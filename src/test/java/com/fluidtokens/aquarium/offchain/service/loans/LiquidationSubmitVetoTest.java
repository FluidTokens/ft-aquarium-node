package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

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

    /**
     * The exact preview override T-027 ran under: a negative margin, which under the old
     * margin-inside-the-number arithmetic was a subtraction of a negative and re-authorised a
     * loss-making liquidation. Kept as the number that reproduces the measured incident.
     */
    private static final BigInteger NEGATIVE_MARGIN = BigInteger.valueOf(-3_000_000);

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
     * The T-027 shape: a token-collateral loan whose fee slice is tiny, so once the min-ada the bot
     * funds on the emitted asset-manager output is counted the liquidation is a net loss.
     * <p>
     * Collateral 1_000_000 TOK at 50 lovelace against 55 ADA of debt — under water, equity zero. The
     * bond's fee is only 10 per mille, so the fee slice is {@code 1_000_000 * 10 / 1000 = 10_000 TOK},
     * priced through the c3 feed to {@code 500_000 lovelace}. That is below the transaction fee plus
     * the ~min-ada rider the token collateral output has to be funded to, so {@code floorProfit} is
     * negative — a real loss, exactly as {@code 79e62601…} was.
     */
    private static Scenario lossMakingTokenScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(BigInteger.valueOf(10),
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), collateralFeed(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    /**
     * The T-027 shape as a min-ada-DRIVEN loss: a positive-equity token-collateral liquidation whose
     * fee slice comfortably covers the transaction fee, yet the min-ada the bot funds on the two emitted
     * asset-manager outputs turns it into a net loss all on its own.
     * <p>
     * Collateral 1_000_000 TOK at 50 lovelace = 50 ADA against a 40 ADA principal, so the equity is
     * positive and <em>not</em> in the principal currency — which is exactly the loan the builder emits
     * with <b>two</b> asset-manager outputs (borrower compensation + lender claim), just as
     * {@code 79e62601…} did. Each token-carrying output is topped to its ~1.6 ADA min-ada, so
     * {@code Σ(assetManagerOutput.ada) = A = 3_193_710} against the loan UTxO's own {@code L =
     * 2_000_000}: the bot funds {@code A − L = 1_193_710}.
     * <p>
     * The bond's fee is 40 per mille, so the fee slice is {@code 1_000_000 * 40 / 1000 = 40_000 TOK},
     * priced through the c3 feed to {@code 2_000_000 lovelace} — above the ~1.36 ADA transaction fee, so
     * {@code expectedFee − txFee > 0} and the loss is NOT a fee/size artefact. It is the {@code A − L}
     * rider that pulls {@code floorProfit} below zero. Zero that rider out and the fee slice alone clears
     * the floor and the candidate submits — which is the verdict flip {@link
     * #aTokenLiquidationRefusedSolelyByTheMinAdaRider()} pins.
     */
    private static Scenario minAdaDrivenLossTokenScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(40_000_000),
                BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(BigInteger.valueOf(40),
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
        private Account account = ACCOUNT;

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
         * T-037: overrides the default {@code ACCOUNT} — a spy of it with {@code sign} stubbed to
         * throw is how the sign-failure catch (not one of the eight vetoes) gets driven.
         */
        Rig account(Account account) {
            this.account = account;
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
            PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                    LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                    new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                            LoanFixtures.utxoSupplier(universe), protocolParams()));
            LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                    new FakeAppUtxoService(), account, new FakeScanner(List.of(scenario.assessment())),
                    new FakeResolver(unspent, loanAnswersBeforeItIsGone, bondAnswersBeforeItIsGone,
                            loanThrows),
                    builder, payInAdvanceRouter, LoanFixtures.registry(), log, provider(oracle),
                    networkNamed(networkName), params, LoanFixtures.converters(), submitter);
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

    /**
     * FIX 1 + FIX 2, the T-027 measured loss, reproduced and shown refused.
     * <p>
     * The candidate is a real net loss: its fee slice does not cover the transaction fee plus the
     * min-ada the bot funds on the emitted asset-manager output, so {@code floorProfit} — the
     * margin-EXCLUDED {@code fee − txFee − minAdaFunded} — is negative. It is armed, LIVE, on preview,
     * and running under the exact {@code −3,000,000} preview margin the incident ran under.
     * <p>
     * The verdict-flip is built into the assertions, so the test is its own mutant:
     * <ul>
     *   <li>the recorded {@code expectedProfitLovelace} (= {@code floorProfit − margin}) is
     *       <b>positive</b> — because the negative margin, subtracted-as-a-negative, inflates it. That
     *       is precisely the number the old {@code fee − txFee − margin} arithmetic tested, and a code
     *       that still put the margin inside the floored number would clear this candidate and submit a
     *       loss;</li>
     *   <li>the reconstructed {@code floorProfit} (= {@code expectedProfit + margin}) is
     *       <b>negative</b>, and it is what the absolute floor tests, so the candidate is refused
     *       {@code NOT_PROFITABLE} and nothing is submitted;</li>
     *   <li>and {@code minAdaFunded} (= {@code expectedFee − txFee − floorProfit}) is <b>zero</b>: this
     *       fixture liquidates at zero equity, so the builder emits a single lender-claim output whose
     *       min-ada the loan UTxO's own ada already covers, and FIX 1 funds nothing. The loss here is
     *       therefore fee-driven — the {@code 500_000} fee slice does not cover the {@code ~1.35 ADA}
     *       transaction fee — <em>not</em> min-ada-driven. The min-ada-driven verdict is pinned
     *       separately by {@link #aTokenLiquidationRefusedSolelyByTheMinAdaRider()}.</li>
     * </ul>
     */
    @Test
    void aFloorNegativeLiquidationIsRefusedEvenWhenANegativeMarginInflatesTheMarginAdjustedNumber() {
        Run run = new Rig()
                .scenario(lossMakingTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        NEGATIVE_MARGIN, PUBLISHED))
                .run();

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);

        BigInteger margin = decision.marginLovelace();
        BigInteger expectedProfit = decision.expectedProfitLovelace();
        BigInteger floorProfit = expectedProfit.add(margin);
        BigInteger minAdaFunded = decision.expectedFeeLovelace()
                .subtract(decision.txFeeLovelace()).subtract(floorProfit);

        assertEquals(NEGATIVE_MARGIN, margin, "the incident's negative preview margin");
        assertTrue(expectedProfit.signum() > 0,
                ("the margin-adjusted number the OLD arithmetic tested is positive (%s) — a code that "
                        + "put the margin back inside the floored number would submit this loss")
                        .formatted(expectedProfit));
        assertTrue(floorProfit.signum() < 0,
                "the margin-excluded floorProfit is negative (" + floorProfit + "), and it is what the "
                        + "absolute floor tests — so the loss is refused regardless of the margin");
        assertEquals(BigInteger.ZERO, minAdaFunded,
                "this zero-equity fixture emits a single output the loan UTxO's own ada covers, so FIX 1 "
                        + "funds nothing (" + minAdaFunded + ") and the loss is fee-driven, not "
                        + "min-ada-driven: " + decision.detail());
    }

    /**
     * FIX 1, the T-027 min-ada rider as a VERDICT-flipping pin — the case the zero-equity fixture above
     * cannot exercise. A positive-equity token liquidation whose fee slice ({@code 2_000_000}) covers
     * the transaction fee, so neither the fee nor the size makes it a loss; only the min-ada the bot
     * funds on the two asset-manager outputs ({@code A − L = 1_193_710}) pulls {@code floorProfit} below
     * the absolute floor. Armed, LIVE, on preview, margin at zero so the floor is the only lever.
     * <p>
     * The candidate is refused {@code NOT_PROFITABLE} and nothing is submitted, and that {@code vetoed}
     * verdict — not an arithmetic identity — is the pin. It is a genuine mutant catcher: zero the
     * {@code minAdaFunded} rider in {@link LiquidationExecutor} and {@code floorProfit} becomes
     * {@code expectedFee − txFee} (positive), clearing both the absolute floor and the zero margin, so
     * the candidate SUBMITS and this test reddens on {@code assertNothingWasSubmitted}. The two
     * relationships asserted below spell out why the rider — and only the rider — is what refuses it:
     * {@code floorProfit < 0} (refused) while {@code expectedFee − txFee > 0} (the fee slice covers the
     * fee), so the entire shortfall is the min-ada rider.
     */
    @Test
    void aTokenLiquidationRefusedSolelyByTheMinAdaRider() {
        Run run = new Rig()
                .scenario(minAdaDrivenLossTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, true,
                        BigInteger.ZERO, PUBLISHED))
                .run();

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);

        BigInteger margin = decision.marginLovelace();
        BigInteger floorProfit = decision.expectedProfitLovelace().add(margin);
        BigInteger feeSliceOverFee = decision.expectedFeeLovelace().subtract(decision.txFeeLovelace());
        BigInteger minAdaFunded = feeSliceOverFee.subtract(floorProfit);

        assertEquals(BigInteger.ZERO, margin, "the floor is the only lever in play here");
        assertTrue(floorProfit.signum() < 0,
                "the margin-excluded floorProfit is negative (" + floorProfit + "), so the candidate is "
                        + "refused: " + decision.detail());
        assertTrue(feeSliceOverFee.signum() > 0,
                "the fee slice covers the transaction fee (" + feeSliceOverFee + " > 0), so zeroing the "
                        + "min-ada rider would lift floorProfit above the floor and submit this candidate "
                        + "— the loss is the rider, not a fee/size artefact: " + decision.detail());
        assertTrue(minAdaFunded.compareTo(feeSliceOverFee) > 0,
                "the min-ada rider (" + minAdaFunded + ") is the whole reason floorProfit is below zero: "
                        + decision.detail());
    }

    /**
     * FIX 3 (F1.ii). A negative {@code profit-margin-lovelace} is a preview-only override; on mainnet
     * it is a hard startup failure, so a preview config copied to a mainnet node cannot silently
     * sanction losses. The guard lives where both the network and the margin are known — construction
     * of the liquidation executor — so a mainnet node that would arm the path refuses to come up. On
     * preview the same margin is a WARN and the loop runs.
     */
    @Test
    void aNegativeMarginHardFailsOnMainnetButIsOnlyAWarningOnPreview() {
        // Mainnet: constructing the executor (inside run()) throws, naming the override.
        IllegalStateException mainnet = assertThrows(IllegalStateException.class, () -> new Rig()
                .network("mainnet")
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW, false,
                        NEGATIVE_MARGIN, PUBLISHED))
                .run());
        assertTrue(mainnet.getMessage().contains("profit-margin-lovelace")
                        && mainnet.getMessage().contains("mainnet"),
                "the failure must name the override and the network: " + mainnet.getMessage());

        // Preview: the identical negative margin does not stop the node — it warns and runs a cycle.
        Run preview = new Rig()
                .network("preview")
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW, false,
                        NEGATIVE_MARGIN, PUBLISHED))
                .run();
        assertNotNull(preview.onlyDecision(),
                "on preview the negative margin is a WARN, so the loop still records a decision");
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

    /**
     * T-037: before the fix this catch was {@code log.warn(..., e.toString())} — a generic message at
     * WARN with the stack invisible on an INFO-level node. This is exactly the failure a live-armed
     * node needs to see, so it is now a single ERROR line carrying the full cause chain plus the
     * exception itself — the same idiom the build-path catches already use. The recorded outcome is
     * untouched: this is a log/detail-only fix, proved here by asserting {@code SUBMIT_FAILED} still
     * comes out unchanged alongside the new log event.
     */
    @Test
    void aThrowingSubmissionIsLoggedAtErrorWithTheCauseAttached() {
        RuntimeException boom = new IllegalStateException("connection reset");
        RecordingSubmitter submitter = RecordingSubmitter.throwing(boom);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            run = new Rig().submitter(submitter).run();
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, run.onlyDecision().outcome(),
                "the fix is log-only — the recorded outcome must not change");
        assertTrue(run.onlyDecision().detail().contains(LiquidationExecutor.causeChain(boom)),
                "the decision detail should also carry the cause chain: " + run.onlyDecision().detail());

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("submitting the liquidation"))
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event for the submit-threw path: "
                + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains(LiquidationExecutor.causeChain(boom)),
                "must surface the real cause chain, not just toString(): " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "the exception itself must be attached for the stack trace, not just the message");
    }

    /**
     * T-037: the sibling swallow, on the sign path. Before the fix this catch was also
     * {@code log.warn(..., e.toString())}. Driven with a spy of {@code ACCOUNT} whose {@code sign} is
     * stubbed to throw — this is the machinery failing after all eight vetoes already said yes, so
     * nothing here is one of them: the recorded outcome stays {@code SUBMIT_VETOED} with no veto name,
     * exactly as before the fix, and nothing reaches the wire.
     */
    @Test
    void aFailedSignIsLoggedAtErrorWithTheCauseAndStillRecordsSubmitVetoed() {
        RuntimeException boom = new IllegalStateException("hsm unavailable");
        Account brokenSigner = spy(ACCOUNT);
        doThrow(boom).when(brokenSigner).sign(any(Transaction.class));

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            run = new Rig().account(brokenSigner).run();
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(0, run.submitter().submitted.size(), "signing threw, so nothing reached the wire");
        LiquidationDecision decision = run.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_VETOED, decision.outcome(),
                "the fix is log-only — the recorded outcome must not change");
        assertEquals(null, decision.submitVeto(),
                "not one of the eight vetoes, so none is named — unchanged by the fix");
        assertTrue(decision.detail().contains(LiquidationExecutor.causeChain(boom)),
                "the decision detail should also carry the cause chain: " + decision.detail());

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("could not sign the liquidation"))
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event for the sign-failure path: "
                + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains(LiquidationExecutor.causeChain(boom)),
                "must surface the real cause chain, not just toString(): " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "the exception itself must be attached for the stack trace, not just the message");
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

        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), protocolParams()));
        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(), ACCOUNT, new FakeScanner(List.of(scenario.assessment())),
                FakeResolver.stable(unspent),
                new LiquidateTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                        protocolParams()),
                payInAdvanceRouter, LoanFixtures.registry(), log, provider(new FakeOracleClient(List.of())),
                networkNamed("preview"), protocolParams(),
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
