package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Confirms that the reward accounts used by liquidation withdrawals exist on chain. A script
 * withdrawal can evaluate successfully and still fail at submit when its reward account was never
 * registered (findings §57.8), so every answer other than a current {@code registered} event is
 * deliberately fail-closed.
 */
@Service
public class WithdrawAccountRegistration {

    public enum Route { PLAIN, PAY_IN_ADVANCE, CONVERT }

    public enum Status { REGISTERED, NOT_REGISTERED, UNKNOWN }

    public record Check(String label, String scriptHash, String stakeAddress, Status status,
                        String detail) {
        public boolean confirmed() {
            return status == Status.REGISTERED;
        }
    }

    public record Fetched(int httpStatus, String body) {
    }

    @FunctionalInterface
    public interface RegistrationsFetcher {
        Fetched fetch(String pathAndQuery) throws Exception;
    }

    static final long REGISTERED_TTL_MILLIS = 600_000L;
    static final long UNCONFIRMED_TTL_MILLIS = 60_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Cached(Status status, String detail, long expiresAt) {
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final RegistrationsFetcher fetcher;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public WithdrawAccountRegistration(LoansContractRegistry registry, AppConfig.Network network,
                                       BFBackendService backendService) {
        this(registry, network.getCardanoNetwork(), productionFetcher(backendService),
                System::currentTimeMillis);
    }

    /** Test seam for the complete response taxonomy and the TTL clock. */
    public WithdrawAccountRegistration(LoansContractRegistry registry, Network network,
                                       RegistrationsFetcher fetcher, LongSupplier clock) {
        this.registry = registry;
        this.network = network;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    private static RegistrationsFetcher productionFetcher(BFBackendService backendService) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String baseUrl = backendService.getBaseUrl().replaceFirst("/+$", "");
        String projectId = backendService.getProjectId();
        return pathAndQuery -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                        .timeout(Duration.ofSeconds(10))
                        .header("project_id", projectId)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new Fetched(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Blockfrost registration request was interrupted");
            } catch (Exception e) {
                // Do not attach the request: its headers carry the project id.
                throw new IOException("Blockfrost registration request failed ("
                        + e.getClass().getSimpleName() + ")");
            }
        };
    }

    /** Newest event first, one event: the account's current registration state. */
    public static String registrationsPath(String stakeAddress) {
        return "/accounts/" + stakeAddress + "/registrations?order=desc&count=1";
    }

    /** The first event's action, or null when the input has no event to inspect. */
    public static String mostRecentAction(JsonNode events) {
        return events != null && events.isArray() && !events.isEmpty()
                && events.get(0).hasNonNull("action")
                ? events.get(0).get("action").asText() : null;
    }

    static Status classify(int httpStatus, String body) {
        if (httpStatus == 404) {
            return Status.NOT_REGISTERED;
        }
        if (httpStatus != 200) {
            return Status.UNKNOWN;
        }
        try {
            JsonNode events = MAPPER.readTree(body);
            if (events == null || !events.isArray()) {
                return Status.UNKNOWN;
            }
            if (events.isEmpty()) {
                return Status.NOT_REGISTERED;
            }
            String action = mostRecentAction(events);
            if (action == null) {
                return Status.UNKNOWN;
            }
            return "registered".equals(action) ? Status.REGISTERED : Status.NOT_REGISTERED;
        } catch (Exception e) {
            return Status.UNKNOWN;
        }
    }

