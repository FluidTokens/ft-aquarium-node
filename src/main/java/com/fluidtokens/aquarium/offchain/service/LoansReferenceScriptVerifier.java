package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks, at startup, that every configured {@code loans.liquidation.reference-scripts.*} UTxO
 * really publishes the script this repo derives for that validator — and refuses to start if it
 * does not.
 * <p>
 * A reference-script coordinate is a deployment coordinate like the config NFT policy ids
 * {@link LoansConfigVerifier} checks, and it fails the same way: FluidTokens redeploys v4, the
 * coordinates in {@code application.yaml} go stale, and the bot builds transactions that point at
 * reference inputs carrying <em>somebody else's</em> validator. That transaction does not merely
 * fail — it fails in phase-2 evaluation, with the collateral already forfeit. Turning it into a
 * startup failure is the whole point of this class, and that failure is an answer, not an outage.
 *
 * <h2>A sibling of {@link LoansConfigVerifier} rather than part of it</h2>
 * Same failure taxonomy, deliberately the same shape — hard fail on a mismatch, soft warn on a
 * transient backend error, a 4xx treated as an answer. It is separate because it needs
 * {@link AppConfig.LiquidationConfiguration}, which only exists on a node that runs the bot, and
 * because bolting a fourth constructor parameter onto {@code LoansConfigVerifier} would change the
 * shape its own tests pin.
 */
@Service
@Slf4j
public class LoansReferenceScriptVerifier {

    /**
     * Everything this class needs from a backend: read one transaction output. Narrowed to a single
     * method for the same reason {@code LiquidationExecutor.TransactionSubmitter} is — a concrete
     * {@link BFBackendService} cannot be stubbed, and a hard-fail nobody can write a test for is a
     * hard-fail that quietly becomes a log line. The three mutations that matter here (deleting the
     * mismatch throw, softening a 4xx, accepting a UTxO with no reference script) are only
     * detectable because this seam exists.
     */
    @FunctionalInterface
    public interface TxOutputLookup {

        /** The output at {@code txHash#index}, spent or unspent. */
        Result<Utxo> lookup(String txHash, int outputIndex) throws Exception;
    }

    private final LoansContractRegistry registry;
    private final AppConfig.LiquidationConfiguration configuration;
    private final TxOutputLookup txOutputLookup;
    private final boolean failOnUnreachable;

