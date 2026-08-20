package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof for the shadow liquidation loop: a real {@link LiquidateTransactionBuilder}, real
 * {@link LoanFinance} arithmetic and real fixtures, with only the collaborators that would otherwise
 * need a database, a chain or a clock replaced by hand-built subclasses — the
 * {@code Fake…Service extends …Service { super(null, ..) }} idiom
 * {@code LiquidationCandidateScannerTest} established.
 * <p>
 * No Spring context and no Spring Boot test harness: every wire is visible in {@link #wiring}.
 *
 * <h2>The claim these tests exist to support</h2>
 * The loop can decide a liquidation is worth doing and still not do it. Every wiring here is a
 * <em>shadow</em> configuration on a preview node with real protocol parameters, so the mode is the
 * only thing standing between these cycles and the wire — and the submitter they are given throws
 * an {@link AssertionError} on contact.
 * {@link #aBuiltTransactionCarriesNoSignatureAndTheOutcomeIsOnlyEverWouldSubmit()} is the falsifiable
 * form of that: it takes the transaction the loop actually produced, parses it back out of the
 * recorded CBOR, and shows the witness set holds zero vkey witnesses.
 * <p>
 * The armed half — the seven submit vetoes and the submission itself — lives in
 * {@link LiquidationSubmitVetoTest}.
 */
class LiquidationExecutorTest {

    /** A fixed instant well inside preview's history, so slot conversion is deterministic. */
    private static final long NOW = 1_700_000_000_000L;

    /** 30 days before {@link #NOW}: past every deadline the fixture datum sets, so the loan is late. */
    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    /** What {@link LiquidationExecutor} back-dates {@code validFrom} by. */
    private static final long VALID_FROM = NOW - 30_000L;

    private static final String LOAN_ID = "a1b2c3d4e5f6a1b2";
    private static final String STAKE_KEY = "33333333333333333333333333333333333333333333333333333333";

    private static final String TX_LOAN = "aa".repeat(32);
    private static final String TX_BOND = "dd".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);
    private static final String TX_WALLET_DIRTY = "e1".repeat(32);
    private static final String TX_WALLET_HASHED = "e2".repeat(32);
    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);

    /** 100 ADA of collateral against 110 ADA of debt: under water, so the equity is exactly zero. */
    private static final long COLLATERAL_LOVELACE = 100_000_000L;

    /** 500 per mille of 100 ADA — a 50 ADA fee slice, comfortably above any plausible tx fee. */
    private static final BigInteger FAT_FEE_PER_MILLE = BigInteger.valueOf(500);

    private static final BigInteger SMALL_MARGIN = BigInteger.valueOf(1_500_000);

    /** Larger than the whole 50 ADA fee slice, so the same candidate turns unprofitable. */
    private static final BigInteger HUGE_MARGIN = BigInteger.valueOf(100_000_000);

    /**
     * The bot's own account. Generated rather than pinned because nothing here ever uses a key: the
     * loop reads {@code baseAddress()} for the change and fee address and never signs, which is the
     * whole point of the slice.
     */
    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);

    /**
     * The real preview config reference inputs, datum and config NFT included — the same fixtures
     * {@code LiquidateDryEvalTest} uses. They are only ever read as reference inputs, so nothing about
     * the transaction's own bytes depends on them; what does depend on them is whether the deployed
     * validators can be run against the result at all, which
     * {@link #honestExUnitsCostMoreThanPlaceholdersAndStillFitInsideHalfTheLiveBudget()} needs.
     */
    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            ACCOUNT.baseAddress(), 200_000_000L);

    /**
     * Ada only and script free, but carrying an inline datum — a DEX order refund or an airdrop
     * claim, entirely routine in a bot wallet. The builder refuses it
     * ({@code WALLET_UTXO_NOT_ADA_ONLY}), so selecting it would refuse every candidate of every
     * cycle for as long as it sat there.
     */
    private static final Utxo WALLET_UTXO_WITH_DATUM = LoanFixtures.utxo(TX_WALLET_DIRTY, 0,
            ACCOUNT.baseAddress(), List.of(Amount.lovelace(BigInteger.valueOf(200_000_000))),
            // any well-formed datum; the builder rejects on its presence, not its content
            LiquidateTransactionBuilder.GENERAL_SPEND_REDEEMER.serializeToHex());

    /** The same hazard in its hashed-datum form. */
    private static final Utxo WALLET_UTXO_WITH_DATUM_HASH = dataHashed(
            LoanFixtures.adaUtxo(TX_WALLET_HASHED, 0, ACCOUNT.baseAddress(), 200_000_000L));

    private static Utxo dataHashed(Utxo utxo) {
        utxo.setDataHash("ab".repeat(32));
        return utxo;
    }

    // Token collateral leg, priced by a Charli3 feed.
    private static final AssetType COLLATERAL_TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType ORACLE_TOKEN = new AssetType("b0".repeat(28), "4f52434c");
    private static final String ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String TX_ORACLE_NFT = "9a".repeat(32);
    private static final String TX_ORACLE_SCRIPT = "9b".repeat(32);
    private static final String TX_CHARLI3_PROVIDER = "9c".repeat(32);

    // ======================================================================================
    // collaborators
    // ======================================================================================

    private static final class FakeScanner extends LiquidationCandidateScanner {

        private final List<LiquidationAssessment> assessments;

        private int scans;

        FakeScanner(List<LiquidationAssessment> assessments) {
            super(null, null, null);
            this.assessments = assessments;
        }

        @Override
        public List<LiquidationAssessment> scan(long atTimeMillis) {
            scans++;
            return assessments;
        }
    }

    /** Answers from a fixed set of "still unspent" UTxOs, keyed by {@code txHash#index}. */
    private static final class FakeResolver extends LiquidationUtxoResolver {

        private final Map<String, Utxo> unspent;

        private int loanResolutions;

        FakeResolver(Map<String, Utxo> unspent) {
            super(null, null, null);
            this.unspent = unspent;
        }

        @Override
        public Optional<Utxo> resolveLoanUtxo(Loan loan) {
            loanResolutions++;
            return Optional.ofNullable(unspent.get(loan.utxoRef()));
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.ofNullable(unspent.get(bond.utxoRef()));
        }

        @Override
        public Optional<Utxo> resolveConfigUtxo() {
            return Optional.of(CONFIG_UTXO);
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(LM_CONFIG_UTXO);
        }
    }

    private static final class FakeAppUtxoService extends AppUtxoService {

        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
        }
    }

    /** A registry whose contents are fixed, so a test decides exactly what the loop can see. */
    private static final class FakeOracleClient extends FluidOracleClient {

        private final List<OracleEntry> entries;

        FakeOracleClient(List<OracleEntry> entries) {
            super("http://unused.invalid");
            this.entries = entries;
        }

        @Override
        public Collection<OracleEntry> entries() {
            return entries;
        }
    }

    /**
     * Counts how often the loop asks for the oracle registry, and hands out a <em>different</em>
     * client from the second ask onwards.
     * <p>
     * That asymmetry is the whole instrument: the invariant under test is that a cycle takes one
     * snapshot and builds against it, so a second consultation is not merely wasteful, it would
     * return a registry the scan never saw. Making the second answer differ turns "asked twice" from
     * something only a counter could notice into something the decision itself reports.
     */
    private static final class CountingOracleProvider implements ObjectProvider<FluidOracleClient> {

        private final FluidOracleClient first;

        private final FluidOracleClient later;

        private int calls;

        CountingOracleProvider(FluidOracleClient first, FluidOracleClient later) {
            this.first = first;
            this.later = later;
        }

        @Override
        public FluidOracleClient getObject() {
            return getIfAvailable();
        }

        @Override
        public FluidOracleClient getObject(Object... args) {
            return getIfAvailable();
        }

        @Override
        public FluidOracleClient getIfAvailable() {
            calls++;
            return calls == 1 ? first : later;
        }

        @Override
        public FluidOracleClient getIfUnique() {
            return getIfAvailable();
        }
    }

    /** No oracle at all: the ada/ada fixtures consult none. */
    private static CountingOracleProvider noOracle() {
        return new CountingOracleProvider(null, null);
    }

    // ======================================================================================
    // fixtures
    // ======================================================================================

    /** One ada/ada loan, its bond, and the assessment the scanner would have produced for it. */
    private record Scenario(LoanFixtures.LoanUtxo loan,
                            LoanFixtures.BondUtxo bond,
                            LiquidationAssessment assessment) {

        Scenario withAssessment(LiquidationAssessment replacement) {
            return new Scenario(loan, bond, replacement);
        }
    }

    private static Scenario scenario(BigInteger feePerMille) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(feePerMille, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        AssetType.ada()),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    /**
     * A token-collateral loan priced by a Charli3 feed, and the oracle entry that prices it.
     * <pre>
     *   principal 50 ADA at 10%                    -> remainingDebt   55_000_000 lovelace
     *   collateral 1_000_000 TOK at 50 lovelace    -> 50_000_000 lovelace, i.e. under water
     *   -> equity 0, which is what the builder's V8 veto requires
     *   liquidationFeePerMille 500                 -> fee 500_000 TOK = 25_000_000 lovelace
     * </pre>
     * The fee slice is therefore <em>not</em> its own lovelace value: it only becomes one by going
     * through the collateral feed, which is what makes this fixture the one that can tell whether the
     * loop priced with the snapshot it scanned with.
     */
    private static Scenario tokenScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), collateralFeed(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    /** A c3 window wide enough to cover the tx interval plus the configured 30 s margin. */
    private static OraclePriceFeed collateralFeed() {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50),
                BigInteger.ONE, NOW - 60_000L, NOW + 600_000L);
    }

    private static OracleEntry collateralOracle() {
        return LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, ORACLE_CREDENTIAL, collateralFeed(),
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_CHARLI3_PROVIDER, 0));
    }

    private static AppConfig.LiquidationConfiguration config(
            AppConfig.LiquidationConfiguration.Mode mode, BigInteger margin, int decisionLogSize) {
        return new AppConfig.LiquidationConfiguration(mode, false, 60, 120, 30, margin,
                decisionLogSize, 30);
    }

    private static AppConfig.LiquidationConfiguration shadow(BigInteger margin) {
        return config(AppConfig.LiquidationConfiguration.Mode.SHADOW, margin, 200);
    }

    // ======================================================================================
    // wiring
    // ======================================================================================

    private record Wiring(LiquidationExecutor executor,
                          LiquidationDecisionLog log,
                          FakeScanner scanner,
                          FakeResolver resolver,
                          CountingOracleProvider oracles) {
    }

    /**
     * The whole loop, wired by hand.
     *
     * @param scanned      what the scanner returns this cycle — buildable and excluded alike
     * @param inUniverse   every loan/bond fixture the builder's {@code UtxoSupplier} can see
     * @param stillUnspent exactly what the resolver reports as still unspent, keyed by
     *                     {@code txHash#index}; the difference between this and {@code inUniverse}
     *                     is what "spent since the scan" means, and it is stated per UTxO rather
     *                     than per loan so a bond can be spent while its loan is not
     * @param walletUtxos  what {@code AppUtxoService.listWalletUtxo()} returns, in order
     * @param syncing      drives the {@link BlockEventListener} guard
     */
    private static Wiring wiring(AppConfig.LiquidationConfiguration configuration,
                                 List<LiquidationAssessment> scanned,
                                 List<Scenario> inUniverse,
                                 Map<String, Utxo> stillUnspent,
                                 List<Utxo> walletUtxos,
                                 CountingOracleProvider oracles,
                                 boolean syncing) {
        return wiring(configuration, scanned, inUniverse, stillUnspent, walletUtxos, oracles, syncing,
                false);
    }

    /**
     * @param honestExUnits when true the builder is given a real PlutusV3 script-cost evaluator, so the
     *                      redeemers carry measured ex-units instead of cardano-client-lib's
     *                      placeholders — which is what the production wiring does and what the
     *                      transaction fee is therefore really made of
     */
    private static Wiring wiring(AppConfig.LiquidationConfiguration configuration,
                                 List<LiquidationAssessment> scanned,
                                 List<Scenario> inUniverse,
                                 Map<String, Utxo> stillUnspent,
                                 List<Utxo> walletUtxos,
                                 CountingOracleProvider oracles,
                                 boolean syncing,
                                 boolean honestExUnits) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO));
        universe.addAll(walletUtxos);
        for (Scenario scenario : inUniverse) {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
        }
        Map<String, Utxo> unspent = new LinkedHashMap<>(stillUnspent);

        LiquidateTransactionBuilder builder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), honestExUnits ? realExUnitsEvaluator(universe) : null);

        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(syncing);

        FakeScanner scanner = new FakeScanner(scanned);
        FakeResolver resolver = new FakeResolver(unspent);
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);

        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT, scanner, resolver, builder,
                payInAdvanceRouter, LoanFixtures.registry(), log, oracles,
                previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                EXPLODING_SUBMITTER);
        return new Wiring(executor, log, scanner, resolver, oracles);
    }

    /**
     * The node is on preview and the protocol parameters are real, so <em>nothing</em> about this
     * wiring is what stops these cycles submitting — only the shadow mode is. Every test in this
     * class therefore runs against a submitter that fails the test on contact.
     */
    private static AppConfig.Network previewNetwork() {
        return new AppConfig.Network() {
            @Override
            public String getNetwork() {
                return "preview";
            }
        };
    }

    /**
     * The falsifiable form of "shadow mode cannot submit". Every wiring in this class gets this
     * submitter; if any code path on the shadow tail ever reached the wire, the test that reached it
     * would fail here rather than pass quietly.
     */
    private static final LiquidationExecutor.TransactionSubmitter EXPLODING_SUBMITTER = bytes -> {
        throw new AssertionError("a shadow-mode cycle submitted " + bytes.length + " bytes");
    };

    /** Both UTxOs of each scenario, as the resolver would report them when nothing has moved. */
    private static Map<String, Utxo> allUnspent(List<Scenario> scenarios) {
        Map<String, Utxo> unspent = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
            unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());
        }
        return unspent;
    }

    /** The common case: one candidate, scanned and still on chain, on a clean ada-only wallet. */
    private static Wiring wiring(AppConfig.LiquidationConfiguration configuration, Scenario scenario,
                                 boolean syncing) {
        return wiring(configuration, List.of(scenario.assessment()), List.of(scenario),
                allUnspent(List.of(scenario)), List.of(WALLET_UTXO), noOracle(), syncing);
    }

    /** As above with the scanner's verdicts supplied whole, for the exclusion histogram. */
    private static Wiring wiring(AppConfig.LiquidationConfiguration configuration,
                                 List<LiquidationAssessment> scanned, List<Scenario> onChain,
                                 boolean syncing) {
        return wiring(configuration, scanned, onChain, allUnspent(onChain), List.of(WALLET_UTXO),
                noOracle(), syncing);
    }

    /**
     * A real script-cost evaluator, offline: the same UPLC machine and the same applied {@code loans-v4}
     * scripts {@link LiquidateDryEvalTest} runs against, wired where production wires Blockfrost's
     * {@code /utils/txs/evaluate}. Blockfrost resolves the transaction's inputs itself because on chain
     * they are real UTxOs; here the synthetic universe is handed over explicitly instead, which is the
     * only difference and the reason the adapter exists.
     * <p>
     * Narrowed to {@link TransactionEvaluator} at this point, exactly as the production wiring is: what
     * the builder ends up holding can price a transaction and has no way to transmit one.
     */
    private static TransactionEvaluator realExUnitsEvaluator(List<Utxo> universe) {
        AikenTransactionEvaluator aiken = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(LoanFixtures.registry()), SlotConfigs.preview());
        Set<Utxo> resolvable = new LinkedHashSet<>(universe);
        return (cbor, inputUtxos) -> aiken.evaluateTx(cbor, resolvable);
    }

    /** One candidate, still on chain, with the wallet contents chosen by the test. */
    private static Wiring wiringWithWallet(AppConfig.LiquidationConfiguration configuration,
                                           Scenario scenario, List<Utxo> walletUtxos) {
        return wiring(configuration, List.of(scenario.assessment()), List.of(scenario),
                allUnspent(List.of(scenario)), walletUtxos, noOracle(), false);
    }

    // ======================================================================================
    // the two gates
    // ======================================================================================

    @Test
    void disabledModeDoesNotEvenScan() {
        Wiring wiring = wiring(config(AppConfig.LiquidationConfiguration.Mode.DISABLED, SMALL_MARGIN, 200),
                scenario(FAT_FEE_PER_MILLE), false);

        wiring.executor().cycle(NOW);

        assertEquals(0, wiring.scanner().scans, "DISABLED must return before the scanner is touched");
        assertEquals(0, wiring.log().size());
        assertNull(wiring.log().lastRun().at(), "no run happened, so there is no run to summarise");
    }

    /**
     * A syncing node's index is behind the chain, so every "still unspent" answer it gives is a
     * guess. The guard therefore has to fire before the <em>scan</em>, not merely before the build:
     * a scan of a stale index is already a wrong answer, and it is the one the endpoint would then
     * report as this cycle's truth.
     */
    @Test
    void aSyncingNodeShortCircuitsBeforeTheScan() {
        Wiring wiring = wiring(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE), true);

        wiring.executor().cycle(NOW);

        assertEquals(0, wiring.scanner().scans);
        assertEquals(0, wiring.log().size());
        assertNull(wiring.log().lastRun().at());
    }

    // ======================================================================================
    // the profit decision
    // ======================================================================================

    @Test
    void aProfitableCandidateIsRecordedAsWouldSubmitWithTheBuiltTransaction() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "the fixture must have zero equity, or the builder's V8 veto refuses it outright");
        assertEquals(BigInteger.valueOf(50_000_000), scenario.assessment().liquidationFee());

        Wiring wiring = wiring(shadow(SMALL_MARGIN), scenario, false);
        wiring.executor().cycle(NOW);

        assertEquals(1, wiring.scanner().scans);
        assertEquals(NOW, wiring.log().lastRun().at());
        assertEquals(1, wiring.log().lastRun().bondsScanned());
        assertEquals(1, wiring.log().lastRun().buildable());

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT, decision.variant());
        assertEquals(LOAN_ID, decision.loanId());
        assertEquals(TX_LOAN + "#0", decision.loanUtxoRef());
        assertEquals(TX_BOND + "#0", decision.bondUtxoRef());
        assertEquals(AssetType.LOVELACE, decision.collateralUnit());

        assertNotNull(decision.txHash());
        assertNotNull(decision.txSizeBytes());
        assertNotNull(decision.txCborHex());
        assertTrue(decision.inputs() >= 3, "the loan, its bond and the bot's ada");
        assertTrue(decision.referenceInputs() >= 2, "both config utxos are read");
        assertTrue(decision.redeemers() > 0);

        // The arithmetic, re-derived rather than read back off the decision it produced.
        assertEquals(BigInteger.valueOf(50_000_000), decision.expectedFeeLovelace(),
                "ada collateral prices 1:1, so the fee slice is already its own lovelace value");
        assertEquals(SMALL_MARGIN, decision.marginLovelace());
        assertEquals(decision.expectedFeeLovelace()
                        .subtract(decision.txFeeLovelace())
                        .subtract(decision.marginLovelace()),
                decision.expectedProfitLovelace());
        assertTrue(decision.expectedProfitLovelace().signum() > 0);
    }

    /**
     * The same candidate and the same transaction, with a margin larger than the whole fee slice.
     * Nothing about the loan changed — only the operator's idea of what is worth doing.
     */
    @Test
    void aMarginAboveTheFeeSliceTurnsTheSameCandidateUnprofitable() {
        Wiring wiring = wiring(shadow(HUGE_MARGIN), scenario(FAT_FEE_PER_MILLE), false);

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertEquals(HUGE_MARGIN, decision.marginLovelace());
        assertTrue(decision.expectedProfitLovelace().signum() <= 0);
        // Still built: an unprofitable candidate is a priced one, not a refused one.
        assertNotNull(decision.txHash());
        assertNotNull(decision.txSizeBytes());
    }

    // ======================================================================================
    // the two failure paths
    // ======================================================================================

    /**
     * The loan UTxO was spent between the scan and the build — repaid, or liquidated by somebody
     * else. The builder must never see the candidate: the assessment's coordinates now name an
     * output that does not exist, and a UTxO nobody assessed is exactly what must not be spent.
     */
    @Test
    void aLoanUtxoThatIsNoLongerUnspentIsRecordedAsNoUtxoAndNeverReachesTheBuilder() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);

        // Scanned, but nothing of it is left on chain.
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(scenario.assessment()), List.of(), false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, decision.outcome());
        assertEquals(LiquidationDecision.Outcome.NO_UTXO.name(), decision.reason());
        assertEquals(1, wiring.resolver().loanResolutions, "the resolver was asked exactly once");
        assertNull(decision.txHash(), "nothing was built");
        assertNull(decision.txCborHex());
        assertNull(decision.txFeeLovelace());
        assertNull(decision.expectedProfitLovelace());
        // The assessment's own numbers survive into the record, so the row is still diagnosable.
        assertEquals(BigInteger.valueOf(50_000_000), decision.liquidationFee());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "a spent utxo is not a malfunction: the ref is gone for good and will not be rescanned");
    }

    /**
     * The other half of the same predicate, and the reachable one: the <b>bond</b> was spent while
     * the loan is still there — a lender withdrawing their bond, or another bot liquidating first.
     * <p>
     * Worth its own test because the two clauses fail differently. Drop {@code bondUtxo.isEmpty()}
     * and the loan clause still passes, so nothing short-circuits: {@code bondUtxo.get()} throws
     * {@code NoSuchElementException}, which lands in the generic branch and records the wrong reason
     * <em>and</em> quarantines a perfectly healthy loan for half an hour. Both halves are asserted.
     */
    @Test
    void aBondUtxoSpentWhileTheLoanRemainsIsAlsoRecordedAsNoUtxo() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        // Only the loan survives; the bond is gone.
        Map<String, Utxo> loanOnly = Map.of(scenario.loan().loan().utxoRef(), scenario.loan().utxo());

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(scenario.assessment()), List.of(scenario),
                loanOnly, List.of(WALLET_UTXO), noOracle(), false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, decision.outcome(),
                "a spent bond is a missing utxo, not an unexplained exception");
        assertEquals(LiquidationDecision.Outcome.NO_UTXO.name(), decision.reason());
        assertNull(decision.txHash());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "and it must not quarantine a loan whose only problem is that its bond moved");
    }

    /**
     * A refusal is recorded under the builder's own {@code Refusal} name, never a paraphrase — that
     * name is the only thing that lets an operator match a decision back to the check that produced
     * it.
     * <p>
     * The candidate here carries a liquidation fee one lovelace above the one the bond's per-mille
     * rate produces, which is V4's {@code LIQUIDATION_FEE_NOT_REPRODUCIBLE}. Every other number is
     * the real one, so the refusal under test is the only thing standing between this batch and a
     * built transaction: delete the check and the test fails on the missing refusal, not on a
     * NullPointerException somewhere downstream.
     */
    @Test
    void aBuilderRefusalIsRecordedUnderItsExactRefusalName() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Scenario tampered = honest.withAssessment(LoanFixtures.withNumbers(honest.assessment(),
                honest.assessment().remainingDebt(),
                honest.assessment().equity(),
                honest.assessment().liquidationFee().add(BigInteger.ONE)));

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(tampered.assessment()), List.of(tampered),
                false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome());
        assertEquals(LiquidateTransactionBuilder.Refusal.LIQUIDATION_FEE_NOT_REPRODUCIBLE.name(),
                decision.reason());
        assertNull(decision.txHash());
        assertNull(decision.txCborHex());
    }

    // ======================================================================================
    // the ring buffer
    // ======================================================================================

    /**
     * Excluded bonds are counted and nothing more. A node watching a few hundred healthy loans would
     * otherwise evict every interesting row with {@code NOT_LIQUIDATABLE} noise on every single
     * cycle — and the counts still have to reconcile, which is what {@code bonds_scanned} is for.
     */
    @Test
    void exclusionsAppearOnlyInTheHistogramNeverInTheRingBuffer() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        LiquidationAssessment healthy = LiquidationAssessment.excluded(scenario.bond().bond(),
                scenario.loan().loan(), LiquidationExclusion.NOT_LIQUIDATABLE,
                "not late and currentLtv <= liquidationLtv");
        LiquidationAssessment orphan = LiquidationAssessment.excluded(scenario.bond().bond(), null,
                LiquidationExclusion.LOAN_NOT_FOUND, "no loan shares this bond's asset name");

        Wiring wiring = wiring(shadow(SMALL_MARGIN),
                List.of(scenario.assessment(), healthy, orphan), List.of(scenario), false);
        wiring.executor().cycle(NOW);

        assertEquals(3, wiring.log().lastRun().bondsScanned(), "every scanned bond is accounted for");
        assertEquals(1, wiring.log().lastRun().buildable());
        assertEquals(Map.of(LiquidationExclusion.NOT_LIQUIDATABLE, 1,
                        LiquidationExclusion.LOAN_NOT_FOUND, 1),
                wiring.log().lastRun().exclusions());

        assertEquals(1, wiring.log().size(), "only the buildable candidate produced a decision");
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, onlyDecision(wiring).outcome());
    }

    @Test
    void theRingBufferEvictsOldestFirstAtCapacity() {
        LiquidationDecisionLog log = new LiquidationDecisionLog(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, SMALL_MARGIN, 3));

        for (int i = 0; i < 5; i++) {
            log.record(decisionNumbered(i));
        }

        assertEquals(3, log.size());
        assertEquals(List.of("4", "3", "2"),
                log.newestFirst(10).stream().map(LiquidationDecision::loanId).toList(),
                "newest first, and the two oldest were evicted");
        assertEquals(List.of("4", "3"),
                log.newestFirst(2).stream().map(LiquidationDecision::loanId).toList(),
                "a limit takes from the newest end");
    }

    // ======================================================================================
    // the falsifiable harness: no signature can be present, because none is ever produced
    // ======================================================================================

    /**
     * The consequence, not the plumbing. A full shadow cycle runs over a candidate the loop judges
     * worth submitting; the transaction it built is then parsed back out of the recorded CBOR and
     * inspected. If anything on this path ever signed — a stray signer, a sign-and-build helper, a
     * transaction processor — the witness set would carry a vkey witness and this assertion would
     * fail. It cannot be satisfied by an intention or a comment.
     * <p>
     * The size is asserted <em>and reported</em>. With {@code ReferenceScripts.none()} all six
     * validators (18_584 bytes) travel in the witness set, so the transaction is expected to blow
     * straight past the 16_384-byte {@code maxTxSize}; surfacing that number is this slice's job and
     * publishing the reference scripts that fix it is the next one. Should this ever fail because
     * the transaction came out <em>smaller</em> than the limit, the measurement the plan rests on is
     * wrong and has to be redone before anything is armed.
     */
    @Test
    void aBuiltTransactionCarriesNoSignatureAndTheOutcomeIsOnlyEverWouldSubmit() throws Exception {
        Wiring wiring = wiring(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE), false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());

        Transaction built = Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex()));
        assertNotNull(built.getWitnessSet(), "the six validators travel in the witness set");
        assertTrue(built.getWitnessSet().getVkeyWitnesses() == null
                        || built.getWitnessSet().getVkeyWitnesses().isEmpty(),
                "the shadow loop produced a SIGNED transaction — it must be incapable of that");
        assertTrue(built.getWitnessSet().getBootstrapWitnesses() == null
                        || built.getWitnessSet().getBootstrapWitnesses().isEmpty(),
                "no bootstrap witness either");

        // A SUBMITTED state now exists, so the claim has to be made about this cycle rather than
        // about the enum: the recorded outcome is not it, and the veto that stopped it is named.
        assertNotEquals(LiquidationDecision.Outcome.SUBMITTED, decision.outcome());
        assertNotEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, decision.outcome());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), decision.submitVeto(),
                "shadow mode is what stopped it — not the wallet, the network or the size");
        assertTrue(Arrays.stream(LiquidationDecision.Outcome.values())
                        .anyMatch(outcome -> outcome.name().equals("SUBMITTED")),
                "the SUBMITTED state is what slice 3 added; a workstream without it cannot arm");

        int size = decision.txSizeBytes();
        System.out.printf("OBSERVED tx_size_bytes for one ada/ada liquidation with "
                + "ReferenceScripts.none(): %d bytes (maxTxSize 16384)%n", size);
        assertTrue(size > 16_384,
                ("the transaction is %d bytes, at or under the 16_384 maxTxSize — that contradicts "
                        + "the measured 18_584 bytes of validators this plan rests on, so the "
                        + "measurement has to be redone before slice 3").formatted(size));
    }

    // ======================================================================================
    // honest ex-units: what the transaction really costs, and what that does to the verdict
    // ======================================================================================

    /**
     * The live budget, measured from preview via Blockfrost {@code /epochs/latest/parameters} on
     * 2026-08-15 (alongside {@code protocol_major_ver} 11): {@code maxTxExMem} 17_500_000 and
     * {@code maxTxExSteps} 10_000_000_000. Half of it is the headroom this workstream is willing to
     * run on.
     * <p>
     * Deliberately <em>not</em> read from {@code LoanFixtures.protocolParams()}: the 14_000_000
     * {@code maxTxExMem} pinned there and in {@code EvalFixtures} is stale, the same pinned-fixture
     * problem this ticket is about one layer up. Refreshing those fixtures is backlog, together with
     * the cost model; a guard that took its threshold from the stale pin would be measuring the pin.
     */
    private static final BigInteger BUDGET_MEM = BigInteger.valueOf(17_500_000L);
    private static final BigInteger BUDGET_STEPS = BigInteger.valueOf(10_000_000_000L);

    /**
     * The same candidate, priced twice: once with cardano-client-lib's placeholder ex-units and once
     * with ex-units measured by the real PlutusV3 machine against the deployed validators.
     *
     * <h2>Why this test exists</h2>
     * Ex-units are priced into the fee ({@code priceMem} 0.0577, {@code priceStep} 0.0000721), so
     * placeholders do not merely mis-declare the budget — they under-state the fee, and the fee is what
     * {@code expectedProfitLovelace} is computed from. A candidate can therefore be reported as
     * profitable on arithmetic that no real transaction would ever satisfy. Both numbers are printed
     * rather than only asserted, because deciding whether the margin is still the right one is an
     * operator's call and needs the figures.
     *
     * <h2>What is asserted</h2>
     * That the honest run is honest (no redeemer left at a placeholder), that pricing it costs more
     * than pretending (so the placeholder fee really was an under-statement, in the direction that
     * flatters the bot), and that the measured cost sits inside half the live budget. The last one is a
     * guard rather than a measurement: at ~13% of mem and ~8% of steps it has an order of magnitude of
     * slack, so it will not flap, but a change that doubles the cost of a liquidation stops being
     * invisible.
     */
    @Test
    void honestExUnitsCostMoreThanPlaceholdersAndStillFitInsideHalfTheLiveBudget() throws Exception {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);

        Wiring guessing = wiring(shadow(SMALL_MARGIN), scenario, false);
        guessing.executor().cycle(NOW);
        LiquidationDecision guessed = onlyDecision(guessing);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, guessed.outcome(), guessed.detail());

        Wiring measuring = wiringWithHonestExUnits(shadow(SMALL_MARGIN), scenario);
        measuring.executor().cycle(NOW);
        LiquidationDecision measured = onlyDecision(measuring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, measured.outcome(), measured.detail());

        // Every redeemer of the honest transaction carries a real costing, read off the transaction
        // rather than off the evaluator's report.
        Transaction honest = Transaction.deserialize(HexUtil.decodeHexString(measured.txCborHex()));
        BigInteger totalMem = BigInteger.ZERO;
        BigInteger totalSteps = BigInteger.ZERO;
        for (Redeemer redeemer : honest.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(BigInteger.valueOf(10_000)) > 0,
                    "redeemer " + redeemer.getTag() + "#" + redeemer.getIndex()
                            + " still carries a placeholder mem of " + redeemer.getExUnits().getMem());
            totalMem = totalMem.add(redeemer.getExUnits().getMem());
            totalSteps = totalSteps.add(redeemer.getExUnits().getSteps());
        }

        // The placeholder transaction, for contrast: 10000 mem everywhere.
        Transaction placeheld = Transaction.deserialize(HexUtil.decodeHexString(guessed.txCborHex()));
        BigInteger placeholderMem = BigInteger.ZERO;
        for (Redeemer redeemer : placeheld.getWitnessSet().getRedeemers()) {
            placeholderMem = placeholderMem.add(redeemer.getExUnits().getMem());
        }
        assertTrue(totalMem.compareTo(placeholderMem) > 0,
                "the placeholders were not an under-statement, which contradicts the whole defect");

        System.out.printf("OBSERVED honest ex-units for one ada/ada liquidation: mem=%s (%.1f%% of "
                        + "%s), steps=%s (%.1f%% of %s)%n",
                totalMem, percent(totalMem, BUDGET_MEM), BUDGET_MEM,
                totalSteps, percent(totalSteps, BUDGET_STEPS), BUDGET_STEPS);
        System.out.printf("OBSERVED placeholder vs honest: tx_fee %s -> %s lovelace, "
                        + "expected_profit %s -> %s lovelace, tx_size %d -> %d bytes%n",
                guessed.txFeeLovelace(), measured.txFeeLovelace(),
                guessed.expectedProfitLovelace(), measured.expectedProfitLovelace(),
                guessed.txSizeBytes(), measured.txSizeBytes());

        // Task 5's two numbers, asserted so they cannot drift silently past a reader.
        assertTrue(measured.txFeeLovelace().compareTo(guessed.txFeeLovelace()) > 0,
                "an honestly priced transaction costs more than a placeholder-priced one");
        assertEquals(measured.txFeeLovelace().subtract(guessed.txFeeLovelace()),
                guessed.expectedProfitLovelace().subtract(measured.expectedProfitLovelace()),
                "every lovelace the honest fee adds comes straight off the expected profit");
        assertTrue(measured.txSizeBytes() >= guessed.txSizeBytes(),
                "real ex-unit integers encode no smaller than the placeholders");

        // Task 6: the headroom guard.
        assertTrue(totalMem.multiply(BigInteger.TWO).compareTo(BUDGET_MEM) <= 0,
                "consumed mem " + totalMem + " is over half of the live maxTxExMem " + BUDGET_MEM);
        assertTrue(totalSteps.multiply(BigInteger.TWO).compareTo(BUDGET_STEPS) <= 0,
                "consumed steps " + totalSteps + " is over half of the live maxTxExSteps " + BUDGET_STEPS);
    }

    /**
     * The verdict itself, stated as a claim an operator can act on: with the default 1.5 ADA margin the
     * honest fee does <em>not</em> turn this candidate unprofitable. If it ever does, this test fails
     * with the numbers in the message, and that failure is the finding — not something to fix by
     * choosing a friendlier fixture.
     */
    @Test
    void theHonestFeeDoesNotTurnTheAdaAdaCandidateUnprofitable() {
        Wiring measuring = wiringWithHonestExUnits(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE));
        measuring.executor().cycle(NOW);

        LiquidationDecision measured = onlyDecision(measuring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, measured.outcome(),
                ("the honest fee turned a previously profitable ada/ada candidate unprofitable: "
                        + "fee=%s margin=%s profit=%s — this is a finding for T-010, not a fixture "
                        + "problem").formatted(measured.txFeeLovelace(), measured.marginLovelace(),
                        measured.expectedProfitLovelace()));
        assertTrue(measured.expectedProfitLovelace().signum() > 0, measured.detail());
    }

    private static Wiring wiringWithHonestExUnits(AppConfig.LiquidationConfiguration configuration,
                                                  Scenario scenario) {
        return wiring(configuration, List.of(scenario.assessment()), List.of(scenario),
                allUnspent(List.of(scenario)), List.of(WALLET_UTXO), noOracle(), false, true);
    }

    private static double percent(BigInteger part, BigInteger whole) {
        return new BigDecimal(part).multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(whole), 1, RoundingMode.HALF_UP).doubleValue();
    }

    // ======================================================================================
    // the wallet UTxO selector
    // ======================================================================================

    /**
     * A datum-carrying ada-only UTxO sits <b>first</b> in the wallet, which is where {@code findFirst}
     * would take it from.
     * <p>
     * The builder rejects such a UTxO outright, and a refusal is not quarantined — so selecting it
     * would refuse every candidate of every cycle, for as long as it sat in the wallet, with no
     * symptom louder than a repeated reason string. This is not a hypothetical shape: a DEX order
     * refund or an airdrop claim arrives exactly like this.
     */
    @Test
    void aDatumCarryingWalletUtxoIsSkippedInFavourOfACleanOne() throws Exception {
        Wiring wiring = wiringWithWallet(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE),
                List.of(WALLET_UTXO_WITH_DATUM, WALLET_UTXO));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(),
                "the loop must decide normally, not refuse on the wallet: " + decision.detail());

        // And the clean UTxO is the one that was actually spent.
        Transaction built = Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex()));
        List<TransactionInput> inputs = built.getBody().getInputs();
        assertTrue(inputs.contains(new TransactionInput(TX_WALLET, 0)),
                "the clean wallet utxo must be the fee input");
        assertFalse(inputs.contains(new TransactionInput(TX_WALLET_DIRTY, 0)),
                "the datum-carrying utxo must not be spent");
    }

    /** The same hazard in its hashed-datum form, which the builder rejects on the same rule. */
    @Test
    void aWalletUtxoCarryingOnlyADataHashIsAlsoSkipped() throws Exception {
        Wiring wiring = wiringWithWallet(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE),
                List.of(WALLET_UTXO_WITH_DATUM_HASH, WALLET_UTXO));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());

        Transaction built = Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex()));
        assertFalse(built.getBody().getInputs().contains(new TransactionInput(TX_WALLET_HASHED, 0)));
    }

    /**
     * A wallet with nothing usable in it. The loop stops before the scan and records nothing, rather
     * than scanning and then refusing every candidate it found — the difference between one warning
     * an operator can act on and a decision log full of misleading refusals.
     */
    @Test
    void aWalletWithNoCleanAdaUtxoStopsBeforeTheScan() {
        Wiring wiring = wiringWithWallet(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE),
                List.of(WALLET_UTXO_WITH_DATUM, WALLET_UTXO_WITH_DATUM_HASH));

        wiring.executor().cycle(NOW);

        assertEquals(0, wiring.scanner().scans);
        assertEquals(0, wiring.log().size());
        assertNull(wiring.log().lastRun().at());
    }

    // ======================================================================================
    // one oracle snapshot per cycle
    // ======================================================================================

    /**
     * The build has to price against the registry the <em>scan</em> saw, not a fresher one.
     * <p>
     * The provider here hands out the real registry once and an empty one from the second ask
     * onwards, so a loop that consulted it twice would build against a registry with no entry for
     * this loan's collateral and the candidate would come back
     * {@code REFUSED/ORACLE_ENTRY_MISSING}. That is the failure this asserts against — and it
     * matters because the builder's V4 guard demands the assessment reproduce against the feeds it
     * is handed, so a mid-cycle price change does not produce a slightly different transaction, it
     * produces a wave of unexplained refusals.
     * <p>
     * This is also the only fixture in this class where the fee slice is <em>not</em> already
     * lovelace: 500_000 TOK becomes 25_000_000 lovelace only by passing through the collateral feed,
     * so the priced number itself is evidence of which snapshot was used.
     */
    @Test
    void theBuildPricesWithTheOracleSnapshotTheScanWasTakenWith() {
        Scenario scenario = tokenScenario();
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "the fixture must have zero equity, or the builder's V8 veto refuses it outright");
        assertEquals(BigInteger.valueOf(500_000), scenario.assessment().liquidationFee(),
                "the fee is denominated in the collateral token, not in lovelace");

        CountingOracleProvider oracles = new CountingOracleProvider(
                new FakeOracleClient(List.of(collateralOracle())),
                new FakeOracleClient(List.of()));

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(scenario.assessment()),
                List.of(scenario), allUnspent(List.of(scenario)), List.of(WALLET_UTXO), oracles, false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertEquals(1, oracles.calls,
                "the registry must be consulted once per cycle and the snapshot reused");
        assertEquals(BigInteger.valueOf(25_000_000), decision.expectedFeeLovelace(),
                "500_000 TOK at 50 lovelace — priced through the feed the scan used");
        assertEquals(LoanFixtures.unit(COLLATERAL_TOKEN), decision.collateralUnit());
        assertTrue(decision.referenceInputs() >= 5,
                "two configs plus the oracle nft, its reference script and the c3 provider");
    }

    // ======================================================================================
    // quarantine
    // ======================================================================================

    /**
     * A candidate whose build throws something that is <em>not</em> a refusal — the machinery
     * failing rather than a verdict on the loan. Here the assessment claims to be buildable while
     * carrying no numbers, so the builder dereferences a null; in production the same branch catches
     * a Blockfrost timeout fetching protocol params.
     */
    private static Scenario unbuildableScenario() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        return honest.withAssessment(LiquidationAssessment.buildable(honest.bond().bond(),
                honest.loan().loan(), "buildable, but carrying none of the numbers a build needs",
                null, null, false, null));
    }

    @Test
    void aFailedBuildQuarantinesTheLoanAndTheNextCycleSkipsIt() {
        Wiring wiring = wiring(shadow(SMALL_MARGIN), unbuildableScenario(), false);

        wiring.executor().cycle(NOW);

        LiquidationDecision first = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, first.outcome());
        assertEquals("NullPointerException", first.reason(),
                "a thrown failure is recorded under the exception, not under a Refusal name");
        assertEquals(1, wiring.executor().quarantinedCount());
        assertEquals(Set.of(TX_LOAN + "#0"), wiring.executor().quarantinedRefs());

        // A minute later, well inside the 30-minute quarantine.
        wiring.executor().cycle(NOW + 60_000L);

        assertEquals(1, wiring.log().size(),
                "the quarantined loan must not be built again while its quarantine holds");
        assertEquals(1, wiring.log().lastRun().bondsScanned(),
                "it is still scanned and still counted — only the build attempt is skipped");
    }

    /**
     * The divergence from {@code ScheduledTransactionService}, which quarantines forever. One
     * transient failure must not exclude a borrower's loan for the lifetime of the process.
     */
    @Test
    void aQuarantineExpiresAndTheLoanIsReconsidered() {
        Wiring wiring = wiring(shadow(SMALL_MARGIN), unbuildableScenario(), false);

        wiring.executor().cycle(NOW);
        assertEquals(1, wiring.log().size());

        // 31 minutes later: past the configured 30.
        wiring.executor().cycle(NOW + 31L * 60_000L);

        assertEquals(2, wiring.log().size(), "the quarantine must lapse, not persist");
        assertEquals(1, wiring.executor().quarantinedCount(), "and be taken again on the new failure");
    }

    /**
     * The key is the loan <b>UTxO ref</b>, not the loan id, and the difference is not cosmetic: a
     * loan id outlives the UTxO carrying it, so keying on it would exclude a borrower across every
     * re-creation of their loan output, while a ref-keyed quarantine dies the moment the output is
     * spent by anyone.
     */
    @Test
    void theQuarantineKeyIsTheLoanUtxoRefAndNotTheLoanId() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), scenario, false);

        wiring.executor().quarantineUntil(LOAN_ID, NOW + 3_600_000L);
        wiring.executor().cycle(NOW);

        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, onlyDecision(wiring).outcome(),
                "a quarantine under the loan id must not suppress the loan's utxo");

        // The ref, by contrast, does suppress it.
        Wiring byRef = wiring(shadow(SMALL_MARGIN), scenario, false);
        byRef.executor().quarantineUntil(TX_LOAN + "#0", NOW + 3_600_000L);
        byRef.executor().cycle(NOW);

        assertEquals(0, byRef.log().size());
        assertEquals(1, byRef.log().lastRun().bondsScanned(),
                "still scanned and still counted, just not built");
    }

    /**
     * The map is bounded, so a pathological cycle — every loan failing to build — cannot grow it
     * without limit. The entry evicted is the one closest to expiry, because its exclusion was about
     * to end anyway.
     */
    @Test
    void theQuarantineIsBoundedAndEvictsTheSoonestToExpire() {
        LiquidationExecutor executor = wiring(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE), false)
                .executor();

        // Expiries strictly increasing, so "soonest to expire" is unambiguous.
        for (int i = 0; i < LiquidationExecutor.MAX_QUARANTINED; i++) {
            executor.quarantineUntil("ref#" + i, NOW + 3_600_000L + i);
        }
        assertEquals(LiquidationExecutor.MAX_QUARANTINED, executor.quarantinedCount());

        executor.quarantineUntil("ref#overflow", NOW + 7_200_000L);

        assertEquals(LiquidationExecutor.MAX_QUARANTINED, executor.quarantinedCount(),
                "the bound must hold");
        assertTrue(executor.quarantinedRefs().contains("ref#overflow"), "the newcomer is admitted");
        assertFalse(executor.quarantinedRefs().contains("ref#0"),
                "the entry closest to expiry is the one dropped");
        assertTrue(executor.quarantinedRefs().contains("ref#1"),
                "and only that one — nothing else is evicted to make room");
    }

    // ======================================================================================
    // helpers
    // ======================================================================================

    private static LiquidationDecision onlyDecision(Wiring wiring) {
        List<LiquidationDecision> decisions = wiring.log().newestFirst(10);
        assertEquals(1, decisions.size(), "expected exactly one recorded decision");
        return decisions.getFirst();
    }

    private static LiquidationDecision decisionNumbered(int i) {
        return new LiquidationDecision(NOW + i, String.valueOf(i), TX_LOAN + "#" + i, TX_BOND + "#" + i,
                LiquidationDecision.VARIANT, LiquidationDecision.Outcome.NO_UTXO, "NO_UTXO", "detail",
                null, null, null, null, AssetType.LOVELACE,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
