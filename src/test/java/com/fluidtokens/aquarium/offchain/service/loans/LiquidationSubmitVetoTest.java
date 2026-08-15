package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The armed half of the liquidation loop: the eight submit vetoes, and the one path that reaches the
 * wire.
 *
 * <h2>What every test here asserts</h2>
 * The <em>consequence</em>, never the control flow. A veto is proved by
 * {@code submitter.submitted().isEmpty()} — nothing was transmitted — and only then by the name the
 * decision carries. A test that asserted "the method returned early" would keep passing if the early
 * return were moved after the submission.
 * <p>
 * Every wiring below is one veto away from submitting: live mode, armed, on preview, profitable,
 * inside maxTxSize, with fresh feeds, unspent UTxOs and an open validity window. Each test then
 * breaks exactly one of those.
 * That is what makes the falsifiable harness meaningful — disable any single veto in
 * {@code LiquidationExecutor} and precisely the test for that veto starts submitting.
 *
 * <h2>Byte identity</h2>
 * {@link #everyVetoPassingSignsTheVettedTransactionAndSubmitsThoseExactBytes()} takes the CBOR the
 * decision recorded — the transaction the vetoes ran against — signs it independently with the same
 * account, and compares byte for byte with what reached the submitter. Ed25519 signing is
 * deterministic and cardano-client-lib splices the witness into the original serialisation, so any
 * rebuild, re-balance or second builder pass between the last veto and the wire changes those bytes
 * and fails this test.
 */
class LiquidationSubmitVetoTest {

    private static final long NOW = 1_700_000_000_000L;

    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final long VALID_FROM = NOW - 30_000L;

    private static final String LOAN_ID = "a1b2c3d4e5f6a1b2";
    private static final String STAKE_KEY = "33333333333333333333333333333333333333333333333333333333";

    private static final String TX_LOAN = "aa".repeat(32);
    private static final String TX_BOND = "dd".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);
    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_REF_SCRIPTS = "cc".repeat(32);

    private static final long COLLATERAL_LOVELACE = 100_000_000L;

    /** 500 per mille of 100 ADA — a 50 ADA fee slice, far above any plausible tx fee. */
    private static final BigInteger FAT_FEE_PER_MILLE = BigInteger.valueOf(500);

    private static final BigInteger SMALL_MARGIN = BigInteger.valueOf(1_500_000);

    /** Larger than the whole fee slice, so the same candidate stops being worth doing. */
    private static final BigInteger HUGE_MARGIN = BigInteger.valueOf(100_000_000);

    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);

    private static final Utxo CONFIG_UTXO = LoanFixtures.adaUtxo(TX_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.CONFIG_POLICY_ID), 5_000_000L);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.adaUtxo(TX_LM_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.LM_CONFIG_POLICY_ID), 5_000_000L);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            ACCOUNT.baseAddress(), 200_000_000L);

    /**
     * The six validators a {@code Liquidate} invokes, published. Not decoration: with none of them
     * published the transaction measures 19_838 bytes against a 16_384-byte maxTxSize, so without
     * this every test in this class would be an S5 test.
     */
    private static final LiquidateTransactionBuilder.ReferenceScripts PUBLISHED =
            new LiquidateTransactionBuilder.ReferenceScripts(
                    new TransactionInput(TX_REF_SCRIPTS, 0),
                    new TransactionInput(TX_REF_SCRIPTS, 1),
                    new TransactionInput(TX_REF_SCRIPTS, 2),
                    new TransactionInput(TX_REF_SCRIPTS, 3),
                    new TransactionInput(TX_REF_SCRIPTS, 4),
                    new TransactionInput(TX_REF_SCRIPTS, 5),
                    null);

    // Token collateral leg, priced by a Charli3 feed — the only shape whose oracle window S6 can
    // have anything to say about.
    private static final AssetType COLLATERAL_TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType ORACLE_TOKEN = new AssetType("b0".repeat(28), "4f52434c");
    private static final String ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String TX_ORACLE_NFT = "9a".repeat(32);
    private static final String TX_ORACLE_SCRIPT = "9b".repeat(32);
    private static final String TX_CHARLI3_PROVIDER = "9c".repeat(32);

    // A token *principal* leg, so the principal branch of the submit-time window check has a feed
    // of its own to be checked against. Every other fixture in this class lends ada.
    private static final AssetType PRINCIPAL_TOKEN = new AssetType("d0".repeat(28), "505249");
    private static final AssetType PRINCIPAL_ORACLE_TOKEN = new AssetType("e0".repeat(28), "504f5243");
    private static final String PRINCIPAL_ORACLE_CREDENTIAL = "a1".repeat(28);
    private static final String TX_PRINCIPAL_ORACLE_NFT = "8a".repeat(32);
    private static final String TX_PRINCIPAL_ORACLE_SCRIPT = "8b".repeat(32);
    private static final String TX_PRINCIPAL_CHARLI3_PROVIDER = "8c".repeat(32);

    /** The feed's window closes 600 s after NOW, which is a real preview c3 window. */
    private static final long FEED_VALID_TO = NOW + 600_000L;

    /**
     * The end of the built transaction's own validity interval, near enough. The executor asks for
     * {@code now + validity-window-seconds} and the builder clamps that inwards to a whole slot, so
     * the real end is at or a shade before this.
     */
    private static final long TX_VALID_TO = NOW + 120_000L;

    // ======================================================================================
    // collaborators
    // ======================================================================================

    /** Records every byte string handed to it, and answers whatever the test told it to. */
    private static final class RecordingSubmitter implements LiquidationExecutor.TransactionSubmitter {

        private final List<byte[]> submitted = new ArrayList<>();

        private final Result<String> answer;

        private final RuntimeException throwable;

        RecordingSubmitter(Result<String> answer, RuntimeException throwable) {
            this.answer = answer;
            this.throwable = throwable;
        }

        static RecordingSubmitter accepting(String txHash) {
            return new RecordingSubmitter(Result.success(txHash).withValue(txHash), null);
        }

        static RecordingSubmitter rejecting(String response) {
            return new RecordingSubmitter(Result.error(response).code(400), null);
        }

        static RecordingSubmitter throwing(RuntimeException e) {
            return new RecordingSubmitter(null, e);
        }

        @Override
        public Result<String> submit(byte[] signedTransactionBytes) {
            submitted.add(signedTransactionBytes);
            if (throwable != null) {
                throw throwable;
            }
            return answer;
        }
    }

    private static final class FakeScanner extends LiquidationCandidateScanner {

        private final List<LiquidationAssessment> assessments;

        FakeScanner(List<LiquidationAssessment> assessments) {
            super(null, null, null);
            this.assessments = assessments;
        }

        @Override
        public List<LiquidationAssessment> scan(long atTimeMillis) {
            return assessments;
        }
    }

    /**
     * Answers from a fixed unspent set, and can be told to stop seeing the loan (or the bond) after
     * a given number of resolutions — which is how a UTxO "moves between the build and the wire".
     */
    private static final class FakeResolver extends LiquidationUtxoResolver {

        private final Map<String, Utxo> unspent;

        private final int loanAnswersBeforeItIsGone;

        private final int bondAnswersBeforeItIsGone;

        private final RuntimeException loanThrows;

        private int loanResolutions;

        private int bondResolutions;

        FakeResolver(Map<String, Utxo> unspent, int loanAnswersBeforeItIsGone,
                     int bondAnswersBeforeItIsGone, RuntimeException loanThrows) {
            super(null, null, null);
            this.unspent = unspent;
            this.loanAnswersBeforeItIsGone = loanAnswersBeforeItIsGone;
            this.bondAnswersBeforeItIsGone = bondAnswersBeforeItIsGone;
            this.loanThrows = loanThrows;
        }

        static FakeResolver stable(Map<String, Utxo> unspent) {
            return new FakeResolver(unspent, Integer.MAX_VALUE, Integer.MAX_VALUE, null);
        }

        @Override
        public Optional<Utxo> resolveLoanUtxo(Loan loan) {
            loanResolutions++;
            if (loanThrows != null && loanResolutions > 1) {
                throw loanThrows;
            }
            if (loanResolutions > loanAnswersBeforeItIsGone) {
                return Optional.empty();
            }
            return Optional.ofNullable(unspent.get(loan.utxoRef()));
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            bondResolutions++;
            if (bondResolutions > bondAnswersBeforeItIsGone) {
                return Optional.empty();
            }
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

        FakeAppUtxoService() {
            super(null, null, null);
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return List.of(WALLET_UTXO);
        }
    }

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

    /** An {@link ObjectProvider} over one fixed client, or over none at all. */
    private static ObjectProvider<FluidOracleClient> provider(FluidOracleClient client) {
        return new ObjectProvider<>() {
            @Override
            public FluidOracleClient getObject() {
                return client;
            }

            @Override
            public FluidOracleClient getObject(Object... args) {
                return client;
            }

            @Override
            public FluidOracleClient getIfAvailable() {
                return client;
            }

            @Override
            public FluidOracleClient getIfUnique() {
                return client;
            }
        };
    }

    private static AppConfig.Network networkNamed(String name) {
        return new AppConfig.Network() {
            @Override
            public String getNetwork() {
                return name;
            }
        };
    }

    /** The fixture parameters, with a supplier that hands back the params it was given. */
    private static ProtocolParamsSupplier protocolParams() {
        return LoanFixtures.protocolParams();
    }

    /** A supplier that cannot answer — the "protocol parameters cannot be fetched" case. */
    private static ProtocolParamsSupplier unfetchableProtocolParams() {
        return () -> {
            throw new IllegalStateException("blockfrost timed out fetching protocol parameters");
        };
    }

    /** Real parameters with the one field S5 reads removed. */
    private static ProtocolParamsSupplier protocolParamsWithoutMaxTxSize() {
        ProtocolParams params = protocolParams().getProtocolParams();
        params.setMaxTxSize(null);
        return () -> params;
    }

    // ======================================================================================
    // fixtures
    // ======================================================================================

    private record Scenario(LoanFixtures.LoanUtxo loan,
                            LoanFixtures.BondUtxo bond,
                            LiquidationAssessment assessment) {
    }

    /** 100 ADA of collateral against 110 ADA of debt: under water, so the equity is exactly zero. */
    private static Scenario adaScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    /** A token-collateral loan priced by a Charli3 feed, so there is a window for S6 to check. */
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

    /**
     * A loan whose <em>principal</em> is a token and whose collateral is ada. 100 ADA of collateral
     * against 110 PRI of debt at 1 lovelace apiece: under water, so the equity is exactly zero, and
     * the only oracle feed in the transaction is the principal one.
     */
    private static Scenario tokenPrincipalScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000),
                LoanFixtures.adaCollateral(), LATE_LEND_DATE, LoanFixtures.liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                principalFeed(), OraclePriceFeed.unit(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    /** 1 lovelace per PRI, over the same 600 s window a preview c3 feed publishes. */
    private static OraclePriceFeed principalFeed() {
        return OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.ONE, BigInteger.ONE,
                NOW - 60_000L, FEED_VALID_TO);
    }

    private static OracleEntry principalOracle() {
        return LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                PRINCIPAL_ORACLE_CREDENTIAL, principalFeed(),
                LoanFixtures.input(TX_PRINCIPAL_ORACLE_NFT, 0),
                LoanFixtures.input(TX_PRINCIPAL_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_PRINCIPAL_CHARLI3_PROVIDER, 0));
    }

    private static OraclePriceFeed collateralFeed() {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50),
                BigInteger.ONE, NOW - 60_000L, FEED_VALID_TO);
    }

    private static OracleEntry collateralOracle() {
        return LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, ORACLE_CREDENTIAL, collateralFeed(),
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_CHARLI3_PROVIDER, 0));
    }

    private static AppConfig.LiquidationConfiguration configuration(
            AppConfig.LiquidationConfiguration.Mode mode, boolean enabled, BigInteger margin,
            LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
        return new AppConfig.LiquidationConfiguration(mode, enabled, 60, 120, 30, margin, 200, 30,
                referenceScripts);
    }

    /** Armed: live, enabled, and with the reference scripts published. */
    private static AppConfig.LiquidationConfiguration armed() {
        return configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true, SMALL_MARGIN, PUBLISHED);
    }

    // ======================================================================================
    // wiring
    // ======================================================================================

    /**
     * A cycle that is one step from submitting. Every parameter defaults to the passing value; a test
     * changes exactly one.
     */
    private static final class Rig {

        private AppConfig.LiquidationConfiguration configuration = armed();
        private Scenario scenario = adaScenario();
        private String networkName = "preview";
        private ProtocolParamsSupplier params = protocolParams();
        private FluidOracleClient oracle = new FakeOracleClient(List.of());
        private long submitTime = NOW;
        private int loanAnswersBeforeItIsGone = Integer.MAX_VALUE;
        private int bondAnswersBeforeItIsGone = Integer.MAX_VALUE;
        private RuntimeException loanThrows;
        private RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        Rig configuration(AppConfig.LiquidationConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }

        Rig scenario(Scenario scenario) {
            this.scenario = scenario;
            return this;
        }

        Rig network(String networkName) {
            this.networkName = networkName;
            return this;
        }

        Rig params(ProtocolParamsSupplier params) {
            this.params = params;
            return this;
        }

        Rig oracle(FluidOracleClient oracle) {
            this.oracle = oracle;
            return this;
        }

        Rig submitAt(long submitTime) {
            this.submitTime = submitTime;
            return this;
        }

        Rig loanGoneAfter(int answers) {
            this.loanAnswersBeforeItIsGone = answers;
            return this;
        }

        Rig bondGoneAfter(int answers) {
            this.bondAnswersBeforeItIsGone = answers;
            return this;
        }

        Rig loanRecheckThrows(RuntimeException e) {
            this.loanThrows = e;
            return this;
        }

        Rig submitter(RecordingSubmitter submitter) {
            this.submitter = submitter;
            return this;
        }

        /**
         * How many cycles to drive against the same executor. Each subsequent cycle advances both
         * the cycle clock and the submit clock by one minute, which is what a real scheduler does
         * and what makes the quarantine's effect observable.
         */
        Rig cycles(int cycles) {
            this.cycles = cycles;
            return this;
        }

        private int cycles = 1;

        Run run() {
            List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                    scenario.loan().utxo(), scenario.bond().utxo()));
            Map<String, Utxo> unspent = new LinkedHashMap<>();
            unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
            unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());

            LiquidateTransactionBuilder builder = new LiquidateTransactionBuilder(
                    LoanFixtures.registry(), LoanFixtures.NETWORK, LoanFixtures.converters(),
                    LoanFixtures.utxoSupplier(universe), protocolParams());

            BlockEventListener blockEventListener = new BlockEventListener(null);
            blockEventListener.getIsSyncing().set(false);

            LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
            LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                    new FakeAppUtxoService(), ACCOUNT, new FakeScanner(List.of(scenario.assessment())),
                    new FakeResolver(unspent, loanAnswersBeforeItIsGone, bondAnswersBeforeItIsGone,
                            loanThrows),
                    builder, log, provider(oracle), networkNamed(networkName), params,
                    LoanFixtures.converters(), submitter);
            long[] elapsed = {0};
            executor.setSubmitClock(() -> submitTime + elapsed[0]);

            for (int cycle = 0; cycle < cycles; cycle++) {
                elapsed[0] = cycle * 60_000L;
                executor.cycle(NOW + elapsed[0]);
            }
            return new Run(log, submitter);
        }
    }

    private record Run(LiquidationDecisionLog log, RecordingSubmitter submitter) {

        LiquidationDecision onlyDecision() {
            List<LiquidationDecision> decisions = log.newestFirst(10);
            assertEquals(1, decisions.size(), "expected exactly one recorded decision");
            return decisions.getFirst();
        }

        /** The consequence every veto test is really about. */
        void assertNothingWasSubmitted() {
            assertTrue(submitter.submitted.isEmpty(),
                    "the veto did not hold: " + submitter.submitted.size()
                            + " transaction(s) reached the submitter");
        }
    }

    /** The common shape: one veto fired, nothing went out, and the row names it. */
    private static LiquidationDecision vetoed(Run run, LiquidationExecutor.SubmitVeto veto,
                                              LiquidationDecision.Outcome outcome) {
        run.assertNothingWasSubmitted();
        LiquidationDecision decision = run.onlyDecision();
        assertEquals(veto.name(), decision.submitVeto(), decision.detail());
        assertEquals(outcome, decision.outcome(), decision.detail());
        return decision;
    }

    // ======================================================================================
    // S1 — the mode
    // ======================================================================================

    /**
     * Everything else is armed: enabled, preview, profitable, small enough, fresh feeds, unspent
     * UTxOs. The mode alone is what stops it, and the row still reports the candidate's own verdict
     * — which is exactly what makes shadow mode legible.
     */
    @Test
    void s1AShadowModeSubmitsNothingEvenWhenEverythingElseWouldAllowIt() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW, true,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE,
                LiquidationDecision.Outcome.WOULD_SUBMIT);
        assertTrue(decision.expectedProfitLovelace().signum() > 0,
                "the candidate was worth doing — the mode is the only thing that stopped it");
    }

    /** And the disabled mode, which does not even get as far as building. */
    @Test
    void s1BDisabledModeSubmitsNothing() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED, true,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        run.assertNothingWasSubmitted();
        assertEquals(0, run.log().size());
    }

    // ======================================================================================
    // S2 — the arming flag
    // ======================================================================================

    /**
     * Two independent switches by design. {@code mode: live} on its own is one flag flipped — an
     * operator experimenting, a copied env file — and it must not be enough.
     */
    @Test
    void s2LiveModeWithoutTheArmingFlagSubmitsNothing() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, false,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.NOT_ARMED,
                LiquidationDecision.Outcome.WOULD_SUBMIT);
        assertTrue(decision.expectedProfitLovelace().signum() > 0);
    }

    // ======================================================================================
    // S3 — the network
    // ======================================================================================

    /**
     * Mainnet is additionally protected by {@code loans.enabled=false}, which stops this class from
     * existing at all there. This veto is the second line, enforced in the code that would do the
     * submitting rather than in the wiring — so it holds even on a node where lending was switched on.
     * <p>
     * Preprod is checked alongside it because the veto is an allow-list of one, not a mainnet block:
     * a preprod deployment has no verified reference-script coordinates either, and would build
     * against a contract tree this node cannot vouch for.
     */
    @Test
    void s3AFullyArmedNodeOnAnyNetworkButPreviewSubmitsNothing() {
        for (String networkName : List.of("mainnet", "preprod")) {
            Run run = new Rig().network(networkName).run();

            run.assertNothingWasSubmitted();
            LiquidationDecision decision = run.onlyDecision();
            assertEquals(LiquidationExecutor.SubmitVeto.NETWORK_NOT_PREVIEW.name(),
                    decision.submitVeto(), "on " + networkName + ": " + decision.detail());
            assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome());
            assertTrue(decision.expectedProfitLovelace().signum() > 0,
                    "the candidate was worth doing on " + networkName + " — only the network stopped it");
        }
    }

    // ======================================================================================
    // S4 — profitability
    // ======================================================================================

    /**
     * Two cases, one veto.
     * <ul>
     *   <li>A margin above the whole fee slice — nothing about the loan changed, only the operator's
     *       idea of what is worth doing.</li>
     *   <li>Exactly break-even. The threshold is strictly greater than zero, so equality does not
     *       submit: a liquidation that pays for itself and nothing more is not worth moving somebody
     *       else's collateral for. The margin here is derived from the fee slice and the built
     *       transaction's actual fee, so the expected profit lands on zero rather than near it — which
     *       is what makes a widened {@code >= 0} threshold fail this test rather than pass it.</li>
     * </ul>
     */
    @Test
    void s4ACandidateThatDoesNotClearTheMarginSubmitsNothing() {
        Run unprofitable = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        HUGE_MARGIN, PUBLISHED))
                .run();

        LiquidationDecision decision = vetoed(unprofitable,
                LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);
        assertTrue(decision.expectedProfitLovelace().signum() < 0);

        // Exactly zero: 50 ADA fee slice - txFee - margin == 0.
        BigInteger txFee = new Rig().run().onlyDecision().txFeeLovelace();
        assertNotNull(txFee);
        BigInteger breakEvenMargin = BigInteger.valueOf(50_000_000).subtract(txFee);

        Run breakEven = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        breakEvenMargin, PUBLISHED))
                .run();

        LiquidationDecision atZero = vetoed(breakEven, LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);
        assertEquals(BigInteger.ZERO, atZero.expectedProfitLovelace(),
                "the fixture must sit exactly on zero, or this is not a break-even test");
    }

    // ======================================================================================
    // S5 — the size, against the live protocol parameters
    // ======================================================================================

    /**
     * No reference scripts published, so all six validators travel in the witness set: 19_838 bytes
     * against a 16_384-byte maxTxSize. The candidate is handsomely profitable, which is the point —
     * S5 is not S4's arithmetic wearing a different name.
     */
    @Test
    void s5ATransactionThatCannotBeShownToFitSubmitsNothing() {
        // Oversized: no reference scripts published, so all six validators travel in the witness set.
        Run oversized = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .run();

        LiquidationDecision decision = vetoed(oversized, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(decision.txSizeBytes() > 16_384,
                "the fixture must actually be oversized: " + decision.txSizeBytes());
        assertTrue(decision.expectedProfitLovelace().signum() > 0,
                "and profitable, so S5 cannot be S4 in disguise");

        // Protocol parameters that cannot be fetched. Not knowing the limit is not being under it.
        Run unfetchable = new Rig().params(unfetchableProtocolParams()).run();
        LiquidationDecision timedOut = vetoed(unfetchable, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(timedOut.detail().contains("maxTxSize could not be fetched"), timedOut.detail());

        // Parameters that arrive without the one field this veto reads. Same answer.
        Run noLimit = new Rig().params(protocolParamsWithoutMaxTxSize()).run();
        vetoed(noLimit, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
    }

    /**
     * The size veto reads the live parameter rather than a constant. Here maxTxSize is raised above
     * the 19_838-byte transaction, and the same candidate that S5 refused above goes out — so the
     * check cannot be a hard-coded 16384.
     */
    @Test
    void s5ReadsTheLiveMaxTxSizeRatherThanAConstant() {
        ProtocolParams generous = protocolParams().getProtocolParams();
        generous.setMaxTxSize(1_000_000);

        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .params(() -> generous)
                .run();

        assertEquals(1, run.submitter().submitted.size(),
                "with a large enough maxTxSize the very transaction S5 refused must go out");
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, run.onlyDecision().outcome());
    }

    // ======================================================================================
    // S6 — the oracle window at submit time
    // ======================================================================================

    /**
     * The transaction built happily: at cycle time the feed had 600 s of window ahead of it, far more
     * than the builder's V3 needed. By the time the vetoes ran — after the resolves, the parameter
     * fetch and the script evaluation, which are Blockfrost round trips — the clock had moved past
     * the point where the configured 30 s margin still fits.
     * <p>
     * This is why S6 reads a submit-time clock and not the cycle's {@code now}: against the cycle's
     * own instant it could never say anything the builder had not already said.
     */
    @Test
    void s6AFeedThatCannotBeShownFreshAtSubmitTimeSubmitsNothing() {
        // First, the case that does not depend on the clock: no registry client at all. An absent
        // registry is not a fresh one — even on an ada/ada loan, which consults no feed. Being unable
        // to check is failing the check.
        //
        // It is deliberately first. The two clock-driven cases below can only fire at instants where
        // S8 would also fire (see SubmitVeto.TRANSACTION_WINDOW_ELAPSED), so on their own they would
        // let a "delete S6" mutation be caught by S8 and reported as a name mismatch rather than as
        // a submission. This case fires at NOW, well inside the transaction's window, so deleting S6
        // submits — and the failure is then the consequence, not the label.
        Run noClient = new Rig().oracle(null).run();
        LiquidationDecision absent = vetoed(noClient,
                LiquidationExecutor.SubmitVeto.ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(absent.detail().contains("unavailable"), absent.detail());

        // The collateral leg's feed, with 20 s of window left against a 30 s margin.
        Run closingCollateral = new Rig()
                .scenario(tokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .submitAt(FEED_VALID_TO - 20_000L)
                .run();

        LiquidationDecision collateral = vetoed(closingCollateral,
                LiquidationExecutor.SubmitVeto.ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(collateral.detail().contains("collateral feed has"), collateral.detail());

        // And the PRINCIPAL leg, which every other fixture in this class leaves as ada and therefore
        // never exercises. A token principal against ada collateral: the only feed in the transaction
        // is the principal one, so deleting that branch of the check leaves nothing to catch this.
        Run closingPrincipal = new Rig()
                .scenario(tokenPrincipalScenario())
                .oracle(new FakeOracleClient(List.of(principalOracle())))
                .submitAt(FEED_VALID_TO - 20_000L)
                .run();

        LiquidationDecision principal = vetoed(closingPrincipal,
                LiquidationExecutor.SubmitVeto.ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(principal.detail().contains("principal feed has"), principal.detail());
    }

    /** The token-principal loan goes out normally while its principal feed is still wide open. */
    @Test
    void s6ATokenPrincipalLoanGoesOutWhileItsPrincipalFeedIsStillOpen() {
        Run run = new Rig()
                .scenario(tokenPrincipalScenario())
                .oracle(new FakeOracleClient(List.of(principalOracle())))
                .submitAt(NOW)
                .run();

        assertEquals(1, run.submitter().submitted.size(),
                "the principal-leg fixture must be submittable, or the veto case above proves nothing");
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, run.onlyDecision().outcome());
    }

    /** The same candidate, submitted while the window is still wide open. */
    @Test
    void s6TheSameCandidateGoesOutWhileItsFeedWindowIsStillOpen() {
        Run run = new Rig()
                .scenario(tokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .submitAt(NOW)
                .run();

        assertEquals(1, run.submitter().submitted.size());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, run.onlyDecision().outcome());
    }

    // ======================================================================================
    // S7 — the UTxOs, re-read immediately before the wire
    // ======================================================================================

    /**
     * The loan UTxO was there when the build started and is gone by the time the vetoes finish —
     * repaid, or liquidated by somebody else in the meantime. Submitting now would spend an output
     * nobody assessed.
     */
    @Test
    void s7AUtxoThatCannotBeShownUnspentSubmitsNothing() {
        // The loan was there when the build started and is gone by the time the vetoes finish.
        Run loanGone = new Rig().loanGoneAfter(1).run();
        LiquidationDecision loan = vetoed(loanGone, LiquidationExecutor.SubmitVeto.STALE_UTXO,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(loan.detail().contains("loan utxo"), loan.detail());

        // The other half of the same predicate: the bond moved while the loan stayed put.
        Run bondGone = new Rig().bondGoneAfter(1).run();
        LiquidationDecision bond = vetoed(bondGone, LiquidationExecutor.SubmitVeto.STALE_UTXO,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(bond.detail().contains("bond utxo"), bond.detail());

        // And an index that throws has not said the output is still there.
        Run unreadable = new Rig()
                .loanRecheckThrows(new IllegalStateException("the local index is not readable"))
                .run();
        LiquidationDecision threw = vetoed(unreadable, LiquidationExecutor.SubmitVeto.STALE_UTXO,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(threw.detail().contains("threw"), threw.detail());
    }

    // ======================================================================================
    // S8 — the transaction's own validity window
    // ======================================================================================

    /**
     * The gap S6 cannot cover. This is an ada/ada loan: it has no oracle feed at all, so before S8
     * there was no submit-time staleness check on it whatsoever, and a transaction whose validity
     * interval had already elapsed would be signed and sent.
     * <p>
     * The direction was already safe — an expired transaction is refused in phase 1 and costs
     * nothing — but ada/ada is the shape that actually builds today, and "we submitted a transaction
     * we knew had expired" is not a thing this loop should do.
     */
    @Test
    void s8AnExpiredTransactionSubmitsNothing() {
        Run run = new Rig()
                // Well past the transaction's own validity end, and note there is no feed here for
                // S6 to have had an opinion about.
                .submitAt(TX_VALID_TO + 80_000L)
                .run();

        LiquidationDecision decision = vetoed(run,
                LiquidationExecutor.SubmitVeto.TRANSACTION_WINDOW_ELAPSED,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(decision.detail().contains("validity interval ended at"), decision.detail());
        assertTrue(decision.expectedProfitLovelace().signum() > 0,
                "the candidate was worth doing — only the elapsed window stopped it");
    }

    /** The same ada/ada candidate, submitted while its window is still open. */
    @Test
    void s8TheSameCandidateGoesOutWhileItsWindowIsStillOpen() {
        Run run = new Rig().submitAt(TX_VALID_TO - 60_000L).run();

        assertEquals(1, run.submitter().submitted.size());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, run.onlyDecision().outcome());
    }

    // ======================================================================================
    // the one path to the wire
    // ======================================================================================

    /**
     * All seven vetoes pass. The bytes that reach the submitter are the bytes that were vetted, plus
     * exactly one signature.
     * <p>
     * The comparison is made against the CBOR the decision recorded — that is the transaction the
     * veto chain ran against — signed here independently with the same account. If anything between
     * the last veto and the wire rebuilt, re-balanced or re-priced the transaction, these arrays
     * differ.
     */
    @Test
    void everyVetoPassingSignsTheVettedTransactionAndSubmitsThoseExactBytes() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        Run run = new Rig().submitter(submitter).run();

        LiquidationDecision decision = run.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decision.outcome(), decision.detail());
        assertEquals(null, decision.submitVeto(), "no veto fired, so none is named");
        assertEquals(1, submitter.submitted.size(), "exactly one transaction reached the wire");

        byte[] vettedCbor = HexUtil.decodeHexString(decision.txCborHex());
        Transaction vetted = Transaction.deserialize(vettedCbor);
        assertTrue(vetted.getWitnessSet().getVkeyWitnesses() == null
                        || vetted.getWitnessSet().getVkeyWitnesses().isEmpty(),
                "the vetted transaction is the unsigned one");

        byte[] submitted = submitter.submitted.getFirst();
        assertArrayEquals(ACCOUNT.sign(vetted).serialize(), submitted,
                "the submitted bytes are not the vetted transaction plus one signature — something "
                        + "between the last veto and the wire changed the transaction");

        // And the body is untouched: the hash the decision published is the hash that went out.
        Transaction onTheWire = Transaction.deserialize(submitted);
        assertEquals(decision.txHash(), TransactionUtil.getTxHash(onTheWire));
        assertEquals(1, onTheWire.getWitnessSet().getVkeyWitnesses().size(),
                "exactly one witness — the fee the builder computed accounts for exactly one");
    }

    /** A backend that says no. Recorded as SUBMIT_FAILED, with its response. */
    @Test
    void aRejectedSubmissionIsRecordedAsSubmitFailedWithTheBackendResponse() {
        RecordingSubmitter submitter = RecordingSubmitter.rejecting("ValueNotConservedUTxO");
        Run run = new Rig().submitter(submitter).run();

        assertEquals(1, submitter.submitted.size(), "it was transmitted");
        LiquidationDecision decision = run.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, decision.outcome());
        assertTrue(decision.detail().contains("ValueNotConservedUTxO"), decision.detail());
    }

    /**
     * A backend that throws. Same conclusion: the attempt happened, so it is not a veto.
     * <p>
     * And the second cycle is the point of this test, not an afterthought. A connection reset is
     * thrown <em>after</em> the bytes have gone out as readily as before, so this is precisely the
     * case where transmission status is unknown — and precisely where a quarantine taken only on the
     * success path would let the next cycle re-derive the same still-unspent loan UTxO and submit a
     * second transaction against it. The quarantine is taken before the attempt, either way.
     */
    @Test
    void aThrowingSubmissionIsRecordedAsSubmitFailedAndStillQuarantinesTheLoan() {
        RecordingSubmitter submitter = RecordingSubmitter.throwing(
                new IllegalStateException("connection reset"));
        Run run = new Rig().submitter(submitter).cycles(2).run();

        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, run.onlyDecision().outcome());
        assertEquals(1, submitter.submitted.size(),
                "a submission whose outcome is UNKNOWN was retried on the next cycle — that is the "
                        + "double-submit the quarantine exists to prevent");
        assertEquals(1, run.log().size(), "and no second decision was even derived for it");
    }

    /** The same property for a cleanly rejected submission. */
    @Test
    void aRejectedSubmissionAlsoQuarantinesTheLoan() {
        RecordingSubmitter submitter = RecordingSubmitter.rejecting("ValueNotConservedUTxO");
        Run run = new Rig().submitter(submitter).cycles(2).run();

        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, run.onlyDecision().outcome());
        assertEquals(1, submitter.submitted.size(),
                "a rejected submission was retried on the next cycle");
    }

    // ======================================================================================
    // one submission per loan utxo
    // ======================================================================================

    /**
     * The property is about the loan <b>UTxO</b>, not about the decision row: a submitted liquidation
     * takes a quarantine on {@code txHash#index}, and the local index cannot possibly have seen the
     * spend by the next cycle. Without it the very next cycle would re-derive the same candidate from
     * the same still-unspent loan output and submit a second transaction spending it.
     */
    @Test
    void aSubmittedLoanUtxoIsNotSubmittedAgainOnTheNextCycleBeforeTheIndexCatchesUp() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        // The rig's run() drives one cycle; this test needs two against the same executor, so it
        // builds the wiring directly rather than through the rig.
        Scenario scenario = adaScenario();
        List<Utxo> universe = List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                scenario.loan().utxo(), scenario.bond().utxo());
        Map<String, Utxo> unspent = new LinkedHashMap<>();
        unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
        unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());

        AppConfig.LiquidationConfiguration configuration = armed();
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(), ACCOUNT, new FakeScanner(List.of(scenario.assessment())),
                FakeResolver.stable(unspent),
                new LiquidateTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                        protocolParams()),
                log, provider(new FakeOracleClient(List.of())), networkNamed("preview"), protocolParams(),
                LoanFixtures.converters(), submitter);
        executor.setSubmitClock(() -> NOW);

        executor.cycle(NOW);
        assertEquals(1, submitter.submitted.size());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, log.newestFirst(1).getFirst().outcome());

        // The chain has not caught up: the loan utxo is still unspent as far as this node can see.
        executor.setSubmitClock(() -> NOW + 60_000L);
        executor.cycle(NOW + 60_000L);

        assertEquals(1, submitter.submitted.size(),
                "the same loan utxo was submitted twice — the quarantine is what has to stop that");
        assertEquals(1, log.size(), "and no second decision was even derived for it");
    }

    // ======================================================================================
    // the shipped defaults
    // ======================================================================================

    /**
     * {@code mode: disabled} and {@code enabled: false} — what an operator gets if they change
     * nothing. Nothing is scanned, nothing is built, nothing is submitted.
     */
    @Test
    void withTheShippedDefaultsNothingIsSubmitted() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED, false,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .submitter(submitter)
                .run();

        run.assertNothingWasSubmitted();
        assertEquals(0, run.log().size());
        assertFalse(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED, false,
                SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()).isArmed());
    }

    /**
     * And the preview default, which is {@code shadow} with the arming flag still off — two changes
     * away from submitting, not one.
     */
    @Test
    void theShippedPreviewDefaultIsAlsoIncapableOfSubmitting() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW, false,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        // Deliberately no veto name: shadow-without-arming is stopped by S1 and would still be
        // stopped by S2, and this test is about the shipped configuration rather than about which
        // of the two got there first.
        run.assertNothingWasSubmitted();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, run.onlyDecision().outcome());
    }
}
