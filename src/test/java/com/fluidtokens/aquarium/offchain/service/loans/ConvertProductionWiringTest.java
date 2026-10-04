package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-134 B3b-3: one convert built through {@link ConvertTransactionBuilder}'s <b>PRODUCTION
 * constructor</b> — the one {@code YaciConfig} calls — with only its LEAVES supplied by the rig: the UTxO
 * set, the protocol parameters, the script bytes and the evaluator. Until this test no convert had ever
 * been built offline at all; {@code ConvertLiveDryEvalTest} needs a mainnet key and builds through the
 * OFFLINE constructor, which declares its reference scripts and so cannot see what production prices.
 *
 * <h2>The fixture: recorded datums, reconstructed UTxO shells</h2>
 * Every DATUM and every script below is recorded mainnet data; the UTxO VALUES around them are
 * reconstructed (stated per constant). It is a hypothetical on purpose — <i>"had the d832b78e loan still
 * been live when the 2026-10-01 FLDT feed was signed"</i> — and it is coherent because every piece is
 * the current deployment's: the shipped mainnet registry derives exactly the 2026-10-01 config datums
 * ({@code MainnetRegistryMatchesConfigTest}).
 * <ul>
 *   <li>loan and lender-bond datums: {@code mainnet-*-d832b78e.hex}, the convert-eligible candidate
 *       ({@code mainnet-convert-candidate.PROVENANCE.md}; loan id from {@code ConvertOrderPlanTest});</li>
 *   <li>Config / LMConfig datums: {@code mainnet-*-config-datum-2026-10-01.hex}, at the coordinates
 *       {@code ConvertLiveDryEvalTest} pins for them;</li>
 *   <li>the ADA/FLDT Minswap V2 pool: datum {@code mainnet-minswap-pool-ada-fldt.hex}, at its recorded
 *       coordinate and address;</li>
 *   <li>the FLDT oracle: the v1 entry the loan names, signed feed and all, from
 *       {@code mainnet-oracle-registry-2026-10-01.json} through production's {@link FluidOracleClient};
 *       its applied script {@code mainnet-oracle-script-d81a8bea.hex}. Offline the 2026-10-01 signature
 *       still verifies: the transaction's validity interval is pinned inside the feed's own window.</li>
 * </ul>
 *
 * <h2>Why the oracle is the point</h2>
 * The oracle script is a third party's, reached only through a reference input, and the registry
 * cannot derive it. A PARTIAL {@code withReferenceScripts} list (the six registry scripts) makes
 * cardano-client-lib 0.7.2 price ONLY the declared scripts and never consult the supplier
 * ({@code FeeCalculators:125-145}), so the oracle's bytes are charged zero — the shape of the
 * 2026-08-24 {@code FeeTooSmallUTxO} (CCL traps 9 and 13).
 */
@Slf4j
class ConvertProductionWiringTest {

    private static final Network NETWORK = Networks.mainnet();

    // ---- the shipped mainnet deployment (MainnetRegistryMatchesConfigTest's coordinates) -------------

    private static final String CONFIG_POLICY_ID = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG_POLICY_ID = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    private static final LoansContractRegistry REGISTRY = new LoansContractRegistry(CONFIG_POLICY_ID,
            LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND, MS_POOL_POLICY, MS_POOL_SPEND,
            MS_ORDER_SPEND);

    // ---- the candidate --------------------------------------------------------------------------------

    private static final String LOAN_TX = "d832b78e3d4a9ff99dfa8f238ae378b37dbd36b30efd24d68e5786f99786cf99";
    private static final int LOAN_IX = 1;
    private static final int BOND_IX = 3;
    private static final String LOAN_ID = "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    /** Recorded: the loan UTxO holds 100,000,000 FLDT (PROVENANCE). */
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    /** ⚠ RECONSTRUCTED: the loan and bond outputs' lovelace is not recorded. Min-ada-sized. */
    private static final long LOAN_LOVELACE = 2_000_000L;
    private static final long BOND_LOVELACE = 2_000_000L;

    // ---- the recorded coordinates ---------------------------------------------------------------------

