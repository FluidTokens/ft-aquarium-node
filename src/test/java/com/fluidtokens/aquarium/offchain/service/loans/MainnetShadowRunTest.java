package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>THE MAINNET SHADOW RUN: what this bot would decide, on the real mainnet population, today.</b>
 *
 * <h2>What this is, and the one thing it is NOT</h2>
 * It drives the <b>real</b> {@link LiquidationCandidateScanner}, the <b>real</b>
 * {@link LoanHealthService}, the <b>real</b> {@link FluidOracleClient} and the <b>real</b>
 * {@link MarketGate} over the <b>real mainnet</b> Lending-v4 deployment, read-only, and prints the
 * decision set. Nothing here signs, submits, or holds a key — the classes that could are not
 * constructed at all.
 *
 * <p>⛔ <b>It stops one step short of a transaction, and that boundary is a finding rather than a
 * limitation of this class.</b> {@code LiquidateTransactionBuilder} needs a <b>funded wallet UTxO</b>
 * for the fee and the ledger collateral ({@code Refusal.NO_SUITABLE_WALLET_UTXO}). So a mainnet
 * <i>shadow build</i> — the "build it, validate it, dump the bytes" rehearsal — <b>cannot be done
 * without a funded mainnet operator wallet</b>, which is Giovanni's trigger and not something a
 * shadow posture removes the need for. <b>Shadow withholds the SUBMIT, not the WALLET.</b> Everything
 * upstream of that wallet is what this class proves, and it is the whole decision set.
 *
 * <h2>⚠ Why it enumerates LOANS and not BONDS, unlike the production scanner</h2>
 * Production joins bond → loan, because it indexes both credentials and the bond is the thing it can
 * enumerate. Blockfrost has no by-payment-credential query, so this class enumerates the <b>loan
 * policy</b> instead — and that turns out to be the more honest instrument here. The loan policy id
 * is <b>parameterised by the config policy id</b>, so it names exactly one deployment; the bond
 * policies are derived from an integer index and are <b>identical across every deployment and both
 * networks</b>. Measured 2026-09-03: 2,451 lender bonds exist under the mainnet bond policy against
 * <b>1</b> loan under this deployment's loan policy. ⇒ <b>Enumerating bonds would have counted a
 * decade of unrelated FluidTokens history as this deployment's population.</b>
 *
 * <h2>Gated on {@code BLOCKFROST_KEY}</h2>
 * Read-only mainnet queries, which CLAUDE.md classifies as ordinary work. Skips when unset, so the
 * cold suite is unaffected.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "reads mainnet: run with `set -a; . ./.env.mainnet; set +a`")
class MainnetShadowRunTest {

