package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-134 B3b: one real liquidation built through {@link LiquidateTransactionBuilder}'s
 * <b>PRODUCTION constructor</b> — the one {@code YaciConfig} calls — with only its LEAVES supplied by the
 * rig: the UTxO set, the protocol parameters, the script bytes and the evaluator. Every other dry-eval
 * test in this package uses an offline constructor; this one exists because the 2026-08-21 incident was
 * a rig supplying what production must earn (CLAUDE.md, "Promoting test code to src/main requires a
 * production-wiring test").
 *
 * <h2>Why this loan</h2>
 * {@link RealEquityLoanDryEvalTest}'s real preview loan, because it has an <b>oracle leg</b>: the oracle
 * script is a third party's, reached only through a reference input, and the registry cannot derive
 * it. That is the script a PARTIAL {@code withReferenceScripts} list silently leaves unpriced (CCL traps
 * 9 and 13 — the 2026-08-24 rejection), so it is the one this test must see priced. The six loans-v4
 * validators travel by reference too, from synthetic coordinates whose UTxOs carry the derived hashes.
 *
 * <h2>What it proves</h2>
 * <ol>
 *   <li>The redeemers carry the evaluator's ex-units, read off the DESERIALISED transaction (CCL trap 8).</li>
 *   <li>The reference-script fee is charged for every referenced script, the oracle's included.</li>
 *   <li>No script is both witnessed and referenced ({@code ExtraneousScriptWitnessesUTXOW}).</li>
 *   <li>A failed evaluation refuses the build — the production flag is strict.</li>
 *   <li>A short wallet never spends a published reference script held at the bot's own address
 *       (CCL trap 9b, the SELECTOR seam), and the constructor refuses a null evaluator (CCL trap 8).</li>
 * </ol>
 */
@Slf4j
class LiquidateProductionWiringTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the loan, the bond and the oracle: RealEquityLoanDryEvalTest's preview fixtures ----------

    private static final String LOAN_TX =
            "69aee8a016d4d20a49404486c12d8986d7ba4b7bac520840a966f1169a2eecd6";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;
    private static final String LOAN_ID = "724088bab4719b698cf5c74bb6f4c8ec5a28a5a2ccaf2c2975f50e1e";

    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a0193f25e0001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c0001357d1b94be1b55ea87f85fe"
                    + "9c5236f647a09506578dc493f791259d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87980"
                    + "00581d0001357d1b94be1b55ea87f85fe9c5236f647a09506578dc493f791259d8799f4040ffff";

    private static final String LOAN_ADDRESS = LoanFixtures.baseScriptAddress(
            REGISTRY.getLoanSpendScriptHash(), "9e39d6f9824de5f24ac1d73243ebd54bbcaf764e56de11d0c23db9a8");
    private static final String BOND_ADDRESS = LoanFixtures.baseScriptAddress(
            REGISTRY.getLenderManagerSpendScriptHash(), "1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3");

    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_805_890L;

    private static final AssetType ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
    private static final AssetType C3_FEED_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");
    private static final String ORACLE_SCRIPT_HASH = "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final TransactionInput ORACLE_REF_INPUT = new TransactionInput(
            "cc4721afdf4721f8f179b3afddb8e096805c0fad16afe54687d7368d12bd769c", 0);
    private static final TransactionInput ORACLE_REF_SCRIPT = new TransactionInput(
            "ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f", 0);
    private static final TransactionInput C3_PROVIDER = new TransactionInput(
            "a17501465ed79dbc6cb25e2e99edbc421b1baa9d100b6780da89770702b235a5", 0);
    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";
    private static final String C3_PROVIDER_DATUM_HEX = "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff";

    private static final BigInteger PRICE = BigInteger.valueOf(338163);
    private static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(1_000_000);
    private static final long FEED_VALID_FROM = 1_787_135_064_288L;
    private static final long FEED_VALID_TO = 1_787_135_664_288L;

    /** Pinned, never wall clock: inside the pinned feed window (see RealEquityLoanDryEvalTest). */
    private static final long VALID_FROM = 1_787_135_100_000L;
    private static final long VALID_TO = VALID_FROM + 120_000L;
    private static final long MARGIN = 300_000L;

    private static final Utxo CONFIG_UTXO = LoanFixtures.syntheticLatestConfigUtxo("f1".repeat(32), 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.syntheticLatestLmConfigUtxo("f2".repeat(32), 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo("e0".repeat(32), 0,
            LoanFixtures.botAddress(), 50_000_000L);

    /** Six synthetic coordinates whose UTxOs carry the derived loans-v4 hashes — see {@link #universe}. */
    private static final LiquidateTransactionBuilder.ReferenceScripts REFERENCE_SCRIPTS =
            new LiquidateTransactionBuilder.ReferenceScripts(
                    new TransactionInput("a1".repeat(32), 0),
                    new TransactionInput("a2".repeat(32), 0),
                    new TransactionInput("a3".repeat(32), 0),
                    new TransactionInput("a4".repeat(32), 0),
                    new TransactionInput("a5".repeat(32), 0),
                    new TransactionInput("a6".repeat(32), 0),
                    null);

    /** The six loans-v4 validators, in {@link #REFERENCE_SCRIPTS}' order. */
    private static final List<PlutusScript> REGISTRY_SCRIPTS = List.of(REGISTRY.getLoanScript(),
            REGISTRY.getLoanSpendScript(), REGISTRY.getLenderManagerScript(),
            REGISTRY.getLenderManagerSpendScript(), REGISTRY.getLoanClaimActionScript(),
            REGISTRY.getLmLiquidateActionScript());

    /** CCL's placeholder mem (CCL trap 8): a redeemer still carrying it was never costed. */
    private static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);

    // ======================================================================================

    /** (i) and (iii): priced off the built bytes, and no script both witnessed and referenced. */
    @Test
    void theProductionConstructorShipsTheEvaluatedExUnitsAndNoDuplicateScript() throws Exception {
        Recording evaluator = new Recording(aiken());
        Transaction built = production(new HashCheckedScriptSupplier(rigScripts()), evaluator).build(request());

        assertEquals(1, evaluator.calls.get(), "exactly one evaluation per build");
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(8, redeemers.size(), "two spends, one mint, five withdrawals (the oracle's included)");
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            EvaluationResult costing = evaluator.last.get().stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag() && r.getIndex() == redeemer.getIndex().intValue())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no evaluation for " + key));
            assertEquals(costing.getExUnits().getMem(), redeemer.getExUnits().getMem(),
                    "declared mem for " + key + " is not the evaluated one");
            assertEquals(costing.getExUnits().getSteps(), redeemer.getExUnits().getSteps(),
                    "declared steps for " + key + " is not the evaluated one");
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER_MEM) > 0,
                    key + " carries CCL's 10000-mem placeholder: the evaluator was not load-bearing");
        }

        Map<TransactionInput, Utxo> byRef = new java.util.HashMap<>();
        universe().forEach(u -> byRef.put(new TransactionInput(u.getTxHash(), u.getOutputIndex()), u));
        Set<String> referenced = new HashSet<>();
        for (TransactionInput input : reread.getBody().getReferenceInputs()) {
            Utxo utxo = byRef.get(input);
            if (utxo != null && utxo.getReferenceScriptHash() != null) {
                referenced.add(utxo.getReferenceScriptHash());
            }
        }
        assertTrue(referenced.contains(ORACLE_SCRIPT_HASH), "the oracle script travels by reference");
        assertEquals(7, referenced.size(), "six loans-v4 validators and the oracle, all by reference");
        Set<String> witnessed = witnessedScriptHashes(reread.getWitnessSet());
        witnessed.retainAll(referenced);
        assertTrue(witnessed.isEmpty(),
                "scripts both witnessed and referenced (ExtraneousScriptWitnessesUTXOW): " + witnessed);
    }

    /**
     * (ii) Every referenced script is priced, the ORACLE'S included. Compared against the same build
     * through the same production constructor whose script supplier serves nothing — the state in which
     * cardano-client-lib charges the reference-script fee as zero (CCL trap 9). Everything else is
     * identical (same body, same redeemers, same ex-units), so the fee delta is the reference-script fee.
     */
    @Test
    void theReferenceScriptFeeIsChargedForEveryReferencedScriptIncludingTheOracles() throws Exception {
        Transaction priced = Transaction.deserialize(
                production(new HashCheckedScriptSupplier(rigScripts()), new Recording(aiken()))
                        .build(request()).serialize());
        ScriptSupplier servesNothing = hash -> Optional.empty();
        Transaction unpriced = Transaction.deserialize(
                production(servesNothing, new Recording(aiken())).build(request()).serialize());

        long registryBytes = 0;
        for (PlutusScript script : REGISTRY_SCRIPTS) {
            registryBytes += script.scriptRefBytes().length;
        }
        long oracleBytes = oracleScript().scriptRefBytes().length;
        BigDecimal perByte = EvalFixtures.protocolParams().getProtocolParams().getMinFeeRefScriptCostPerByte();
        BigInteger flatFloor = perByte.multiply(BigDecimal.valueOf(registryBytes + oracleBytes))
                .setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        BigInteger oracleShare = perByte.multiply(BigDecimal.valueOf(oracleBytes))
                .setScale(0, RoundingMode.CEILING).toBigIntegerExact();

        BigInteger delta = priced.getBody().getFee().subtract(unpriced.getBody().getFee());
        log.info("FAB-134 B3b ref-script fee: priced {} unpriced {} delta {} | registry {} B + oracle {} B "
                        + "= {} B x {} = flat floor {} (oracle share {})",
                priced.getBody().getFee(), unpriced.getBody().getFee(), delta, registryBytes, oracleBytes,
                registryBytes + oracleBytes, perByte, flatFloor, oracleShare);

        // Tolerance: the two bodies differ ONLY in the fee field and the change output's coin, and the
        // ledger's size fee moves by minFeeA (44) per byte. Both fees and both change coins sit in the
        // same CBOR width class (uint32 for the fees, uint32/uint64 for the coins), so the honest size
        // difference is 0 bytes; one byte each for the fee and the change coin, 2 x 44 = 88 lovelace,
        // is allowed for a width-class boundary. It must stay below the oracle's share, or this
        // assertion could not tell "the oracle was priced" from "it was not".
        BigInteger tolerance = BigInteger.valueOf(2 * 44);
        assertTrue(tolerance.compareTo(oracleShare) < 0, "the tolerance would hide the oracle's fee");
        assertTrue(delta.compareTo(flatFloor.subtract(tolerance)) >= 0,
                "the reference-script fee charged (" + delta + ") is below the flat floor for all seven "
                        + "referenced scripts (" + flatFloor + ", the oracle's share " + oracleShare
                        + "): a referenced script went unpriced, which the ledger rejects as FeeTooSmallUTxO");
    }

    /** (iv) The production flag is strict: an evaluator error refuses, never placeholder ex-units. */
    @Test
    void anEvaluatorErrorRefusesTheBuildOnTheProductionPath() {
        TransactionEvaluator failing = (cbor, inputs) -> Result.error("ScriptFailures: {}");
        LiquidateTransactionBuilder.RefusedException refused = assertThrows(
                LiquidateTransactionBuilder.RefusedException.class,
                () -> production(new HashCheckedScriptSupplier(rigScripts()), failing).build(request()));
        assertEquals(LiquidateTransactionBuilder.Refusal.SCRIPT_COST_EVALUATION_FAILED, refused.getReason(),
                "a failed evaluation on the production path must refuse by name, not ship placeholders");
    }

    /**
     * (v) CCL trap 9b, the SELECTOR seam. The bot address holds the six published loans-v4 reference
     * scripts (ada-only, 20 ADA each) and ONE short wallet utxo. When the wallet cannot cover the build,
     * cardano-client-lib's ChangeOutputAdjustments tops the change up through the context's UtxoSelector
     * ({@code findFirst(sender, adaOnly && qty > required)}, no reference-script check) — so without the
     * selector installed in {@code preBalanceTx} it consumes a published script. Measured 2026-10-04 on
     * this rig with that line deleted: the failure text is recorded in the FAB-134 B3b-1 pin commit.
     * For every wallet value the build either refuses or spends no reference-script utxo.
     */
    @Test
    void aShortWalletNeverSpendsAPublishedReferenceScriptHeldAtTheBotAddress() throws Exception {
        long[] walletLovelace = {2_000_000L, 2_500_000L, 2_750_000L, 3_000_000L, 3_500_000L, 4_000_000L,
                6_000_000L};
        int built = 0;
        List<String> outcomes = new ArrayList<>();
        for (long lovelace : walletLovelace) {
            Utxo wallet = LoanFixtures.adaUtxo("e1".repeat(32), 0, LoanFixtures.botAddress(), lovelace);
            List<Utxo> universe = universe(wallet, LoanFixtures.botAddress());
            Set<TransactionInput> published = new HashSet<>();
            universe.stream().filter(u -> u.getReferenceScriptHash() != null)
                    .forEach(u -> published.add(new TransactionInput(u.getTxHash(), u.getOutputIndex())));
            assertEquals(7, published.size(), "six loans-v4 reference scripts and the oracle's");

            Transaction transaction;
            try {
                transaction = production(universe, new HashCheckedScriptSupplier(rigScripts()),
                        new Recording(aiken(universe))).build(request(wallet));
            } catch (LiquidateTransactionBuilder.RefusedException refused) {
                outcomes.add(lovelace + ": refused " + refused.getReason());
                continue;
            }
            Transaction reread = Transaction.deserialize(transaction.serialize());
            List<TransactionInput> spent = new ArrayList<>(reread.getBody().getInputs());
            if (reread.getBody().getCollateral() != null) {
                spent.addAll(reread.getBody().getCollateral());
            }
            for (TransactionInput input : spent) {
                assertTrue(!published.contains(input), "wallet " + lovelace + " lovelace: the build spends "
                        + "the published reference-script utxo " + input.getTransactionId() + "#"
                        + input.getIndex() + " (CCL trap 9b — the selector seam is unguarded)");
            }
            built++;
            outcomes.add(lovelace + ": built, " + reread.getBody().getInputs().size() + " inputs");
        }
        log.info("FAB-134 B3b-1 short-wallet sweep: {}", outcomes);
        assertTrue(built > 0, "no wallet value built at all, so the sweep proves nothing: " + outcomes);
    }

    /** (vi) The production constructor has no null-evaluator form (CCL trap 8). */
    @Test
    void theProductionConstructorRefusesANullEvaluator() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> production(new HashCheckedScriptSupplier(rigScripts()), null));
        assertEquals("scriptCostEvaluator", npe.getMessage());
    }

    // ======================================================================================
    // The rig's leaves
    // ======================================================================================

    /** The production constructor, exactly the one YaciConfig calls. */
    private static LiquidateTransactionBuilder production(ScriptSupplier scripts, TransactionEvaluator evaluator) {
        return production(universe(), scripts, evaluator);
    }

    private static LiquidateTransactionBuilder production(List<Utxo> universe, ScriptSupplier scripts,
                                                          TransactionEvaluator evaluator) {
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), scripts, evaluator);
    }

    /** The provider leaf: the registry's applied scripts plus the deployed oracle, served by hash. */
    private static ScriptSupplier rigScripts() {
        return EvalFixtures.scriptSupplier(REGISTRY, List.of(oracleScript()));
    }

    private static AikenTransactionEvaluator aiken() {
        return aiken(universe());
    }

    private static AikenTransactionEvaluator aiken(List<Utxo> universe) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                rigScripts(), SlotConfigs.preview());
    }

    /** Records how often it was asked and what it last returned. */
    private static final class Recording implements TransactionEvaluator {
        final TransactionEvaluator delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<EvaluationResult>> last = new AtomicReference<>();

        Recording(TransactionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                throws com.bloxbean.cardano.client.api.exception.ApiException {
            calls.incrementAndGet();
            Result<List<EvaluationResult>> result = delegate.evaluateTx(cbor, inputUtxos);
            last.set(result.getValue());
            return result;
        }
    }

    private static LiquidateTransactionBuilder.Request request() {
        return request(WALLET_UTXO);
    }

    private static LiquidateTransactionBuilder.Request request(Utxo wallet) {
        LoanDatum datum = new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
        Loan loan = new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID, BOND_DATUM_HEX, bondDatum);
        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR, FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);
        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, OraclePriceFeed.unit(),
                oracle.feed(), VALID_FROM);
        return new LiquidateTransactionBuilder.Request(
                List.of(new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo(), bondUtxo())),
                CONFIG_UTXO, LM_CONFIG_UTXO, Map.of(ORACLE_NFT.toUnit(), oracle), wallet,
                LoanFixtures.botAddress(), VALID_FROM, VALID_TO, MARGIN, REFERENCE_SCRIPTS);
    }

    private static Utxo loanUtxo() {
        return LoanFixtures.utxo(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), LOAN_DATUM_HEX);
    }

    private static Utxo bondUtxo() {
        return LoanFixtures.utxo(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), BOND_DATUM_HEX);
    }

    /**
     * The UTxO set: loan, bond, configs, wallet, the three oracle UTxOs as preview holds them (the
     * oracle's reference-script UTxO carrying the oracle's hash — a Blockfrost Utxo never carries the
     * bytes), and the six published loans-v4 reference-script UTxOs carrying the derived hashes.
     */
    private static List<Utxo> universe() {
        return universe(WALLET_UTXO, null);
    }

    /**
     * @param referenceScriptAddress where the six loans-v4 reference-script UTxOs sit; {@code null} puts
     *                               each at its own script's enterprise address
     */
    private static List<Utxo> universe(Utxo wallet, String referenceScriptAddress) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, wallet,
                loanUtxo(), bondUtxo()));
        universe.add(LoanFixtures.utxo(ORACLE_REF_INPUT.getTransactionId(), ORACLE_REF_INPUT.getIndex(),
                ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(LoanFixtures.unit(ORACLE_NFT), BigInteger.ONE)), null));
        universe.add(Utxo.builder().txHash(ORACLE_REF_SCRIPT.getTransactionId())
                .outputIndex(ORACLE_REF_SCRIPT.getIndex()).address(ORACLE_ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                .referenceScriptHash(ORACLE_SCRIPT_HASH).build());
        universe.add(LoanFixtures.utxo(C3_PROVIDER.getTransactionId(), C3_PROVIDER.getIndex(),
                C3_PROVIDER_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(LoanFixtures.unit(C3_FEED_NFT), BigInteger.ONE)), C3_PROVIDER_DATUM_HEX));
        List<TransactionInput> coordinates = List.of(REFERENCE_SCRIPTS.loan(), REFERENCE_SCRIPTS.loanSpend(),
                REFERENCE_SCRIPTS.lenderManager(), REFERENCE_SCRIPTS.lenderManagerSpend(),
                REFERENCE_SCRIPTS.loanClaimAction(), REFERENCE_SCRIPTS.lmLiquidateAction());
        for (int i = 0; i < coordinates.size(); i++) {
            String hash = hashOf(REGISTRY_SCRIPTS.get(i));
            universe.add(Utxo.builder().txHash(coordinates.get(i).getTransactionId())
                    .outputIndex(coordinates.get(i).getIndex())
                    .address(referenceScriptAddress != null ? referenceScriptAddress : LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        return universe;
    }

    private static Set<String> witnessedScriptHashes(TransactionWitnessSet witnesses) {
        Set<String> hashes = new HashSet<>();
        Stream.of(witnesses.getPlutusV1Scripts(), witnesses.getPlutusV2Scripts(), witnesses.getPlutusV3Scripts())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .forEach(script -> hashes.add(hashOf(script)));
        return hashes;
    }

    private static PlutusScript oracleScript() {
        try {
            PlutusScript script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    LoanFixtures.fixture("preview-oracle-script.hex"), PlutusVersion.v3);
            assertEquals(ORACLE_SCRIPT_HASH, hashOf(script), "the oracle fixture is not the deployed oracle");
            return script;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script", e);
        }
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash a script", e);
        }
    }
}