    /** The 2026-10-01 Config / LMConfig holders (ConvertLiveDryEvalTest's CONFIG_TX / LM_CONFIG_TX). */
    private static final TransactionInput CONFIG_REF = new TransactionInput(
            "3d800e98a4da21dc9abcce30c145729406fef7db4d5cd3b4ecd6813aa228a75c", 0);
    private static final TransactionInput LM_CONFIG_REF = new TransactionInput(
            "ab3e3aafe7ea0e6fec24d9ac9249e01edb242dee55aeaa2399da097a21177620", 0);
    private static final TransactionInput POOL_REF = new TransactionInput(
            "665195ca95aac79331ce9d83f2902849999e0f2ba98f663df37191ecda3d03c6", 1);
    private static final String POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    private static final String ORACLE_SCRIPT_HASH = "d81a8bea722dc9487e7e693487f6948983af45c3f7897bf1d37916c8";

    /** The bot. Synthetic, as in every sibling rig. */
    private static final String BOT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("11".repeat(28))), NETWORK).getAddress();
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo("e0".repeat(32), 0, BOT_ADDRESS, 50_000_000L);

    /**
     * The six loans-v4 validators a mainnet convert references — exactly the six slots
     * {@code ConvertLiquidationRouter.referenceScripts()} fills from {@code application.yaml}'s
     * {@code loans.liquidation.reference-scripts} (loan, loan-spend, lender-manager, lender-manager-spend,
     * loan-claim-action, lm-liquidate-and-convert-action) — at synthetic coordinates carrying the derived
     * hashes. With the oracle's that is every script the transaction runs, all by reference.
     */
    private static final List<PlutusScript> REGISTRY_SCRIPTS = List.of(REGISTRY.getLoanSpendScript(),
            REGISTRY.getLenderManagerSpendScript(), REGISTRY.getLoanScript(), REGISTRY.getLoanClaimActionScript(),
            REGISTRY.getLenderManagerScript(), REGISTRY.getLmLiquidateAndConvertActionScript());

    /** CCL's placeholder mem (CCL trap 8): a redeemer still carrying it was never costed. */
    private static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);

    // ======================================================================================

    /** Priced off the built bytes, assertStructure holds, and no script is both witnessed and referenced. */
    @Test
    void theProductionConstructorShipsTheEvaluatedExUnitsAndNoDuplicateScript() throws Exception {
        Fixture f = fixture(WALLET_UTXO, null);
        Recording evaluator = new Recording(aiken(f.universe));
        ConvertTransactionBuilder builder = production(f.universe, new HashCheckedScriptSupplier(rigScripts()),
                evaluator);
        Transaction built = builder.build(f.request);

        // Pass 1 is the layout probe and is never script-evaluated (CCL trap 23); only pass 2 asks.
        assertEquals(1, evaluator.calls.get(),
                "exactly one evaluation per convert: pass 2's. A second means the layout probe was evaluated");
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(8, redeemers.size(), "two spends, one burn, five withdrawals (the oracle's included)");
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            EvaluationResult costing = evaluator.last.get().stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag()
                            && r.getIndex() == redeemer.getIndex().intValue())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no evaluation for " + key));
            assertEquals(costing.getExUnits().getMem(), redeemer.getExUnits().getMem(),
                    "declared mem for " + key + " is not the evaluated one");
            assertEquals(costing.getExUnits().getSteps(), redeemer.getExUnits().getSteps(),
                    "declared steps for " + key + " is not the evaluated one");
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER_MEM) > 0,
                    key + " carries CCL's 10000-mem placeholder: the evaluator was not load-bearing");
        }

        // The post-assert ran inside the build; it is re-run here on the DESERIALISED body.
        List<TransactionOutput> outs = reread.getBody().getOutputs();
        builder.assertStructure(reread, f.request,
                indexOfDatum(outs, f.request.plan().successDatum().serializeToHex()),
                indexOfDatum(outs, f.request.plan().refundDatum().serializeToHex()),
                indexOfDatum(outs, f.request.bondUtxo().getInlineDatum()));

        Map<TransactionInput, Utxo> byRef = new HashMap<>();
        f.universe.forEach(u -> byRef.put(new TransactionInput(u.getTxHash(), u.getOutputIndex()), u));
        Set<String> referenced = new HashSet<>();
        for (TransactionInput input : reread.getBody().getReferenceInputs()) {
            Utxo utxo = byRef.get(input);
            if (utxo != null && utxo.getReferenceScriptHash() != null) {
                referenced.add(utxo.getReferenceScriptHash());
            }
        }
        // The rig references exactly what mainnet references: ConvertLiquidationRouter.referenceScripts()'s
        // six keys, plus the oracle's script from its registry entry.
        Set<String> mainnetSlots = Set.of(REGISTRY.getLoanPolicyId(), REGISTRY.getLoanSpendScriptHash(),
                REGISTRY.getLenderManagerWithdrawScriptHash(), REGISTRY.getLenderManagerSpendScriptHash(),
                REGISTRY.getLoanClaimActionScriptHash(), REGISTRY.getLmLiquidateAndConvertActionScriptHash());
        assertEquals(mainnetSlots, f.request.referenceScripts().keySet(),
                "the rig must reference the six slots a mainnet convert references, no more and no fewer");
        assertTrue(referenced.contains(ORACLE_SCRIPT_HASH), "the oracle script travels by reference");
        assertEquals(7, referenced.size(), "six loans-v4 validators and the oracle, all by reference");
        Set<String> witnessed = witnessedScriptHashes(reread.getWitnessSet());
        witnessed.retainAll(referenced);
        assertTrue(witnessed.isEmpty(),
                "scripts both witnessed and referenced (ExtraneousScriptWitnessesUTXOW): " + witnessed);
    }

    /**
     * Every referenced script is priced: the fee delta against the same build through the same
     * production constructor whose script supplier serves NOTHING (the state in which cardano-client-lib
     * charges every reference script zero, CCL trap 9) is at least the flat per-byte fee for all seven.
     */
    @Test
    void theReferenceScriptFeeIsChargedForEveryReferencedScriptIncludingTheOracles() throws Exception {
        Fixture f = fixture(WALLET_UTXO, null);
        Transaction priced = buildProduction(f, new HashCheckedScriptSupplier(rigScripts()));
        ScriptSupplier servesNothing = hash -> Optional.empty();
        Transaction unpriced = buildProduction(f, servesNothing);

        long registryBytes = 0;
        for (PlutusScript script : REGISTRY_SCRIPTS) {
            registryBytes += script.scriptRefBytes().length;
        }
        long oracleBytes = oracleScript().scriptRefBytes().length;
        BigInteger floor = refScriptFee(registryBytes + oracleBytes);
        BigInteger oracleShare = refScriptFee(registryBytes + oracleBytes).subtract(refScriptFee(registryBytes));
        BigInteger delta = priced.getBody().getFee().subtract(unpriced.getBody().getFee());
        log.info("FAB-134 B3b-3 convert ref-script fee: priced {} unpriced {} delta {} | registry {} B + oracle {} B "
                        + "= {} B at {}/B, Conway tiers = floor {} (oracle share {})",
                priced.getBody().getFee(), unpriced.getBody().getFee(), delta, registryBytes, oracleBytes,
                registryBytes + oracleBytes, perByte(), floor, oracleShare);

        BigInteger tolerance = tolerance();
        assertTrue(tolerance.compareTo(oracleShare) < 0, "the tolerance would hide the oracle's fee");
        assertTrue(delta.compareTo(floor.subtract(tolerance)) >= 0,
                "the reference-script fee charged (" + delta + ") is below the Conway floor for all seven "
                        + "referenced scripts (" + floor + ", the oracle's share " + oracleShare
                        + "): a referenced script went unpriced, which the ledger rejects as FeeTooSmallUTxO");
    }

    /**
     * ⛔ F1, isolated: the ORACLE's share. The same production build against a supplier that serves every
     * registry script but not the oracle's must charge less by the oracle's share. A partial
     * {@code withReferenceScripts} declaration never consults the supplier, so the two fees come out
     * equal — the oracle unpriced in both.
     */
    @Test
    void theOraclesReferenceScriptIsPricedNotJustTheRegistrys() throws Exception {
        Fixture f = fixture(WALLET_UTXO, null);
        Transaction priced = buildProduction(f, new HashCheckedScriptSupplier(rigScripts()));
        ScriptSupplier everyScript = rigScripts();
        ScriptSupplier allButTheOracle = hash -> ORACLE_SCRIPT_HASH.equals(hash)
                ? Optional.empty() : everyScript.getScript(hash);
        Transaction withoutOracle = buildProduction(f, allButTheOracle);

        long registryBytes = 0;
        for (PlutusScript script : REGISTRY_SCRIPTS) {
            registryBytes += script.scriptRefBytes().length;
        }
        long oracleBytes = oracleScript().scriptRefBytes().length;
        // The oracle's MARGINAL share on top of the registry's bytes, so a tier boundary is honoured.
        BigInteger oracleShare = refScriptFee(registryBytes + oracleBytes).subtract(refScriptFee(registryBytes));
        BigInteger delta = priced.getBody().getFee().subtract(withoutOracle.getBody().getFee());
        log.info("FAB-134 B3b-3 convert oracle share: priced {} without-oracle {} delta {} | oracle {} B x {} = {}",
                priced.getBody().getFee(), withoutOracle.getBody().getFee(), delta, oracleBytes, perByte(),
                oracleShare);
        assertTrue(delta.compareTo(oracleShare.subtract(tolerance())) >= 0,
                "the oracle script's reference-script fee was not charged: the fee moved by " + delta
                        + " when the supplier stopped serving its " + oracleBytes + " bytes, against a share of "
                        + oracleShare + " (CCL trap 9 — a partial withReferenceScripts list prices only what "
                        + "it declares)");
    }

    /**
     * Pass 2 is strict: an evaluator error refuses the build, never placeholder ex-units. The refusal must
     * CARRY the evaluator's own failure somewhere in its cause chain — a bare {@code Exception.class} is
     * satisfied by any unrelated fault (a missing script supplier's NPE, a selection failure) thrown before
     * the evaluator was ever asked, which proves nothing about the strict flag.
     */
    @Test
    void anEvaluatorErrorRefusesTheBuildOnTheProductionPath() throws Exception {
        Fixture f = fixture(WALLET_UTXO, null);
        String marker = "FAB-134-6c marker";
        AtomicInteger asked = new AtomicInteger();
        TransactionEvaluator failing = (cbor, inputs) -> {
            asked.incrementAndGet();
            return Result.error("ScriptFailures: {" + marker + "}");
        };
        Exception refused = assertThrows(Exception.class,
                () -> production(f.universe, new HashCheckedScriptSupplier(rigScripts()), failing).build(f.request),
                "a failed evaluation on the production path must refuse, not ship placeholder ex-units");
        assertEquals(1, asked.get(), "the refusal must come from pass 2's evaluation, which runs exactly once; "
                + "asked " + asked.get() + " times, refused with " + refused);
        boolean carriesTheEvaluatorsFailure = false;
        for (Throwable t = refused; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(marker)) {
                carriesTheEvaluatorsFailure = true;
                break;
            }
        }
        assertTrue(carriesTheEvaluatorsFailure, "the build refused, but not because the evaluator failed — "
                + "nothing in the cause chain carries the evaluator's error, so an unrelated fault satisfied "
                + "the refusal: " + refused);
    }

    /**
     * The order in which the rig's UTxO supplier lists the bot address. {@code LoanFixtures.utxoSupplier}
     * answers in LIST order, while production's {@code IndexFirstUtxoSupplier} pages by transaction id —
     * effectively random against the wallet — and CCL's selectors take the first UTxOs that fit. The
     * shipped single-ordering sweep listed the wallet first and stayed green while, with the scripts listed
     * first, every wallet of 9-50 ADA pledged a 20 ADA published reference script as its ONLY collateral
     * (the FAB-134 B3b-3 audit addendum).
     */
    enum Ordering {
        /** The wallet utxo before the published scripts. */
        WALLET_FIRST,
        /** The published scripts before the wallet utxo. */
        SCRIPTS_FIRST,
        /** Scripts first, then the wallet split into utxos of at most 4 ADA (the first is the nominated one). */
        WALLET_SPLIT
    }

    /**
     * CCL trap 9b, ALL THREE seams: the bot address holds the six published loans-v4 reference scripts
     * (ada-only, 20 ADA each) beside a short wallet.
     * <ul>
     *   <li>the STRATEGY and the {@code preBalanceTx} SELECTOR: when the wallet cannot cover the build,
     *       ChangeOutputAdjustments tops the change up through the context's UtxoSelector, which checks no
     *       reference script;</li>
     *   <li>the COLLATERAL: with no collateral inputs named, {@code buildCollateralOutput} selects its own
     *       with an unguarded default strategy and a 5 ADA target — blind to both guards above.</li>
     * </ul>
     * For every wallet value, under every {@link Ordering}, the build either refuses or neither spends nor
     * pledges a published script.
     */
    @Test
    void aShortWalletNeverSpendsAPublishedReferenceScriptHeldAtTheBotAddress() throws Exception {
        long[] walletLovelace = {2_000_000L, 3_000_000L, 3_500_000L, 4_000_000L, 4_500_000L, 6_000_000L,
                8_000_000L, 9_000_000L, 10_000_000L, 11_000_000L, 12_000_000L, 15_000_000L, 20_000_000L,
                30_000_000L, 50_000_000L};
        for (Ordering ordering : Ordering.values()) {
            int built = 0;
            List<String> outcomes = new ArrayList<>();
            for (long lovelace : walletLovelace) {
                Fixture f = ordered(lovelace, ordering);
                Set<TransactionInput> published = new HashSet<>();
                f.universe.stream().filter(u -> u.getReferenceScriptHash() != null && BOT_ADDRESS.equals(u.getAddress()))
                        .forEach(u -> published.add(new TransactionInput(u.getTxHash(), u.getOutputIndex())));
                assertEquals(6, published.size(), "the six loans-v4 reference scripts sit at the bot address");

                Transaction transaction;
                try {
                    transaction = production(f.universe, new HashCheckedScriptSupplier(rigScripts()),
                            new Recording(aiken(f.universe))).build(f.request);
                } catch (RuntimeException refused) {
                    outcomes.add(lovelace + ": refused " + refused.getClass().getSimpleName());
                    continue;
                }
                Transaction reread = Transaction.deserialize(transaction.serialize());
                for (TransactionInput input : reread.getBody().getInputs()) {
                    assertFalse(published.contains(input), ordering + ", wallet " + lovelace + " lovelace: the "
                            + "build SPENDS the published reference-script utxo " + input.getTransactionId() + "#"
                            + input.getIndex() + " (CCL trap 9b — the selector seam is unguarded)");
                }
                List<TransactionInput> collateral = reread.getBody().getCollateral() == null
                        ? List.of() : reread.getBody().getCollateral();
                for (TransactionInput input : collateral) {
                    assertFalse(published.contains(input), ordering + ", wallet " + lovelace + " lovelace: the "
                            + "build pledges the published reference-script utxo " + input.getTransactionId() + "#"
                            + input.getIndex() + " as COLLATERAL, which a phase-2 failure consumes (CCL trap 9b — "
                            + "the collateral seam is unguarded)");
                }
                built++;
                outcomes.add(lovelace + ": built, " + reread.getBody().getInputs().size() + " inputs, "
                        + collateral.size() + " collateral");
            }
            log.info("FAB-134 B3b-3 convert short-wallet sweep {}: {}", ordering, outcomes);
            assertTrue(built > 0, ordering + ": no wallet value built at all, so the sweep proves nothing: "
                    + outcomes);
        }
    }

    /**
     * A fixture whose bot address holds the published scripts and a wallet of {@code walletLovelace},
     * listed in {@code ordering}. {@code LoanFixtures} is untouched: the universe is reordered here, and
     * every non-wallet, non-script utxo keeps its place.
     */
    private static Fixture ordered(long walletLovelace, Ordering ordering) throws Exception {
        List<Utxo> wallet = new ArrayList<>();
        if (ordering == Ordering.WALLET_SPLIT) {
            long remaining = walletLovelace;
            for (int i = 0; remaining > 0; i++) {
                long chunk = Math.min(4_000_000L, remaining);
                wallet.add(LoanFixtures.adaUtxo("9e".repeat(32), i, BOT_ADDRESS, chunk));
                remaining -= chunk;
            }
        } else {
            wallet.add(LoanFixtures.adaUtxo("e1".repeat(32), 0, BOT_ADDRESS, walletLovelace));
        }
        Fixture f = fixture(wallet.get(0), BOT_ADDRESS);
        Utxo nominated = f.request.walletUtxo();
        List<Utxo> scripts = f.universe.stream().filter(u -> u.getReferenceScriptHash() != null).toList();
        List<Utxo> universe = new ArrayList<>(f.universe.stream()
                .filter(u -> u.getReferenceScriptHash() == null && u != nominated).toList());
        if (ordering == Ordering.WALLET_FIRST) {
            universe.addAll(wallet);
            universe.addAll(scripts);
        } else {
            universe.addAll(scripts);
            universe.addAll(wallet);
        }
        return new Fixture(universe, f.request);
    }

    /**
     * The production constructor has no null-script-supplier form: a null there would silently select the
     * OFFLINE path (the three-argument QuickTxBuilder, a declared reference-script list and a no-op
     * supplier), whose fee prices only the registry's scripts — never the oracle's (CCL trap 9).
     */
    @Test
    void theProductionConstructorRefusesANullScriptSupplier() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> production(List.of(), null, new Recording(aiken(List.of()))));
        assertEquals("scriptSupplier", npe.getMessage(), "the refusal must name the missing supplier");
    }

    /** The production constructor has no null-evaluator form (CCL trap 8). */
    @Test
    void theProductionConstructorRefusesANullEvaluator() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> production(List.of(), new HashCheckedScriptSupplier(rigScripts()), null));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("trap 8"),
                "the refusal must say why: " + npe.getMessage());
    }

    // ======================================================================================
    // The rig's leaves
    // ======================================================================================

    /** The production constructor, exactly the one YaciConfig calls. */
    private static ConvertTransactionBuilder production(List<Utxo> universe, ScriptSupplier scripts,
                                                        TransactionEvaluator evaluator) {
        return new ConvertTransactionBuilder(REGISTRY, NETWORK, LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(), scripts, evaluator);
    }

    private static Transaction buildProduction(Fixture f, ScriptSupplier scripts) throws Exception {
        return Transaction.deserialize(production(f.universe, scripts, new Recording(aiken(f.universe)))
                .build(f.request).serialize());
    }

    private static BigDecimal perByte() {
        return EvalFixtures.protocolParams().getProtocolParams().getMinFeeRefScriptCostPerByte();
    }

    /**
     * The Conway reference-script fee for {@code bytes}: {@code minFeeRefScriptCostPerByte} for the first
     * 25,600 bytes, then x1.2 per further 25,600-byte tier (the ledger's {@code tierRefScriptFee}, floored).
     * Today's convert references 24,800 bytes, inside the first tier, so this equals the flat 15/B — but a
     * flat rate would UNDER-state the floor the moment the referenced set crossed a tier.
     */
    private static BigInteger refScriptFee(long bytes) {
        final long tier = 25_600L;
        BigDecimal price = perByte();
        BigDecimal fee = BigDecimal.ZERO;
        long remaining = bytes;
        while (remaining >= tier) {
            fee = fee.add(price.multiply(BigDecimal.valueOf(tier)));
            price = price.multiply(new BigDecimal("1.2"));
            remaining -= tier;
        }
        fee = fee.add(price.multiply(BigDecimal.valueOf(remaining)));
        return fee.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
    }

    /**
     * The compared bodies differ ONLY in the fee field and the change output's coin, and the size fee
     * moves by minFeeA (44) per byte. Both sit in the same CBOR width classes, so the honest size
     * difference is 0 bytes; one byte each for the fee and the change coin, 2 x 44 = 88 lovelace, is
     * allowed for a width-class boundary. It must stay below the oracle's share.
     */
    private static BigInteger tolerance() {
        return BigInteger.valueOf(2 * 44);
    }

    /** The provider leaf: the registry's applied scripts plus the deployed oracle, served by hash. */
    private static ScriptSupplier rigScripts() {
        return EvalFixtures.scriptSupplier(REGISTRY, List.of(oracleScript()));
    }

    private static AikenTransactionEvaluator aiken(List<Utxo> universe) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                rigScripts(), SlotConfigs.mainnet());
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

    private record Fixture(List<Utxo> universe, ConvertTransactionBuilder.Request request) {
    }

    /**
     * @param referenceScriptAddress where the six loans-v4 reference-script UTxOs sit; {@code null} puts
     *                               each at its own script's enterprise address
     */
    private static Fixture fixture(Utxo wallet, String referenceScriptAddress) throws Exception {
        String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex");
        String bondDatumHex = LoanFixtures.fixture("mainnet-lender-bond-datum-d832b78e.hex");
        String poolDatumHex = LoanFixtures.fixture("mainnet-minswap-pool-ada-fldt.hex");
        LoanDatum loan = new LoanDatumConverter().deserialize(loanDatumHex);
        LenderManagerDatum bond = new LenderManagerDatumConverter().deserialize(bondDatumHex);
        MinswapPoolDatum pool = new MinswapPoolDatumConverter().deserialize(poolDatumHex);

        // The oracle entry the loan's DATUM names, through production's registry reader.
        OracleEntry entry = OracleClients.mainnetTwoVersions()
                .findEntryByOracleToken(loan.collateral().oracleTokenAsset())
                .filter(e -> FLDT.equals(e.token()))
                .orElseThrow(() -> new AssertionError("no FLDT oracle entry for the NFT the loan names"));
        assertEquals(ORACLE_SCRIPT_HASH, entry.withdrawCredentialHash(), "the FLDT oracle's script moved");
        assertTrue(entry.usableForLiquidation(), "the recorded FLDT entry carries too few signatures");
        OraclePriceFeed feed = entry.feed();

        // Pinned inside the recorded feed's own window: whole seconds, as mainnet slots are.
        long validFromMillis = (feed.validFrom() / 1000L + 1L) * 1000L;
        long validToMillis = validFromMillis + 120_000L;
        assertTrue(validToMillis < feed.validTo(), "the recorded feed window is narrower than the build's");
        CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);
        long validFromSlot = converters.time().toSlot(
                LocalDateTime.ofEpochSecond(validFromMillis / 1000L, 0, ZoneOffset.UTC));
        long validToSlot = converters.time().toSlot(
                LocalDateTime.ofEpochSecond(validToMillis / 1000L, 0, ZoneOffset.UTC));

        BigInteger collateralAmount = BigInteger.valueOf(COLLATERAL_AMOUNT);
        BigInteger remainingDebt = LoanFinance.remainingDebt(loan, validFromMillis);
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.liquidationMode();
        boolean claimable = LoanFinance.isRepaymentLate(loan, validFromMillis) || LoanFinance.canLiquidate(
                Rational.fromInt(remainingDebt), Rational.fromInt(collateralAmount),
                LoanFinance.liquidationLtv(liquidation), OraclePriceFeed.unit(), feed);
        assertTrue(claimable, "the fixture loan is not claimable at the recorded feed: loan_claim_action "
                + "would refuse it by design and nothing here could evaluate");
        BigInteger equity = LoanFinance.redeemerEquity(liquidation, Rational.fromInt(collateralAmount),
                Rational.fromInt(remainingDebt), OraclePriceFeed.unit(), feed);

        AssetType lenderBond = new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID);
        ConvertOrderPlan plan = ConvertOrderPlan.plan(FLDT, AssetType.ada(), collateralAmount, equity,
                remainingDebt, bond.liquidationFeePerMille().longValueExact(),
                bond.shouldLiquidationConvertToPrincipal(), liquidation.equityInPrincipalCurrency(),
                pool, MS_POOL_POLICY, lenderBond, bond.lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(REGISTRY.getAssetManagerSpendScriptHash()),
                LOAN_TX, LOAN_IX);
        ClaimData claim = new ClaimData(liquidation, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                bond.lenderAuth(), equity, LOAN_ID, remainingDebt);

        // ---- the UTxO set -------------------------------------------------------------------------
        Utxo loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_IX, entAddress(REGISTRY.getLoanSpendScriptHash()),
                List.of(Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                        Amount.asset(FLDT.toUnit(), collateralAmount),
                        Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), loanDatumHex);
        Utxo bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_IX, entAddress(REGISTRY.getLenderManagerSpendScriptHash()),
                List.of(Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), bondDatumHex);
        Utxo configUtxo = LoanFixtures.utxo(CONFIG_REF.getTransactionId(), CONFIG_REF.getIndex(),
                entAddress(CONFIG_POLICY_ID), List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-config-datum-2026-10-01.hex"));
        Utxo lmConfigUtxo = LoanFixtures.utxo(LM_CONFIG_REF.getTransactionId(), LM_CONFIG_REF.getIndex(),
                entAddress(LM_CONFIG_POLICY_ID), List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-lm-config-datum-2026-10-01.hex"));
        // ⚠ The pool's VALUE is reconstructed from its recorded datum's reserves plus the pool NFT.
        Utxo poolUtxo = LoanFixtures.utxo(POOL_REF.getTransactionId(), POOL_REF.getIndex(), POOL_ADDRESS,
                List.of(Amount.lovelace(pool.reserveA()), Amount.asset(FLDT.toUnit(), pool.reserveB()),
                        Amount.asset(MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, BigInteger.ONE)),
                poolDatumHex);

        List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo, poolUtxo,
                wallet));
        // The oracle's NFT holder and its published script, at their recorded coordinates; values
        // reconstructed. A Blockfrost Utxo carries the reference script's HASH, never its bytes.
        universe.add(LoanFixtures.utxo(entry.referenceInput().getTransactionId(), entry.referenceInput().getIndex(),
                entAddress(ORACLE_SCRIPT_HASH), List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(entry.oracleToken().toUnit(), BigInteger.ONE)), null));
        universe.add(Utxo.builder().txHash(entry.referenceScript().getTransactionId())
                .outputIndex(entry.referenceScript().getIndex()).address(entAddress(ORACLE_SCRIPT_HASH))
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(ORACLE_SCRIPT_HASH).build());
        // The six loans-v4 reference scripts, at synthetic coordinates carrying the derived hashes.
        Map<String, TransactionInput> referenceScripts = new LinkedHashMap<>();
        for (int i = 0; i < REGISTRY_SCRIPTS.size(); i++) {
            String hash = hashOf(REGISTRY_SCRIPTS.get(i));
            TransactionInput at = new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
            referenceScripts.put(hash, at);
            universe.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(referenceScriptAddress != null ? referenceScriptAddress : entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }

        String orderAddress = ConvertLiquidationRouter.minswapOrderAddress(MS_ORDER_SPEND,
                bond.lenderStakeCredential(), LOAN_ID, NETWORK);
        var request = new ConvertTransactionBuilder.Request(loanUtxo, bondUtxo, poolUtxo, entry, configUtxo,
                lmConfigUtxo, wallet, referenceScripts, plan, claim, FLDT, lenderBond, loan.repaymentReceipts(),
                orderAddress, BOT_ADDRESS, validFromSlot, validToSlot);
        return new Fixture(universe, request);
    }

    private static long indexOfDatum(List<TransactionOutput> outs, String datumHex) {
        for (int i = 0; i < outs.size(); i++) {
            if (outs.get(i).getInlineDatum() != null
                    && datumHex.equalsIgnoreCase(outs.get(i).getInlineDatum().serializeToHex())) {
                return i;
            }
        }
        throw new AssertionError("no output carries the datum " + datumHex);
    }

    private static String entAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), NETWORK)
                .getAddress();
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
                    LoanFixtures.fixture("mainnet-oracle-script-d81a8bea.hex"), PlutusVersion.v3);
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