    public static String stakeAddress(String scriptHash, Network network) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).toBech32();
    }

    /** Check one reward address, using a shorter cache life for every unconfirmed answer. */
    public Check check(String label, String rewardAddressBech32) {
        String scriptHash = HexUtil.encodeHexString(new Address(rewardAddressBech32)
                .getDelegationCredentialHash().orElseThrow());
        long now = clock.getAsLong();
        Cached cached = cache.get(rewardAddressBech32);
        if (cached != null && now < cached.expiresAt()) {
            return new Check(label, scriptHash, rewardAddressBech32, cached.status(), cached.detail());
        }

        Status status;
        String detail;
        try {
            Fetched fetched = fetcher.fetch(registrationsPath(rewardAddressBech32));
            status = classify(fetched.httpStatus(), fetched.body());
            detail = detail(fetched.httpStatus(), fetched.body(), status);
        } catch (Exception e) {
            status = Status.UNKNOWN;
            detail = "registration lookup failed (" + causeChain(e)
                    + ") — not read as registered";
        }

        long ttl = status == Status.REGISTERED ? REGISTERED_TTL_MILLIS
                : UNCONFIRMED_TTL_MILLIS;
        cache.put(rewardAddressBech32, new Cached(status, detail, now + ttl));
        return new Check(label, scriptHash, rewardAddressBech32, status, detail);
    }

    private static String detail(int httpStatus, String body, Status status) {
        if (status == Status.REGISTERED) {
            return "most recent registration action is registered";
        }
        if (httpStatus == 404) {
            return "reward account has no registration history";
        }
        if (httpStatus != 200) {
            return "registration lookup returned HTTP " + httpStatus + " — not read as registered";
        }
        try {
            JsonNode events = MAPPER.readTree(body);
            if (events != null && events.isArray() && events.isEmpty()) {
                return "reward account has no registration history";
            }
            String action = mostRecentAction(events);
            if (action != null) {
                return "most recent registration action is " + action;
            }
        } catch (Exception ignored) {
            // The taxonomy, rather than parser internals or response content, is operator-facing.
        }
        return "registration response could not be read — not read as registered";
    }

    public List<Check> routeChecks(Route route) {
        List<NamedHash> hashes = switch (route) {
            case PLAIN -> List.of(
                    new NamedHash("loan", registry.getLoanPolicyId()),
                    new NamedHash("loan-claim-action", registry.getLoanClaimActionScriptHash()),
                    new NamedHash("lender-manager", registry.getLenderManagerWithdrawScriptHash()),
                    new NamedHash("lm-liquidate-action", registry.getLmLiquidateActionScriptHash()));
            case PAY_IN_ADVANCE -> List.of(
                    new NamedHash("loan", registry.getLoanPolicyId()),
                    new NamedHash("loan-claim-action", registry.getLoanClaimActionScriptHash()),
                    new NamedHash("lender-manager", registry.getLenderManagerWithdrawScriptHash()),
                    new NamedHash("lm-liquidate-and-pay-in-advance-action",
                            registry.getLmLiquidateAndPayInAdvanceActionScriptHash()));
            case CONVERT -> nullableList(
                    new NamedHash("loan", registry.getLoanPolicyId()),
                    new NamedHash("loan-claim-action", registry.getLoanClaimActionScriptHash()),
                    new NamedHash("lender-manager", registry.getLenderManagerWithdrawScriptHash()),
                    new NamedHash("lm-liquidate-and-convert-action",
                            registry.getLmLiquidateAndConvertActionScriptHash()));
        };
        return hashes.stream()
                .filter(named -> named.hash() != null)
                .map(named -> check(named.label(), stakeAddress(named.hash(), network)))
                .toList();
    }

    private static List<NamedHash> nullableList(NamedHash... hashes) {
        return java.util.Arrays.asList(hashes);
    }

    /** Checks the withdrawals in the built body, including dynamic oracle credentials. */
    public List<Check> transactionChecks(Transaction transaction) {
        if (transaction == null || transaction.getBody() == null
                || transaction.getBody().getWithdrawals() == null
                || transaction.getBody().getWithdrawals().isEmpty()) {
            return List.of();
        }
        List<Check> checks = new ArrayList<>();
        for (var withdrawal : transaction.getBody().getWithdrawals()) {
            String rewardAddress = withdrawal.getRewardAddress();
            String hash = HexUtil.encodeHexString(new Address(rewardAddress)
                    .getDelegationCredentialHash().orElseThrow());
            checks.add(check(label(hash), rewardAddress));
        }
        return List.copyOf(checks);
    }

    private String label(String hash) {
        if (hash.equals(registry.getLoanPolicyId())) return "loan";
        if (hash.equals(registry.getLoanClaimActionScriptHash())) return "loan-claim-action";
        if (hash.equals(registry.getLenderManagerWithdrawScriptHash())) return "lender-manager";
        if (hash.equals(registry.getLmLiquidateActionScriptHash())) return "lm-liquidate-action";
        if (hash.equals(registry.getLmLiquidateAndPayInAdvanceActionScriptHash())) {
            return "lm-liquidate-and-pay-in-advance-action";
        }
        if (hash.equals(registry.getLmLiquidateAndConvertActionScriptHash())) {
            return "lm-liquidate-and-convert-action";
        }
        return "oracle or other " + hash;
    }

    private static String causeChain(Throwable thrown) {
        StringBuilder detail = new StringBuilder();
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (!detail.isEmpty()) detail.append(" ⇐ ");
            detail.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) detail.append(": ").append(current.getMessage());
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return detail.toString();
    }

    private record NamedHash(String label, String hash) {
    }
}