    @Autowired
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        BFBackendService bfBackendService,
                                        @Value("${loans.verify-config.fail-on-unreachable:false}")
                                        boolean failOnUnreachable) {
        this(registry, configuration,
                (txHash, index) -> bfBackendService.getUtxoService().getTxOutput(txHash, index),
                failOnUnreachable);
    }

    /** The same verifier with the lookup stated, so its failure taxonomy can be driven from a test. */
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        TxOutputLookup txOutputLookup,
                                        boolean failOnUnreachable) {
        this.registry = registry;
        this.configuration = configuration;
        this.txOutputLookup = txOutputLookup;
        this.failOnUnreachable = failOnUnreachable;
    }

    @PostConstruct
    public void verify() {
        Map<String, Expectation> expected = expectations();
        if (expected.isEmpty()) {
            log.info("No liquidation reference scripts configured; every validator will travel in "
                    + "the witness set, which is over maxTxSize for a Liquidate transaction");
            return;
        }

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Expectation> entry : expected.entrySet()) {
            String key = entry.getKey();
            Expectation expectation = entry.getValue();
            String published;
            try {
                published = publishedScriptHash(key, expectation.input());
            } catch (ReferenceScriptUnreachableException e) {
                if (failOnUnreachable) {
                    throw new IllegalStateException(
                            "Cannot verify liquidation reference scripts: " + e.getMessage(), e);
                }
                log.warn("Could not verify {} against chain ({}). Continuing unverified — the "
                                + "coordinate may point at a superseded deployment.", key, e.getMessage());
                continue;
            }
            if (!expectation.derivedScriptHash().equals(published)) {
                mismatches.add("%s (%s#%d): this node derives %s, the utxo publishes %s".formatted(
                        key, expectation.input().getTransactionId(), expectation.input().getIndex(),
                        expectation.derivedScriptHash(), published));
            }
        }

        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("""
                    Lending v4 reference-script mismatch — the configured reference-script UTxOs do \
                    not publish the validators this node derives. The contracts were almost \
                    certainly redeployed; update loans.liquidation.reference-scripts.* in \
                    application.yaml. Mismatches: """ + String.join("; ", mismatches));
        }
        log.info("Lending v4 reference scripts verified against chain: {} coordinates match the "
                + "derived script hashes", expected.size());
    }

    /** One configured coordinate and the script hash this repo says must be at it. */
    private record Expectation(TransactionInput input, String derivedScriptHash) {
    }

    /**
     * The configured subset, keyed by the property an operator would have to fix. Unset coordinates
     * are absent rather than checked: "not published" is a legal configuration, it just makes the
     * transaction larger.
     */
    private Map<String, Expectation> expectations() {
        LiquidateTransactionBuilder.ReferenceScripts scripts = configuration.getReferenceScripts();
        Map<String, Expectation> expected = new LinkedHashMap<>();
        put(expected, "loans.liquidation.reference-scripts.loan",
                scripts.loan(), registry.getLoanPolicyId());
        put(expected, "loans.liquidation.reference-scripts.loan-spend",
                scripts.loanSpend(), registry.getLoanSpendScriptHash());
        put(expected, "loans.liquidation.reference-scripts.lender-manager",
                scripts.lenderManager(), registry.getLenderManagerWithdrawScriptHash());
        put(expected, "loans.liquidation.reference-scripts.lender-manager-spend",
                scripts.lenderManagerSpend(), registry.getLenderManagerSpendScriptHash());
        put(expected, "loans.liquidation.reference-scripts.loan-claim-action",
                scripts.loanClaimAction(), registry.getLoanClaimActionScriptHash());
        put(expected, "loans.liquidation.reference-scripts.lm-liquidate-action",
                scripts.lmLiquidateAction(), registry.getLmLiquidateActionScriptHash());
        put(expected, "loans.liquidation.reference-scripts.asset-manager",
                scripts.assetManager(), registry.getAssetManagerWithdrawScriptHash());
        put(expected, "loans.liquidation.reference-scripts.lm-liquidate-and-pay-in-advance-action",
                scripts.lmLiquidateAndPayInAdvanceAction(),
                registry.getLmLiquidateAndPayInAdvanceActionScriptHash());
        return expected;
    }

    private static void put(Map<String, Expectation> expected, String key, TransactionInput input,
                            String derivedScriptHash) {
        if (input != null) {
            expected.put(key, new Expectation(input, derivedScriptHash));
        }
    }

    /**
     * The {@code reference_script_hash} the chain reports for one output.
     * <p>
     * A found output with no reference script is a mismatch, not an outage: the coordinate names a
     * real UTxO that simply is not a reference-script UTxO. Only transport failures and 5xx are
     * treated as "we could not ask".
     */
    private String publishedScriptHash(String key, TransactionInput input) {
        Result<Utxo> result;
        try {
            result = txOutputLookup.lookup(input.getTransactionId(), input.getIndex());
        } catch (Exception e) {
            throw new ReferenceScriptUnreachableException(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (result == null) {
            throw new ReferenceScriptUnreachableException("no response for " + key);
        }
        if (!result.isSuccessful()) {
            int code = result.code();
            String detail = "HTTP %d for %s (%s#%d): %s".formatted(code, key,
                    input.getTransactionId(), input.getIndex(), result.getResponse());
            // A 4xx is an answer: a 404 means the output the coordinate names does not exist, which
            // is precisely the stale-coordinate case this class exists to catch.
            if (code >= 400 && code < 500 && code != 429) {
                throw new IllegalStateException("Lending v4 reference-script lookup rejected — "
                        + detail + ". The configured coordinate is stale.");
            }
            throw new ReferenceScriptUnreachableException(detail);
        }

        Utxo utxo = result.getValue();
        if (utxo == null) {
            throw new IllegalStateException("%s names %s#%d, which the chain does not have"
                    .formatted(key, input.getTransactionId(), input.getIndex()));
        }
        String hash = utxo.getReferenceScriptHash();
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException("%s names %s#%d, which carries no reference script"
                    .formatted(key, input.getTransactionId(), input.getIndex()));
        }
        return hash;
    }

    /** Signals "we could not ask the chain", as opposed to "we asked and the answer was wrong". */
    private static class ReferenceScriptUnreachableException extends RuntimeException {
        ReferenceScriptUnreachableException(String message) {
            super(message);
        }
    }
}