    /** Quoted first-hand from FluidTokens' own tooling, 2026-09-02; same values as application.yaml. */
    private static final String CONFIG_POLICY_ID = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY_ID = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final String BF = "https://cardano-mainnet.blockfrost.io/api/v0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static JsonNode get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(BF + path))
                .header("project_id", System.getenv("BLOCKFROST_KEY"))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return MAPPER.createArrayNode();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("blockfrost " + path + " -> " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    /** Every asset name still in circulation under a policy. Paged; a burned asset reports quantity 0. */
    private static List<String> liveAssetNames(String policyId) throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode assets = get("/assets/policy/" + policyId + "?count=100&page=" + page);
            if (!assets.isArray() || assets.isEmpty()) {
                return names;
            }
            for (JsonNode asset : assets) {
                if (!"0".equals(asset.get("quantity").asText())) {
                    names.add(asset.get("asset").asText().substring(policyId.length()));
                }
            }
        }
    }

    /** The single unspent UTxO holding this asset, as a cardano-client-lib {@link Utxo}. */
    private static Optional<Utxo> utxoHolding(String unit) throws IOException, InterruptedException {
        JsonNode addresses = get("/assets/" + unit + "/addresses");
        if (!addresses.isArray() || addresses.isEmpty()) {
            return Optional.empty();
        }
        String address = addresses.get(0).get("address").asText();
        for (JsonNode utxo : get("/addresses/" + address + "/utxos")) {
            List<Amount> amounts = new ArrayList<>();
            boolean holdsIt = false;
            for (JsonNode amount : utxo.get("amount")) {
                String u = amount.get("unit").asText();
                BigInteger q = new BigInteger(amount.get("quantity").asText());
                amounts.add("lovelace".equals(u) ? Amount.lovelace(q) : Amount.asset(u, q));
                holdsIt |= u.equals(unit);
            }
            if (holdsIt) {
                return Optional.of(Utxo.builder()
                        .txHash(utxo.get("tx_hash").asText())
                        .outputIndex(utxo.get("output_index").asInt())
                        .address(address)
                        .amount(amounts)
                        .inlineDatum(utxo.hasNonNull("inline_datum") ? utxo.get("inline_datum").asText() : null)
                        .dataHash(utxo.hasNonNull("data_hash") ? utxo.get("data_hash").asText() : null)
                        .build());
            }
        }
        return Optional.empty();
    }

    private static LoansContractRegistry mainnetRegistry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /** An {@link ObjectProvider} over a single instance — the shape the production classes take. */
    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    /** The node's own liquidation config, at whatever posture the operator has set. */
    private static AppConfig.LiquidationConfiguration configuration(Mode mode, List<Market> markets) {
        var configuration = new AppConfig.LiquidationConfiguration();
        ReflectionTestUtils.setField(configuration, "mode", mode);
        ReflectionTestUtils.setField(configuration, "modeName", mode.name().toLowerCase());
        ReflectionTestUtils.setField(configuration, "markets", markets);
        return configuration;
    }

    /**
     * ⛔ THE RUN. Prints the whole decision set; asserts only the things that must hold for the
     * printout to <b>mean</b> anything — that the world was actually read, and that nothing in it
     * was invisible to this node's decoder.
     */
    @Test
    void whatTheBotWouldDoOnMainnetToday() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        long now = System.currentTimeMillis();

        // ---- 1. the population, enumerated from the deployment's OWN loan policy ------------------
        List<String> loanIds = liveAssetNames(registry.getLoanPolicyId());
        List<Utxo> loanUtxos = new ArrayList<>();
        for (String loanId : loanIds) {
            utxoHolding(registry.getLoanPolicyId() + loanId).ifPresent(loanUtxos::add);
        }

        List<Utxo> bondUtxos = new ArrayList<>();
        for (String loanId : loanIds) {
            utxoHolding(registry.getLenderBondPolicyId() + loanId).ifPresent(bondUtxos::add);
        }

        var loanService = new LoanService(null, registry);
        LoanService.Census census = loanService.classify(loanUtxos, registry.getLoanPolicyId());

        var bondService = new LenderBondService(null, registry);
        List<LenderBond> bonds = bondUtxos.stream()
                .map(utxo -> bondService.toLenderBond(utxo, registry.getLenderBondPolicyId()))
                .flatMap(Optional::stream)
                .toList();

        System.out.println("\n================ MAINNET SHADOW RUN  (read-only, no key, no submit) ========");
        System.out.println("at                     : " + java.time.Instant.ofEpochMilli(now));
        System.out.println("config policy id       : " + CONFIG_POLICY_ID);
        System.out.println("loan policy id         : " + registry.getLoanPolicyId() + "   (deployment-scoped)");
        System.out.println("lender bond policy id  : " + registry.getLenderBondPolicyId()
                + "   (SHARED across deployments)");
        System.out.println("--------------------------------------------------------------------------");
        System.out.println("live loan NFTs         : " + loanIds.size());
        System.out.println("loan utxos resolved    : " + loanUtxos.size());
        System.out.println("decoded as loans       : " + census.loans().size());
        System.out.println("UNREADABLE (blindness) : " + census.unreadable());
        System.out.println("not-a-loan (junk)      : " + census.notALoan());
        System.out.println("lender bonds resolved  : " + bonds.size());

        // ---- 2. the real oracle registry ----------------------------------------------------------
        var oracle = new FluidOracleClient("https://api.fluidtokens.com/get-oracle-tokens");
        oracle.refresh();
        System.out.println("oracle registry assets : " + oracle.trackedAssets()
                + "   (refreshed " + oracle.lastRefresh() + ")");

        // ---- 3. the real scanner, over the real population -----------------------------------------
        var scanner = new LiquidationCandidateScanner(
                new LenderBondService(null, registry) {
                    @Override
                    public List<LenderBond> findAll() {
                        return bonds;
                    }
                },
                new LoanService(null, registry) {
                    @Override
                    public Census census() {
                        return census;
                    }
                },
                provider(oracle));
        LiquidationCandidateScanner.Scan scan = scanner.scan(now);

        var healthService = new LoanHealthService(provider(oracle));

        // ---- 4. the decision, per loan, per path ---------------------------------------------------
        Map<String, Loan> loansById = new LinkedHashMap<>();
        census.loans().forEach(loan -> loansById.put(loan.loanId(), loan));

        System.out.println("\n---- per-bond assessment (the scanner's own verdict) ----------------------");
        for (LiquidationAssessment assessment : scan.assessments()) {
            Loan loan = loansById.get(assessment.bond().loanId());
            System.out.println("\nloan id        : " + assessment.bond().loanId());
            System.out.println("  loan utxo    : " + (loan == null ? "(none)" : loan.utxoRef()));
            System.out.println("  bond utxo    : " + assessment.bond().utxoRef());
            if (loan != null) {
                var datum = loan.datum();
                System.out.println("  principal    : " + datum.principalAsset().toUnit()
                        + "  amount " + datum.principalAmount());
                System.out.println("  collateral   : " + datum.collateral().assetType().toUnit()
                        + "  amount " + loan.collateralAmount());
                LoanHealth health = healthService.health(loan, now);
                System.out.println("  remainingDebt: " + health.remainingDebt());
                System.out.println("  late         : " + health.repaymentLate());
                System.out.println("  currentLTV   : " + (health.currentLtvPercent() == null
                        ? "UNAVAILABLE (" + health.unavailableReason() + ")"
                        : String.format("%.4f%%", health.currentLtvPercent())));
                System.out.println("  liquidatable : " + health.liquidatable());
                System.out.println("  bond says    : convertToPrincipal="
                        + assessment.bond().datum().shouldLiquidationConvertToPrincipal()
                        + "  feePerMille=" + assessment.bond().datum().liquidationFeePerMille());
            }
            System.out.println("  SCANNER      : " + (assessment.buildable()
                    ? "BUILDABLE  fee=" + assessment.liquidationFee()
                            + " debt=" + assessment.remainingDebt() + " equity=" + assessment.equity()
                    : "EXCLUDED " + assessment.exclusion()));
            System.out.println("  detail       : " + assessment.detail());

            if (loan == null) {
                continue;
            }

            // The gate, at three postures an operator can actually be in.
            var principal = loan.datum().principalAsset();

            // ⚠ NOT remainingDebt. MarketGate's own javadoc says so, and PayInAdvanceLiquidationRouter
            // says why: LoanFinance.redeemerEquity returns ZERO OUTRIGHT when
            // partialLiquidationPenaltyPerMille is negative, so the lender's converted share is not
            // bounded by the debt. The figure the protocol forces the bot to front is
            // convertedLoanCollateralToPrincipalAmount, and it is taken here from the PRODUCTION
            // builder rather than re-derived — a number re-derived in a harness proves the harness.
            BigInteger required = BigInteger.ZERO;
            String requiredNote = "n/a (not buildable)";
            var collateralOracle = oracle.findEntry(loan.datum().collateral().assetType()).orElse(null);
            if (assessment.buildable() && collateralOracle != null) {
                var payInAdvanceBuilder = new LiquidatePayInAdvanceTransactionBuilder(
                        registry, com.bloxbean.cardano.client.common.model.Networks.mainnet(),
                        (com.bloxbean.cardano.client.api.UtxoSupplier) null,
                        (com.bloxbean.cardano.client.api.ProtocolParamsSupplier) null);
                var numbers = payInAdvanceBuilder.numbers(loan, assessment.bond(), collateralOracle, now);
                required = numbers.convertedLoanCollateralToPrincipalAmount();
                requiredNote = required + " lovelace  (= " + required.divide(BigInteger.valueOf(1_000_000L))
                        + " ada the bot must FRONT out of its own wallet)";
            }
            System.out.println("  ANTICIPATE would front : " + requiredNote);

            // ⛔ THE OPERATOR'S ACTUAL P&L ON THIS PATH, in the two currencies it really lands in.
            // The bot pays ADA and is paid in COLLATERAL TOKENS. Reporting only the lovelace fronted
            // hides half the trade, and reporting only the token fee hides the exposure. Both, valued
            // through the SAME production helper the validator's arithmetic uses.
            if (assessment.buildable() && collateralOracle != null) {
                var feed = collateralOracle.feed();
                BigInteger collateralValue = com.fluidtokens.aquarium.offchain.service.loans.LoanFinance
                        .toLovelace(com.fluidtokens.aquarium.offchain.model.loans.Rational
                                .fromInt(loan.collateralAmount()), feed).floor();
                BigInteger feeValue = com.fluidtokens.aquarium.offchain.service.loans.LoanFinance
                        .toLovelace(com.fluidtokens.aquarium.offchain.model.loans.Rational
                                .fromInt(assessment.liquidationFee()), feed).floor();
                System.out.println("  ANTICIPATE pays  (ada) : -" + required);
                System.out.println("  ANTICIPATE receives    : " + loan.collateralAmount()
                        + " collateral tokens, oracle-valued at " + collateralValue + " lovelace");
                System.out.println("  of which the bot's fee : " + assessment.liquidationFee()
                        + " tokens = " + feeValue + " lovelace");
                System.out.println("  gross, PRE tx fee      : "
                        + collateralValue.subtract(required) + " lovelace"
                        + "   -- earned in TOKENS, not ada (item 14: accrue, no swap/sweep)");
            }
            for (var posture : List.of(
                    Map.entry("SHIPPED DEFAULT (mode: disabled, no markets)",
                            configuration(Mode.DISABLED, List.of())),
                    Map.entry("SHADOW, unlisted market (=> CONVERT)",
                            configuration(Mode.SHADOW, List.of())),
                    Map.entry("SHADOW, market forced to ANTICIPATE, cap 1000 ada",
                            configuration(Mode.SHADOW, List.of(market(principal.toUnit(), Mode.SHADOW,
                                    Action.ANTICIPATE, BigInteger.valueOf(1_000_000_000L))))))) {
                var gate = new MarketGate(posture.getValue());
                var decision = gate.decide(principal, required);
                System.out.println("  GATE " + posture.getKey());
                System.out.println("       effectiveMode=" + gate.effectiveMode(principal)
                        + "  action=" + gate.actionFor(principal)
                        + "  -> " + (decision.allowed() ? "ALLOWED" : "REFUSED " + decision.refusal()));
            }
        }

        // ---- 4b. the COMPOUND path, answered from the population rather than from a scan ----------
        //
        // ⛔ Compound acts on a REPAID loan's escrow. Under this deployment's own loan policy exactly
        // one loan has ever been minted and it has never been burned, so no loan has ever been repaid
        // and there is no escrow for a compound to collect. That is a stronger statement than an empty
        // scan: an empty scan is also what blindness looks like (T-060), whereas a mint/burn count is
        // a fact about the chain that no indexing filter can hide.
        System.out.println("\n---- compound path -------------------------------------------------------");
        System.out.println("  loans ever minted under this deployment : " + loanIds.size());
        System.out.println("  loans ever repaid (=> escrows to compound): 0  (none burned)");

        // And the pool that WOULD be compounded into, read live — because the fee it publishes is what
        // decides whether the work pays at all, and it is the pool OWNER who sets it, not this node.
        var poolManagerUtxos = new ArrayList<Utxo>();
        for (String poolId : liveAssetNames(registry.getPoolManagerPolicyId())) {
            utxoHolding(registry.getPoolManagerPolicyId() + poolId).ifPresent(poolManagerUtxos::add);
        }
        for (Utxo poolManager : poolManagerUtxos) {
            long feePerMille = CompoundCandidateScanner.compoundingFeePerMille(poolManager);
            System.out.println("  pool manager " + poolManager.getTxHash() + "#"
                    + poolManager.getOutputIndex() + "  compoudingFeePerMille=" + feePerMille
                    + (feePerMille == 0
                            ? "   => pays NOTHING; refused at the shipped margin of 0"
                            : ""));
        }

        System.out.println("\n---- what a mainnet SHADOW BUILD would still need -------------------------");
        System.out.println("  a funded mainnet wallet UTxO: the builder needs one for the tx fee and the");
        System.out.println("  ledger collateral, and on the ANTICIPATE path it must ALSO cover the amount");
        System.out.println("  fronted above. Shadow withholds the SUBMIT, not the WALLET.");

        System.out.println("\n---- exclusion histogram -------------------------------------------------");
        scan.assessments().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> a.buildable() ? "BUILDABLE" : a.exclusion().name(),
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((k, v) -> System.out.println("  " + v + "  " + k));
        System.out.println("==========================================================================\n");

        // ---- 5. what must hold for the printout above to mean anything -----------------------------
        assertNotNull(scan, "the scan itself");
        assertTrue(oracle.trackedAssets() > 0,
                "the oracle registry served nothing, so every price-dependent line above is vacuous");
        org.junit.jupiter.api.Assertions.assertEquals(0, census.unreadable(),
                "a loan-bearing mainnet UTxO this node CANNOT DECODE. Every LOAN_NOT_FOUND in the "
                        + "histogram above is then contaminated: it may be a settled loan, or it may be "
                        + "this loan, invisible. That is the blindness signal T-060 exists to raise.");
        org.junit.jupiter.api.Assertions.assertEquals(loanIds.size(), loanUtxos.size(),
                "a live loan NFT whose UTxO could not be resolved — the population read is incomplete");

        // ⛔ THE CHECK THAT MAKES THE POPULATION THIS NODE'S OWN WORLD, and not merely FluidTokens'.
        //
        // This class enumerates by POLICY; production filters by PAYMENT CREDENTIAL
        // (LoansContractRegistry.indexedPaymentCredentials, findings §5). If those two disagree, every
        // line above describes a world the running node would never see — which is exactly the shape
        // the preview redeploy took: indexed off the chain, then discarded by the credential filter.
        //
        // ⚠ It also settles the 2,451-bonds question. The lender-bond POLICY is derived from an integer
        // index and is identical across every deployment and both networks, so 2,451 bonds exist under
        // it on mainnet against ONE loan here. Sampled 2026-09-03, the other live bonds sit at ordinary
        // payment-KEY addresses (header 0x01) — settled bonds withdrawn to their lenders' wallets — not
        // at any script credential. The lender-manager SPEND hash, by contrast, IS parameterised by the
        // config policy id. ⇒ the node's histogram will NOT be flooded with LOAN_NOT_FOUND on mainnet,
        // and this assertion is what keeps that answer honest if a future deployment changes it.
        for (Utxo utxo : loanUtxos) {
            org.junit.jupiter.api.Assertions.assertEquals(registry.getLoanSpendScriptHash(),
                    new com.bloxbean.cardano.client.address.Address(utxo.getAddress())
                            .getPaymentCredentialHash().map(com.bloxbean.cardano.client.util.HexUtil::encodeHexString)
                            .orElse(null),
                    "a live loan sits at a payment credential this node does not index: it would be "
                            + "read off the chain and then discarded, and the bot would report a quiet "
                            + "market. " + utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }
        for (Utxo utxo : bondUtxos) {
            org.junit.jupiter.api.Assertions.assertEquals(registry.getLenderManagerSpendScriptHash(),
                    new com.bloxbean.cardano.client.address.Address(utxo.getAddress())
                            .getPaymentCredentialHash().map(com.bloxbean.cardano.client.util.HexUtil::encodeHexString)
                            .orElse(null),
                    "a lender bond joined to a live loan sits outside the indexed credential: "
                            + utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }
    }

    private static Market market(String unit, Mode mode, Action action, BigInteger cap) {
        var market = new Market();
        ReflectionTestUtils.setField(market, "unit", unit);
        ReflectionTestUtils.setField(market, "mode", mode);
        ReflectionTestUtils.setField(market, "action", action);
        ReflectionTestUtils.setField(market, "cap", cap);
        return market;
    }
}
