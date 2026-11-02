package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
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
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.cardanofoundation.conversions.CardanoConverters;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

/**
 * The armed half of the liquidation loop: the seven submit vetoes, and the one path that reaches the
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

    /**
     * A live {@link MarketCoverageReporter} for every wiring in this class, on a throwaway registry.
     *
     * <p>⚠ <b>Not a stub, and deliberately not.</b> Every cycle these tests run therefore executes the
     * real reporting path, so the claim this slice makes — that observing which market cannot be
     * served changes no decision — is carried by this whole class rather than asserted once. The
     * dedicated proof is {@code aThrowingReporterChangesNothingAboutTheTransactionThatIsRecorded()}.
     */
    private static MarketCoverageReporter metrics() {
        return new MarketCoverageReporter(new SimpleMeterRegistry());
    }

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
     * this every test in this class would be an S4 test.
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

    // Token collateral leg, priced by a Charli3 feed — the only shape whose oracle window S5 can
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

        /** What every submission after the first answers; null means the same as the first. */
        private Result<String> later;

        RecordingSubmitter(Result<String> answer, RuntimeException throwable) {
            this.answer = answer;
            this.throwable = throwable;
        }

        /**
         * Accepts the first submission, rejects every later one with {@code response} — a liquidation
         * that landed, resubmitted from an index that has not seen it yet.
         */
        static RecordingSubmitter acceptingThenRejecting(String txHash, String response) {
            RecordingSubmitter submitter = accepting(txHash);
            submitter.later = Result.error(response).code(400);
            return submitter;
        }

        static RecordingSubmitter accepting(String txHash) {
            return new RecordingSubmitter(Result.success(txHash).withValue(txHash), null);
        }

        static RecordingSubmitter rejecting(String response) {
            return new RecordingSubmitter(Result.error(response).code(400), null);
        }

        /**
         * FAB-183: Blockfrost's DECODED refusal — the HTTP-400 envelope {@code /tx/submit} documents,
         * {@code message} JSON-escaped as the provider sends it. The one non-success answer
         * {@code WorkResult.classify} calls a DEFINITE rejection; a raw body ({@link #rejecting}) is AMBIGUOUS.
         */
        static RecordingSubmitter rejectingDecoded(String message) {
            return new RecordingSubmitter(decodedRefusal(message), null);
        }

        /** {@link #acceptingThenRejecting}, with every later submission refused by the decoded envelope. */
        static RecordingSubmitter acceptingThenRejectingDecoded(String txHash, String message) {
            RecordingSubmitter submitter = accepting(txHash);
            submitter.later = decodedRefusal(message);
            return submitter;
        }

        static Result<String> decodedRefusal(String message) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("status_code", 400);
            envelope.put("error", "Bad Request");
            envelope.put("message", message);
            try {
                return Result.<String>error(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(envelope)).code(400);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
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
            return later != null && submitted.size() > 1 ? later : answer;
        }
    }

    private static final class FakeScanner extends LiquidationCandidateScanner {

        private final List<LiquidationAssessment> assessments;

        FakeScanner(List<LiquidationAssessment> assessments) {
            super(null, null, null);
            this.assessments = assessments;
        }

        @Override
        public Scan scan(long atTimeMillis) {
            return new Scan(assessments, readableCensus(assessments));
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

        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
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

    /**
     * FAB-185 r2: {@link FakeOracleClient} that also answers the two lookups a pay-in-advance's economics
     * price its legs through — by the oracle NFT the loan's datum names, and by the priced asset — so a
     * pay-in-advance candidate can be priced to a verdict. Only the tests that build one use it.
     */
    private static final class LegPricingOracleClient extends FluidOracleClient {

        private final List<OracleEntry> entries;

        LegPricingOracleClient(List<OracleEntry> entries) {
            super("http://unused.invalid");
            this.entries = entries;
        }

        @Override
        public Collection<OracleEntry> entries() {
            return entries;
        }

        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return entries.stream().filter(e -> e.token().equals(token)).findFirst();
        }

        @Override
        public Optional<OracleEntry> findEntryByOracleToken(AssetType oracleToken) {
            return entries.stream().filter(e -> e.oracleToken().equals(oracleToken)).findFirst();
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

    /**
     * FAB-185: a wallet the stage can resolve its roles from — {@code others} (the largest ada-only one is U)
     * plus role C, sized from {@link #protocolParams()}, the parameters these executors read.
     */
    private static List<Utxo> shaped(Utxo... others) {
        return LoanFixtures.shapedWallet(protocolParams(), ACCOUNT.baseAddress(), others);
    }

    /**
     * FAB-185: the executor reads the parameters at cycle start — to size C, without which nothing is built —
     * and again in the S4 veto. This supplier's FIRST answer is the complete fixture parameters, so the cycle
     * resolves its roles and builds; every later answer is {@code later}'s, so S4 meets the fault under test.
     */
    private static ProtocolParamsSupplier firstAnswerCompleteThen(ProtocolParamsSupplier later) {
        int[] answers = {0};
        return () -> answers[0]++ == 0 ? protocolParams().getProtocolParams() : later.getProtocolParams();
    }

    /** A supplier that cannot answer — the "protocol parameters cannot be fetched" case. */
    private static ProtocolParamsSupplier unfetchableProtocolParams() {
        return () -> {
            // WRAPPED deliberately, the way a transport client wraps the fault it hit. A cause-less
            // fixture cannot tell causeChain(e) from e.toString() — one is a substring of the other —
            // which is exactly how the cause-surfacing assertions on three separate sites came to be
            // vacuous (2026-08-21, 2026-08-24). PARAMS_ROOT_CAUSE below is the discriminator.
            throw new IllegalStateException("blockfrost timed out fetching protocol parameters",
                    new java.net.SocketTimeoutException(PARAMS_ROOT_CAUSE));
        };
    }

    /** The root-cause message {@link #unfetchableProtocolParams()} buries one level down. */
    private static final String PARAMS_ROOT_CAUSE = "connect timed out";

    /** Real parameters with the one field S4 reads removed. */
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

    /** A token-collateral loan priced by a Charli3 feed, so there is a window for S5 to check. */
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
     * The bond's fee is 30 per mille, so the fee slice is {@code 1_000_000 * 30 / 1000 = 30_000 TOK},
     * priced through the c3 feed to {@code 1_500_000 lovelace} — above the ~0.54 ADA transaction fee, so
     * {@code expectedFee − txFee > 0} and the loss is NOT a fee/size artefact. It is the {@code A − L}
     * rider that pulls {@code floorProfit} below zero. Zero that rider out and the fee slice alone clears
     * the floor and the candidate submits — which is the verdict flip {@link
     * #aTokenLiquidationRefusedSolelyByTheMinAdaRider()} pins.
     *
     * <h2>⚠ RECALIBRATED 2026-09-04 — the property held, the scenario stopped instantiating it</h2>
     * The fee was 40 per mille against a transaction fee of ~1.36 ADA, giving
     * {@code 2_000_000 − 1_364_238 − 1_193_710 = −557_948}: refused. When the fixtures moved to the
     * FOURTH deployment the transaction fee fell to ~539,546 (evaluation cost against a different
     * ConfigDatum), and the same scenario came out at <b>+266,744 — it submitted.</b>
     *
     * <p><b>The SCENARIO was recalibrated, not the assertion.</b> Relaxing the assertion would have
     * deleted the property; lowering the fee slice restores the condition the property is about.
     * At 30 per mille the two halves are {@code 1_500_000 − 539_546 − 1_193_710 = −233_256} (refused)
     * and {@code 1_500_000 − 539_546 = +960_454} (submits with the rider zeroed) — both with ~200k of
     * headroom instead of the ~19k the old calibration had.
     *
     * <p>⛔ <b>And that thinness is the lesson worth keeping.</b> A knife-edge scenario does not fail
     * when the thing it tests breaks; it fails when anything nearby moves. If this needs recalibrating
     * a third time, widen the margin rather than re-centring it.
     */
    private static Scenario minAdaDrivenLossTokenScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(40_000_000),
                BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(BigInteger.valueOf(30),
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
            AppConfig.LiquidationConfiguration.Mode mode, BigInteger margin,
            LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
        return new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, margin, 200,
                referenceScripts);
    }

    /** Armed: live, enabled, and with the reference scripts published. */
    private static AppConfig.LiquidationConfiguration armed() {
        return configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, SMALL_MARGIN, PUBLISHED);
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
        private CardanoConverters executorConverters = LoanFixtures.converters();
        private io.micrometer.core.instrument.MeterRegistry meterRegistry;
        /** FAB-185: null means {@link #WALLET_UTXO} plus C sized from {@link #params}. */
        private List<Utxo> wallet;

        /** FAB-185: the wallet stated, for a {@link #params} supplier that must not be asked an extra time. */
        Rig wallet(List<Utxo> wallet) {
            this.wallet = wallet;
            return this;
        }

        /** FAB-183: the registry {@code setMeterRegistry} is handed, as the container would; none by default. */
        Rig meterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

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
         * throw is how the sign-failure catch (not one of the seven vetoes) gets driven.
         */
        Rig account(Account account) {
            this.account = account;
            return this;
        }

        /**
         * T-040: overrides the converters handed to the EXECUTOR only — the builders keep their own
         * real instance. Safe to replace wholesale because the executor uses {@code converters} in
         * exactly one place, the S7 slot-to-time conversion this override exists to break.
         */
        Rig executorConverters(CardanoConverters executorConverters) {
            this.executorConverters = executorConverters;
            return this;
        }

        /**
         * How many cycles to drive against the same executor. Each subsequent cycle advances both
         * the cycle clock and the submit clock by one minute, which is what a real scheduler does
         * and what makes "attempted again next cycle" observable.
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
            // FAB-185: ONE wallet list, read by the executor and resolvable by the plain AND pay-in-advance
            // builders — each pins the executor's role C as collateral, and CCL resolves a pinned collateral
            // input by out-ref.
            List<Utxo> walletUtxos = wallet != null ? wallet
                    : LoanFixtures.shapedWallet(params, ACCOUNT.baseAddress(), WALLET_UTXO);
            List<Utxo> builderUniverse = new ArrayList<>(universe);
            for (Utxo walletUtxo : walletUtxos) {
                if (walletUtxo != null && builderUniverse.stream().noneMatch(u -> u.getTxHash()
                        .equals(walletUtxo.getTxHash()) && u.getOutputIndex() == walletUtxo.getOutputIndex())) {
                    builderUniverse.add(walletUtxo);
                }
            }

            LiquidateTransactionBuilder builder = new LiquidateTransactionBuilder(
                    LoanFixtures.registry(), LoanFixtures.NETWORK, LoanFixtures.converters(),
                    LoanFixtures.utxoSupplier(builderUniverse), protocolParams());

            BlockEventListener blockEventListener = new BlockEventListener(null);
            blockEventListener.getIsSyncing().set(false);

            LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
            PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                    LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                    new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                            LoanFixtures.utxoSupplier(builderUniverse), protocolParams()));
            LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                    new FakeAppUtxoService(walletUtxos),
                    account, new FakeScanner(List.of(scenario.assessment())),
                    new FakeResolver(unspent, loanAnswersBeforeItIsGone, bondAnswersBeforeItIsGone,
                            loanThrows),
                    builder, payInAdvanceRouter, LoanFixtures.registry(), log, metrics(),
                    provider(oracle),
                    networkNamed(networkName), params, executorConverters, submitter);
            long[] elapsed = {0};
            executor.setSubmitClock(() -> submitTime + elapsed[0]);
            if (meterRegistry != null) {
                executor.setMeterRegistry(meterRegistry);
            }

            for (int cycle = 0; cycle < cycles; cycle++) {
                elapsed[0] = cycle * 60_000L;
                executor.cycle(NOW + elapsed[0]);
            }
            return new Run(log, submitter);
        }
    }

    private record Run(LiquidationDecisionLog log, RecordingSubmitter submitter) {

        /**
         * The two decisions a two-cycle run leaves when nothing holds the candidate (FAB-134 NQ):
         * one per cycle, both with {@code outcome}. A second-cycle skip — the quarantine this
         * replaced — would leave one decision, or a second one of a different kind.
         */
        List<LiquidationDecision> attemptedOnBothCycles(LiquidationDecision.Outcome outcome) {
            List<LiquidationDecision> decisions = log.newestFirst(10);
            assertEquals(2, decisions.size(), "expected one decision per cycle: " + decisions);
            for (LiquidationDecision decision : decisions) {
                assertEquals(outcome, decision.outcome(), decision.detail());
            }
            return decisions;
        }

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
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
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
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        run.assertNothingWasSubmitted();
        assertEquals(0, run.log().size());
    }

    // ======================================================================================
    // ⛔ WHAT WAS S2 — the separate arming flag — IS GONE (2026-09-04)
    // ======================================================================================

    /**
     * ⛔ <b>THE INVERSE, and the second veto removed in one day.</b>
     *
     * <p>This asserted that {@code mode: live} alone was NOT enough: a second boolean,
     * {@code loans.liquidation.enabled}, had to agree, on the reasoning that one flag flipped by an
     * experimenting operator or a copied env file must not arm a bot.
     *
     * <p>Giovanni, 2026-09-04: <i>"it's redundant with mode — mode == disabled already IS off, so a
     * separate enabled boolean makes no sense … the gates are too much; it's a bot, if you use it you
     * know it's risky, so gates don't really help."</i>
     *
     * <p>⚠ <b>And a redundant gate is not free.</b> It gave "off" two spellings —
     * {@code mode: disabled}, and {@code mode: live} with the flag false — which log differently and
     * mean the same thing. Worse, it gave an operator who <em>had</em> decided to arm one more silent
     * way to have not done it: exactly the class of failure the network veto was removed for, one
     * level up.
     *
     * <p>⇒ <b>{@code mode: live} is now sufficient at the node level</b>, and this test says so.
     * Keep it: it is the regression guard against a second arming boolean growing back, and this
     * codebase has now grown a redundant last-step barrier back twice.
     */
    @Test
    void liveModeAloneArmsTheNodeBecauseThereIsNoSecondBooleanAnyMore() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        assertEquals(1, run.submitter().submitted.size(),
                "mode: live is the whole node-level dial now; a profitable, buildable candidate on a "
                        + "live node must reach the submitter with nothing else to set");
        LiquidationDecision decision = run.onlyDecision();
        assertNull(decision.submitVeto(), "no veto may fire: " + decision.detail());

        // ⚠ And the controls, so this cannot pass by simply arming everything. The two held modes
        // stop the node in DIFFERENT places, and asserting them identically would hide that:
        //   SHADOW   scans, builds, prices and RECORDS — the rehearsal — then vetoes MODE_NOT_LIVE.
        //   DISABLED returns before it scans anything, so there is no decision row at all.
        Run shadow = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        SMALL_MARGIN, PUBLISHED))
                .run();
        shadow.assertNothingWasSubmitted();
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(),
                shadow.onlyDecision().submitVeto(),
                "shadow must build the rehearsal and then withhold it, naming MODE_NOT_LIVE");

        Run disabled = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                        SMALL_MARGIN, PUBLISHED))
                .run();
        disabled.assertNothingWasSubmitted();
        assertTrue(disabled.log().newestFirst(10).isEmpty(),
                "disabled returns before it scans, so it records nothing — asserting a MODE_NOT_LIVE "
                        + "row here would be asserting a rehearsal that never happened");
    }

    // ======================================================================================
    // ⛔ WHAT WAS S3 — the network veto — IS GONE (2026-09-04); the test below is its
    // inverse, kept as the guard against the barrier growing back.
    // ======================================================================================

    /**
     * ⛔ <b>THE INVERSE OF WHAT THIS TEST USED TO ASSERT, and that inversion IS the change.</b>
     *
     * <p>Until 2026-09-04 a {@code NETWORK_NOT_PREVIEW} veto sat here: a fully armed node on mainnet
     * or preprod built the transaction, priced it, recorded {@code WOULD_SUBMIT} with a positive
     * profit — <b>and sent nothing</b>. The old version of this test asserted exactly that, and read
     * as a safeguard.
     *
     * <p>Giovanni's ruling, 2026-09-04: <i>"a barrier that silently blocks submission even when
     * everything else is armed is a bug, not a safeguard — arming that works everywhere except the
     * last step, silently."</i> It was defensible while this node was preview-only. The product goes
     * to mainnet for real, and a veto aimed at its own purpose had to go.
     *
     * <p>⇒ <b>So this now asserts that an armed node SUBMITS on whatever network it is pointed at.</b>
     * {@code config.network} is the single source of truth; there is no second network value that can
     * disagree with it. <b>Keep this test as the regression guard:</b> re-introducing any network
     * check inside the submit path turns it red, which is the only thing that stops the barrier
     * growing back — the old one grew back once already, as a hard-coded {@code "preview"} constant
     * before it was a config key.
     *
     * <p>⚠ Preview is asserted alongside, unchanged, so a mutant that simply inverted the comparison
     * (submit on mainnet, veto on preview) cannot pass either.
     */
    @Test
    void s3AnArmedNodeSubmitsOnWhicheverNetworkItIsPointedAt() {
        for (String networkName : List.of("mainnet", "preprod", "preview")) {
            Run run = new Rig().network(networkName).run();

            assertEquals(1, run.submitter().submitted.size(),
                    "on " + networkName + ": an armed, profitable, buildable candidate must reach "
                            + "the submitter. A node targeted at a network and then armed ACTS on "
                            + "that network — there is no second network value left to disagree");
            LiquidationDecision decision = run.onlyDecision();
            assertNull(decision.submitVeto(),
                    "on " + networkName + ": no veto may fire — got " + decision.submitVeto()
                            + " (" + decision.detail() + ")");
            assertTrue(decision.expectedProfitLovelace().signum() > 0,
                    "the candidate was worth doing on " + networkName);
        }
    }

    // ======================================================================================
    // S2 — the market's own execution state
    // ======================================================================================

    /** A configuration identical to {@link #armed()} but with one market held at {@code mode}. */
    private static AppConfig.LiquidationConfiguration armedWithMarket(
            AppConfig.LiquidationConfiguration.Mode marketMode) {
        var cfg = armed();
        var m = new AppConfig.LiquidationConfiguration.Market();
        m.setUnit(AssetType.LOVELACE);
        m.setMode(marketMode);
        m.setAction(AppConfig.LiquidationConfiguration.Action.CONVERT);
        cfg.setMarkets(java.util.List.of(m));
        return cfg;
    }

    /**
     * ⛔ <b>THE REHEARSAL. A market held at SHADOW on a fully armed LIVE node builds the whole
     * transaction, records it, dumps it — and submits nothing.</b>
     *
     * <p>This is what makes shadow a rehearsal rather than a refusal, and on mainnet it is the ONLY
     * rehearsal the convert path can ever have: preview carries no Minswap deployment, so a real
     * candidate with real protocol parameters and real ex-units exists nowhere else (findings §28.1).
     *
     * <p>Everything else here is armed — live, enabled, submittable, profitable, small enough, fresh
     * feeds, unspent UTxOs. <b>The market alone is what stops it</b>, and the row still reports the
     * candidate's own verdict, which is what makes the dump worth reading.
     */
    @Test
    void s4AMarketHeldAtShadowBuildsAndDumpsTheTransactionAndSubmitsNothing() {
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            run = new Rig().configuration(armedWithMarket(
                    AppConfig.LiquidationConfiguration.Mode.SHADOW)).run();
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.MARKET_NOT_LIVE,
                LiquidationDecision.Outcome.WOULD_SUBMIT);
        assertTrue(decision.expectedProfitLovelace().signum() > 0,
                "the candidate was worth doing — the market is the only thing that stopped it");
        assertNotNull(decision.txCborHex(), "a rehearsal that produced no transaction is not a rehearsal");

        var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        String meta = lines.stream().filter(l -> l.startsWith("SHADOW TX ")).findFirst().orElse(null);
        assertNotNull(meta, "the operator must be able to grep the dump: " + lines);
        assertTrue(meta.contains("held-by=MARKET_NOT_LIVE"), meta);
        assertTrue(meta.contains("PHASE-2 ONLY"),
                "the honesty boundary must travel WITH the artefact, not sit in a doc: " + meta);
        assertTrue(meta.contains("Nothing was signed and nothing was submitted."), meta);

        // ⛔ Ex-units read off the BUILT transaction, never off an evaluator report (CCL trap 8).
        assertTrue(meta.contains("exunits=["), meta);
        Transaction dumped = assertDoesNotThrow(
                () -> Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex())),
                "the dumped hex must round-trip, or an operator cannot analyse it");

        // ⚑ THIS RIG BUILDS WITHOUT AN EVALUATOR ON PURPOSE — its subject is the veto ladder, not
        // costing — so its redeemers carry cardano-client-lib's placeholder budget. That is exactly
        // the state a shadow dump must NOT present as a validated rehearsal, and asserting it here
        // proves the detection fires rather than merely existing.
        assertTrue(dumped.getWitnessSet().getRedeemers().stream()
                        .allMatch(r -> r.getExUnits() != null
                                && r.getExUnits().getMem().longValue() == 10_000L),
                "this rig is expected to produce placeholders; if it stopped, the assertion below is "
                        + "no longer testing what it claims");
        assertTrue(meta.contains(LiquidationExecutor.PLACEHOLDER_MARKER),
                "a dump whose budgets were never measured MUST say so — otherwise it reads as a "
                        + "validated rehearsal and is worse than no dump at all: " + meta);
        assertTrue(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(l -> l.contains("PROVES NOTHING")),
                "and it must be loud, at ERROR, not a detail inside an INFO line");

        String payload = lines.stream().filter(l -> l.startsWith("SHADOW CBOR ")).findFirst().orElse(null);
        assertNotNull(payload, "the bytes themselves must be dumped, not only described");
        assertTrue(payload.endsWith(decision.txCborHex()),
                "the dumped bytes must be the ones recorded, or the two disagree about what would go");

        run.assertNothingWasSubmitted();
    }

    /**
     * A DISABLED market is not a rehearsal: there is nothing to analyse, so since FAB-183 (A4) nothing is
     * built either. It is refused {@code MARKET_DISABLED} before the build, which spent a Blockfrost probe and
     * an evaluation only to be vetoed {@code MARKET_NOT_LIVE} afterwards.
     */
    @Test
    void s4ADisabledMarketSubmitsNothingEither() {
        Run run = new Rig().configuration(armedWithMarket(
                AppConfig.LiquidationConfiguration.Mode.DISABLED)).run();

        run.assertNothingWasSubmitted();
        LiquidationDecision decision = run.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("MARKET_DISABLED", decision.reason(), decision.detail());
        assertTrue(decision.detail().contains("refused before any build"), decision.detail());
        assertNull(decision.submitVeto(), "refused before the veto chain: " + decision.detail());
        assertNull(decision.txHash(), "no transaction was built");
        assertNull(decision.txCborHex(), "no transaction was built");
    }

    /**
     * ⚑ And the market must not gate a node that never listed it. An unlisted market inherits the node
     * mode, so an armed node with an empty list still submits — otherwise shipping this stage would
     * have silently disarmed every existing operator.
     */
    @Test
    void s4AnUnlistedMarketDoesNotHoldAnArmedNode() {
        Run run = new Rig().run();

        assertEquals(1, run.submitter().submitted.size(),
                "an empty market list must leave an armed node exactly as armed as it was");
    }

    // ======================================================================================
    // S3 — profitability
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
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
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
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
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
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
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

    // ======================================================================================
    // Operating at a loss on purpose — Giovanni's 2026-08-27 ruling
    // ======================================================================================

    /**
     * A loss-tolerant configuration: both floors stated below the loss the fixture makes. The only
     * constructor that can express this is the one that names {@code minExpectedProfitLovelace}.
     */
    private static AppConfig.LiquidationConfiguration lossTolerant(BigInteger absoluteFloor,
                                                                  BigInteger expectedFloor) {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30,
                BigInteger.ZERO, 200, true, absoluteFloor, expectedFloor, PUBLISHED);
    }

    /** Far below anything this fixture can lose, so the floor is never the binding constraint. */
    private static final BigInteger TOLERATED_LOSS = BigInteger.valueOf(-50_000_000L);

    /**
     * The shipped default still refuses a loss. This is the control for the two tests below: without
     * it, "a negative floor lets a loss through" would be equally consistent with the gate having been
     * removed altogether.
     */
    @Test
    void theShippedFloorsStillRefuseALossMakingLiquidation() {
        Run run = new Rig()
                .scenario(lossMakingTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
                        BigInteger.ZERO, PUBLISHED))
                .run();

        vetoed(run, LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);
    }

    /**
     * Both floors stated negative: the same loss is no longer refused as unprofitable.
     *
     * <p>The assertion is that {@code NOT_PROFITABLE} is gone, not that the transaction submits — the
     * rest of the veto chain is unchanged and is not what this ruling touched. Asserting a submission
     * here would make the test fail for reasons that have nothing to do with the floors.
     */
    @Test
    void bothFloorsStatedNegativeLetTheSameLossThrough() {
        Run run = new Rig()
                .scenario(lossMakingTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(lossTolerant(TOLERATED_LOSS, TOLERATED_LOSS))
                .run();

        LiquidationDecision decision = run.onlyDecision();
        assertNotEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), decision.submitVeto(),
                "both floors are stated below this fixture's loss, so profitability must no longer be "
                        + "the veto: " + decision.detail());
    }

    /**
     * Moving only the margin-adjusted floor changes nothing, because the absolute floor tests a
     * different number and refuses independently.
     *
     * <p>This is not an incidental case. {@code LiquidationExecutor.announceLossTolerance()} tells an
     * operator at boot that <b>both</b> floors must be moved, and that warning is a claim about
     * behaviour. If this test ever goes green the log is lying, which is worse than the log being
     * absent.
     */
    @Test
    void movingOnlyTheMarginAdjustedFloorStillRefusesTheLoss() {
        Run run = new Rig()
                .scenario(lossMakingTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(lossTolerant(BigInteger.ZERO, TOLERATED_LOSS))
                .run();

        vetoed(run, LiquidationExecutor.SubmitVeto.NOT_PROFITABLE,
                LiquidationDecision.Outcome.UNPROFITABLE);
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
    // ⛔ RED SINCE 2026-08-25, DELIBERATELY, AND THE REASON IS NOT A DEFECT IN THIS TEST.
    //
    // It configures PUBLISHED reference scripts and asserts that the min-ada rider ALONE turns this
    // candidate into a loss. Its numbers were calibrated against a transaction that carried ~18.7 KB
    // of REDUNDANT WITNESSES: LiquidateTransactionBuilder attached every validator even when it also
    // read it as a reference input, because removeDuplicateScriptWitnesses(true) sat behind
    // `backendService == null` and so never ran in production. Fixing that removed the duplicate
    // scripts, which lowered the fee, which lifted floorProfit above zero -- so the candidate now
    // clears the profit gate and reaches the submitter, and this assertion fails.
    //
    // The property is still worth guarding; only the scenario's arithmetic has gone stale. It is NOT
    // recalibrated here on purpose: re-tuning a scenario so a test keeps passing after a behaviour
    // change is the shape of adjusting a test until it is green, and choosing the new numbers is a
    // design decision that should not ride along in a production fix.
    //
    // ⚠ WHEN RECALIBRATING, THE PROPERTY-PRESERVING CHECK IS: the test must STILL FAIL if the
    // min-ada rider is removed from the scenario. If it passes with the rider gone, it has been
    // tuned into passing rather than into testing.
    void aTokenLiquidationRefusedSolelyByTheMinAdaRider() {
        Run run = new Rig()
                .scenario(minAdaDrivenLossTokenScenario())
                .oracle(new FakeOracleClient(List.of(collateralOracle())))
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
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
     * ⛔ <b>A negative margin is HONOURED on mainnet, and says so loudly.</b>
     *
     * <p>This test was the exact inverse until 2026-09-03: it asserted a hard startup failure. Giovanni
     * reversed it first-hand — <i>"it's fundamental to allow operators to operate at a loss. Protocol
     * must be kept bad-loss-free at all costs. So you can expect our bot to be used by FluidTeam to
     * clean up loans non-profitable for other operators but still need cleanup … operating at a loss
     * MUST be implemented even on mainnet."</i>
     *
     * <p><b>The mutant this guards is the old hard-fail reappearing</b> — a refusal to start would take
     * out exactly the protocol-health operator the feature exists for, and it would do it at boot, on
     * mainnet, where nobody is watching a preview log.
     *
     * <p>⚑ And what still protects an ordinary operator is the DEFAULT (1_500_000), not this guard:
     * only an explicitly negative value gets here, which no copy-paste of a zero or positive config can
     * produce. So the loud line is a <b>record of a deliberate mode</b>, not a warning about a mistake.
     */
    @Test
    void aNegativeMarginIsHonouredOnMainnetAndAnnouncedLoudly() {
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run mainnet;
        try {
            mainnet = new Rig()
                    .network("mainnet")
                    .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                            NEGATIVE_MARGIN, PUBLISHED))
                    .run();
        } finally {
            logger.detachAppender(appender);
        }

        assertNotNull(mainnet.onlyDecision(),
                "the node must COME UP and run a cycle: refusing to start would disable the "
                        + "protocol-health cleanup this setting exists to enable");

        String announcement = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("OPERATING AT A LOSS ON MAINNET"))
                .findFirst()
                .orElse(null);
        assertNotNull(announcement, "a mainnet node configured to operate at a loss must say so "
                + "auditably at boot; the WARN lines seen were: "
                + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertTrue(announcement.contains("liquidation"), "it must name the PATH: " + announcement);
        assertTrue(announcement.contains(NEGATIVE_MARGIN.toString()),
                "it must name the STATED FLOOR: " + announcement);

        // Preview is unchanged and still runs.
        Run preview = new Rig()
                .network("preview")
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        NEGATIVE_MARGIN, PUBLISHED))
                .run();
        assertNotNull(preview.onlyDecision());
    }

    // ======================================================================================
    // S4 — the size, against the live protocol parameters
    // ======================================================================================

    /**
     * No reference scripts published, so all six validators travel in the witness set: 19_838 bytes
     * against a 16_384-byte maxTxSize. The candidate is handsomely profitable, which is the point —
     * S4 is not S3's arithmetic wearing a different name.
     */
    @Test
    void s5ATransactionThatCannotBeShownToFitSubmitsNothing() {
        // Oversized: no reference scripts published, so all six validators travel in the witness set.
        Run oversized = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .run();

        LiquidationDecision decision = vetoed(oversized, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(decision.txSizeBytes() > 16_384,
                "the fixture must actually be oversized: " + decision.txSizeBytes());
        assertTrue(decision.expectedProfitLovelace().signum() > 0,
                "and profitable, so S4 cannot be S3 in disguise");

        // Protocol parameters that cannot be fetched. Not knowing the limit is not being under it.
        // FAB-185: the cycle-start read (which sizes C) is answered; the S4 read is the one that fails.
        Run unfetchable = new Rig().params(firstAnswerCompleteThen(unfetchableProtocolParams()))
                .wallet(shaped(WALLET_UTXO)).run();
        LiquidationDecision timedOut = vetoed(unfetchable, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertTrue(timedOut.detail().contains("maxTxSize could not be fetched"), timedOut.detail());

        // Parameters that arrive without the one field this veto reads. Same answer.
        Run noLimit = new Rig().params(firstAnswerCompleteThen(protocolParamsWithoutMaxTxSize()))
                .wallet(shaped(WALLET_UTXO)).run();
        vetoed(noLimit, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
    }

    /**
     * The size veto reads the live parameter rather than a constant. Here maxTxSize is raised above
     * the 19_838-byte transaction, and the same candidate that S4 refused above goes out — so the
     * check cannot be a hard-coded 16384.
     */
    @Test
    void s5ReadsTheLiveMaxTxSizeRatherThanAConstant() {
        ProtocolParams generous = protocolParams().getProtocolParams();
        generous.setMaxTxSize(1_000_000);

        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.LIVE,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .params(() -> generous)
                .run();

        assertEquals(1, run.submitter().submitted.size(),
                "with a large enough maxTxSize the very transaction S4 refused must go out");
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, run.onlyDecision().outcome());
    }

    // ======================================================================================
    // S5 — the oracle window at submit time
    // ======================================================================================

    /**
     * The transaction built happily: at cycle time the feed had 600 s of window ahead of it, far more
     * than the builder's V3 needed. By the time the vetoes ran — after the resolves, the parameter
     * fetch and the script evaluation, which are Blockfrost round trips — the clock had moved past
     * the point where the configured 30 s margin still fits.
     * <p>
     * This is why S5 reads a submit-time clock and not the cycle's {@code now}: against the cycle's
     * own instant it could never say anything the builder had not already said.
     */
    @Test
    void s6AFeedThatCannotBeShownFreshAtSubmitTimeSubmitsNothing() {
        // First, the case that does not depend on the clock: no registry client at all. An absent
        // registry is not a fresh one — even on an ada/ada loan, which consults no feed. Being unable
        // to check is failing the check.
        //
        // It is deliberately first. The two clock-driven cases below can only fire at instants where
        // S7 would also fire (see SubmitVeto.TRANSACTION_WINDOW_ELAPSED), so on their own they would
        // let a "delete S5" mutation be caught by S7 and reported as a name mismatch rather than as
        // a submission. This case fires at NOW, well inside the transaction's window, so deleting S5
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
    // S6 — the UTxOs, re-read immediately before the wire
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
    // T-040 — the three catches that logged NOTHING
    //
    // S4's maxTxSize fetch, S7's slot-to-time conversion and S6's UTxO re-check each swallowed their
    // exception into a veto detail with no log line at all, so an operator saw a refusal and had no
    // way to find out why. Each test below drives a WRAPPED fault and asserts on the ROOT cause's
    // message: a cause-less fixture would pass under the very e.toString() these fixes replace, which
    // is how the same hole was dug on the build paths and the outer net.
    // ======================================================================================

    /** S4: the protocol-parameter fetch throws. The veto is unchanged; the ERROR line is new. */
    @Test
    void s5AnUnfetchableMaxTxSizeIsLoggedAtErrorWithTheRootCause() {
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            // FAB-185: the cycle-start read (which sizes C) is answered; the S4 read is the one that fails.
            run = new Rig().params(firstAnswerCompleteThen(unfetchableProtocolParams()))
                    .wallet(shaped(WALLET_UTXO)).run();
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.TX_TOO_LARGE,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertEquals(0, run.submitter().submitted.size(), "nothing reached the wire");
        assertTrue(decision.detail().contains("maxTxSize could not be fetched"),
                "the veto detail is unchanged in shape: " + decision.detail());
        // THE DISCRIMINATOR: toString() on the wrapper stops one link short of this.
        assertTrue(decision.detail().contains(PARAMS_ROOT_CAUSE),
                "the detail must carry the ROOT cause, not just the wrapper: " + decision.detail());

        ILoggingEvent event = onlyError(appender);
        assertTrue(event.getFormattedMessage().contains("maxTxSize"),
                "must name the operation: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains(PARAMS_ROOT_CAUSE),
                "must surface the ROOT cause: " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "the exception must be attached for the stack trace");
    }

    /** S6: the UTxO re-check throws. Still treated as "not shown unspent"; now it says why. */
    @Test
    void s7AThrowingUtxoRecheckIsLoggedAtErrorWithTheRootCause() {
        RuntimeException boom = new IllegalStateException("the local index is not readable",
                new java.io.IOException("index segment 000042 is corrupt"));

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            run = new Rig().loanRecheckThrows(boom).run();
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = vetoed(run, LiquidationExecutor.SubmitVeto.STALE_UTXO,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertEquals(0, run.submitter().submitted.size(), "nothing reached the wire");
        assertTrue(decision.detail().contains("the utxo re-check threw"),
                "the veto detail is unchanged in shape: " + decision.detail());
        assertTrue(decision.detail().contains("index segment 000042 is corrupt"),
                "the detail must carry the ROOT cause, not just the wrapper: " + decision.detail());

        ILoggingEvent event = onlyError(appender);
        assertTrue(event.getFormattedMessage().contains("utxo re-check"),
                "must name the operation: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("index segment 000042 is corrupt"),
                "must surface the ROOT cause: " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "the exception must be attached for the stack trace");
    }

    /**
     * S7: the slot-to-time conversion throws. Only the EXECUTOR's converters are replaced — the
     * builders keep their real one, so the transaction under test is a genuinely built one and the
     * failure is isolated to the single line the executor uses converters for.
     */
    @Test
    void s8AnUnconvertibleSlotIsLoggedAtErrorWithTheRootCause() {
        RuntimeException boom = new IllegalStateException("slot conversion failed",
                new ArithmeticException("slot 133742000 predates the shelley era start"));
        CardanoConverters broken = mock(CardanoConverters.class, RETURNS_DEEP_STUBS);
        when(broken.slot().slotToTime(anyLong())).thenThrow(boom);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Run run;
        try {
            run = new Rig().executorConverters(broken).run();
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = vetoed(run,
                LiquidationExecutor.SubmitVeto.TRANSACTION_WINDOW_ELAPSED,
                LiquidationDecision.Outcome.SUBMIT_VETOED);
        assertEquals(0, run.submitter().submitted.size(), "nothing reached the wire");
        assertTrue(decision.detail().contains("could not be converted to a time"),
                "the veto detail is unchanged in shape: " + decision.detail());
        assertTrue(decision.detail().contains("predates the shelley era start"),
                "the detail must carry the ROOT cause, not just the wrapper: " + decision.detail());

        ILoggingEvent event = onlyError(appender);
        assertTrue(event.getFormattedMessage().contains("could not convert slot"),
                "must name the operation: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("predates the shelley era start"),
                "must surface the ROOT cause: " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "the exception must be attached for the stack trace");
    }

    /**
     * FAB-183-2 audit residue (R2). The S8 shape again — the executor's converter throws — read for the window
     * START this time. The start clamp calls that converter, fails, and must fall back to the per-cycle floor
     * {@code anchor − 30 s} (the committed slot is unknown here, so the anchor is {@code NOW}): never later
     * than the anchor, which would open the transaction ahead of the chain. Read off the BUILT body, whose
     * builder converts with its own real converters.
     */
    @Test
    void s8AClampWhoseConverterThrowsHandsTheRouteThePerCycleFloor() {
        CardanoConverters broken = mock(CardanoConverters.class, RETURNS_DEEP_STUBS);
        when(broken.slot().slotToTime(anyLong())).thenThrow(new IllegalStateException("slot conversion failed"));

        Run run = new Rig().executorConverters(broken).run();

        LiquidationDecision decision = run.onlyDecision();
        assertNotNull(decision.txCborHex(), "the candidate was built from the fallback start: " + decision.detail());
        Transaction built = assertDoesNotThrow(
                () -> Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex())));
        long startMillis = LoanFixtures.converters().slot()
                .slotToTime(built.getBody().getValidityStartInterval())
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        long anchor = NOW;
        assertEquals(anchor - 30_000L, startMillis, "the per-cycle floor, anchor − 30 s");
        assertTrue(startMillis <= anchor, "never later than the anchor");
    }

    /**
     * The single cause-carrying ERROR event these three sites must each produce — exactly one, never
     * zero or two. Cause-carrying because the veto's own summary line is ERROR too since FAB-134 NQ
     * (an armed node's S4–S7 veto is a failure, loud every cycle), and it carries no throwable.
     */
    private static ILoggingEvent onlyError(ListAppender<ILoggingEvent> appender) {
        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getThrowableProxy() != null)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one cause-carrying ERROR event: " + appender.list);
        return errors.getFirst();
    }

    // ======================================================================================
    // S7 — the transaction's own validity window
    // ======================================================================================

    /**
     * The gap S5 cannot cover. This is an ada/ada loan: it has no oracle feed at all, so before S7
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
                // S5 to have had an opinion about.
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
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded("ValueNotConservedUTxO");
        Run run = new Rig().submitter(submitter).run();

        assertEquals(1, submitter.submitted.size(), "it was transmitted");
        LiquidationDecision decision = run.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, decision.outcome());
        assertTrue(decision.detail().contains("ValueNotConservedUTxO"), decision.detail());
    }

    /** Drives the run with the executor's log captured, and returns every event it logged. */
    private static List<ILoggingEvent> logged(Rig rig, Run[] run) {
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            run[0] = rig.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private static List<ILoggingEvent> errorsContaining(List<ILoggingEvent> events, String text) {
        return events.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
    }

    /** The node's text for a transaction whose interval opens after the validating node's slot. */
    private static final String OUTSIDE_VALIDITY_RESPONSE = "{\"contents\":{\"contents\":{\"contents\":{\"era\":"
            + "\"ShelleyBasedEraConway\",\"error\":[\"ConwayUtxowFailure (UtxoFailure (OutsideValidity"
            + "IntervalUTxO (ValidityInterval {invalidBefore = SJust (SlotNo 121127975), invalidHereafter = "
            + "SJust (SlotNo 121128124)}) (SlotNo 121127848)))\"]}}}}";

    /**
     * FAB-183 (A2): an {@code OutsideValidityInterval} rejection is EXPECTED once the start clamp opens a
     * transaction at a fresh feed's {@code validFrom} — the validating node may not have reached that slot
     * yet. Logged at INFO, never ERROR, and counted; the row is the ordinary {@code SUBMIT_FAILED} with the
     * backend's text, and nothing was transmitted. FAB-183 (A4): the refusal arrives as Blockfrost's decoded
     * envelope, inside which a line break would be the two characters {@code \n}, so the fixture's marker is
     * unbroken — as real ledger text always is.
     */
    @Test
    void anOutsideValidityIntervalRejectionIsCountedAndLoggedAtInfoNotError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded(OUTSIDE_VALIDITY_RESPONSE);
        Run[] run = new Run[1];

        List<ILoggingEvent> events = logged(new Rig().submitter(submitter).meterRegistry(registry), run);

        assertEquals(1, submitter.submitted.size(), "it reached the backend");
        LiquidationDecision decision = run[0].onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains("backend rejected it: "
                        + RecordingSubmitter.decodedRefusal(OUTSIDE_VALIDITY_RESPONSE).getResponse()),
                decision.detail());
        assertEquals(List.of(), errorsContaining(events, "rejected"), "an expected rejection is not a fault");
        assertEquals(List.of(), errorsContaining(events, "OutsideValidity"), "nor at ERROR under any wording");
        assertEquals(1, events.stream()
                        .filter(event -> event.getLevel() == Level.INFO)
                        .filter(event -> event.getFormattedMessage().contains("rejected OutsideValidityInterval"))
                        .filter(event -> event.getFormattedMessage().contains("retried on the next block"))
                        .count(),
                "one INFO line naming it: " + events);
        assertEquals(1.0, registry.get(LiquidationExecutor.OUTSIDE_VALIDITY_COUNTER).counter().count(),
                "counted once");
    }

    /** And every other rejection keeps its ERROR line and is not counted as one. */
    @Test
    void anOrdinaryRejectionIsStillLoggedAtErrorAndNotCountedAsOutsideValidity() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded("ValueNotConservedUTxO");
        Run[] run = new Run[1];

        List<ILoggingEvent> events = logged(new Rig().submitter(submitter).meterRegistry(registry), run);

        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, run[0].onlyDecision().outcome());
        assertEquals(1, errorsContaining(events, "was rejected: "
                        + RecordingSubmitter.decodedRefusal("ValueNotConservedUTxO").getResponse()).size(),
                "an ordinary rejection is a fault, at ERROR: " + events);
        assertEquals(0.0, registry.get(LiquidationExecutor.OUTSIDE_VALIDITY_COUNTER).counter().count(),
                "not an OutsideValidityInterval rejection");
    }

    /**
     * A backend that throws. Same conclusion: the attempt happened, so it is not a veto.
     * <p>
     * FAB-134 NQ (Giovanni, 2026-10-03: "no quarantine ... logging the issue again and again is the
     * expected 'loud' result"): this used to hold the loan for thirty minutes so the next cycle could
     * not resubmit. Nothing holds it now. The next cycle rebuilds and resubmits; if the first one
     * landed, that resubmission fails in phase 1 — free — and is logged like any failure.
     */
    @Test
    void aThrowingSubmissionIsRetriedOnTheNextCycleAndLoggedAtErrorOnBoth() {
        RecordingSubmitter submitter = RecordingSubmitter.throwing(
                new IllegalStateException("connection reset"));
        Run[] run = new Run[1];
        List<ILoggingEvent> events = logged(new Rig().submitter(submitter).cycles(2), run);

        run[0].attemptedOnBothCycles(LiquidationDecision.Outcome.SUBMIT_FAILED);
        assertEquals(2, submitter.submitted.size(), "the second cycle submitted again — nothing held it");
        assertEquals(2, errorsContaining(events, "submitting the liquidation of").size(),
                "the failure is logged at ERROR on BOTH cycles: " + events);
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
        // WRAPPED deliberately, and the assertions below key on the ROOT cause's message. A cause-less
        // exception would make this test vacuous: causeChain(e) is then a substring of e.toString(), so
        // every assertion would pass under the very toString() this ticket exists to replace (audit
        // finding, 2026-08-21). The real shape is a wrapper around a transport fault.
        RuntimeException boom = new IllegalStateException("submit failed",
                new java.net.SocketTimeoutException("connect timed out"));
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
        // The ROOT cause's message is the discriminating assertion: toString() on the wrapper never
        // contains it, so this can only pass if the chain was genuinely walked.
        assertTrue(run.onlyDecision().detail().contains("connect timed out"),
                "the decision detail must carry the ROOT cause, not just the wrapper: "
                        + run.onlyDecision().detail());
        assertTrue(run.onlyDecision().detail().contains(LiquidationExecutor.causeChain(boom)),
                "the decision detail should also carry the cause chain: " + run.onlyDecision().detail());

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("submitting the liquidation"))
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event for the submit-threw path: "
                + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains("connect timed out"),
                "must surface the ROOT cause, not just toString() of the wrapper: "
                        + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains(LiquidationExecutor.causeChain(boom)),
                "must surface the real cause chain, not just toString(): " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "the exception itself must be attached for the stack trace, not just the message");
    }

    /**
     * T-037: the sibling swallow, on the sign path. Before the fix this catch was also
     * {@code log.warn(..., e.toString())}. Driven with a spy of {@code ACCOUNT} whose {@code sign} is
     * stubbed to throw — this is the machinery failing after all seven vetoes already said yes, so
     * nothing here is one of them: the recorded outcome stays {@code SUBMIT_VETOED} with no veto name,
     * exactly as before the fix, and nothing reaches the wire.
     */
    @Test
    void aFailedSignIsLoggedAtErrorWithTheCauseAndStillRecordsSubmitVetoed() {
        // WRAPPED deliberately — see the sibling submit-threw test: a cause-less exception makes
        // causeChain(e) a substring of e.toString(), so the assertions would pass under the toString()
        // this ticket replaces. The root cause's message below is what discriminates.
        RuntimeException boom = new IllegalStateException("hsm unavailable",
                new java.security.KeyException("signing key rejected"));
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
                "not one of the seven vetoes, so none is named — unchanged by the fix");
        assertTrue(decision.detail().contains("signing key rejected"),
                "the decision detail must carry the ROOT cause, not just the wrapper: " + decision.detail());
        assertTrue(decision.detail().contains(LiquidationExecutor.causeChain(boom)),
                "the decision detail should also carry the cause chain: " + decision.detail());

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("could not sign the liquidation"))
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event for the sign-failure path: "
                + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains("signing key rejected"),
                "must surface the ROOT cause, not just toString() of the wrapper: "
                        + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains(LiquidationExecutor.causeChain(boom)),
                "must surface the real cause chain, not just toString(): " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "the exception itself must be attached for the stack trace, not just the message");
    }

    /**
     * The same property for a cleanly rejected submission — which used to be held too, although a
     * rejected transaction can never land. It is retried next cycle, and the rejection, which was
     * logged at WARN, is ERROR on every cycle.
     */
    @Test
    void aRejectedSubmissionIsRetriedOnTheNextCycleAndLoggedAtErrorOnBoth() {
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded("ValueNotConservedUTxO");
        Run[] run = new Run[1];
        List<ILoggingEvent> events = logged(new Rig().submitter(submitter).cycles(2), run);

        run[0].attemptedOnBothCycles(LiquidationDecision.Outcome.SUBMIT_FAILED);
        assertEquals(2, submitter.submitted.size(), "a rejected submission is retried on the next cycle");
        List<ILoggingEvent> errors = errorsContaining(events, "was rejected");
        assertEquals(2, errors.size(), "ERROR on each cycle, never WARN: " + events);
        assertTrue(errors.getLast().getFormattedMessage().contains("ValueNotConservedUTxO"),
                errors.getLast().getFormattedMessage());
    }

    /**
     * An armed node's S4–S7 veto is a candidate the bot priced as worth doing and is NOT submitting:
     * not configuration (S1/S2), not economics (S3) — a failure, so it is ERROR on every cycle. S7
     * here, because its own path logs nothing at ERROR: the summary line is the only loud one.
     */
    @Test
    void anArmedNodesVetoIsLoggedAtErrorOnEveryCycle() {
        Run[] run = new Run[1];
        List<ILoggingEvent> events = logged(new Rig().submitAt(TX_VALID_TO + 80_000L).cycles(2), run);

        run[0].attemptedOnBothCycles(LiquidationDecision.Outcome.SUBMIT_VETOED);
        run[0].assertNothingWasSubmitted();
        assertEquals(2, errorsContaining(events, "TRANSACTION_WINDOW_ELAPSED").size(),
                "the veto is loud on each cycle: " + events);
    }

    // ======================================================================================
    // no hold after a submission, and the cycle's wallet list
    // ======================================================================================

    /** An executor wired for {@code scenarios} on an armed node, with {@code walletUtxos} to spend. */
    private static LiquidationExecutor armedExecutor(List<Scenario> scenarios, List<Utxo> walletUtxos,
                                                     LiquidationDecisionLog log,
                                                     RecordingSubmitter submitter) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO));
        universe.addAll(walletUtxos);
        Map<String, Utxo> unspent = new LinkedHashMap<>();
        List<LiquidationAssessment> assessments = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
            unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
            unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());
            assessments.add(scenario.assessment());
        }
        AppConfig.LiquidationConfiguration configuration = armed();
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), protocolParams()));
        return new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT, new FakeScanner(assessments),
                FakeResolver.stable(unspent),
                new LiquidateTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                        protocolParams()),
                payInAdvanceRouter, LoanFixtures.registry(), log, metrics(),
                provider(new FakeOracleClient(List.of())),
                networkNamed("preview"), protocolParams(),
                LoanFixtures.converters(), submitter);
    }

    /**
     * FAB-134 NQ, Option B. A SUBMITTED liquidation used to hold its loan utxo for thirty minutes, so
     * that the next cycle — reading an index that has not seen the spend yet — could not submit a
     * second transaction against it. Nothing holds it now: the next cycle attempts it again, the
     * backend rejects the resubmission (phase 1, free: the loan utxo is gone), and that rejection is
     * logged at ERROR like any other failure. Accepted by Giovanni's ruling, and preferred to a timer.
     */
    @Test
    void aSubmittedLiquidationIsAttemptedAgainNextCycleAndItsRejectionIsLoggedAtError() {
        RecordingSubmitter submitter = RecordingSubmitter.acceptingThenRejectingDecoded("ab".repeat(32),
                "ConwayMempoolFailure \"All inputs are spent. Transaction has probably already been "
                        + "included\"");
        AppConfig.LiquidationConfiguration configuration = armed();
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        LiquidationExecutor executor = armedExecutor(List.of(adaScenario()), shaped(WALLET_UTXO), log,
                submitter);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.setSubmitClock(() -> NOW);
            executor.cycle(NOW);
            assertEquals(1, submitter.submitted.size());
            assertEquals(LiquidationDecision.Outcome.SUBMITTED, log.newestFirst(1).getFirst().outcome());

            // The chain has not caught up: the loan utxo is still unspent as far as this node can see.
            executor.setSubmitClock(() -> NOW + 60_000L);
            executor.cycle(NOW + 60_000L);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(2, submitter.submitted.size(), "the next cycle attempted the candidate again");
        assertEquals(2, log.size());
        LiquidationDecision second = log.newestFirst(1).getFirst();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, second.outcome(), second.detail());
        assertTrue(second.detail().contains("All inputs are spent"), second.detail());
        assertEquals(1, errorsContaining(appender.list, "was rejected").size(),
                "the resubmission's rejection is loud: " + appender.list);
    }

    private static final String TX_LOAN_2 = "ab".repeat(32);
    private static final String TX_BOND_2 = "de".repeat(32);
    private static final String LOAN_ID_2 = "b1b2c3d4e5f6a1b2";

    /** A second ada-only wallet utxo, smaller than {@link #WALLET_UTXO} — so never U beside it — and still ample. */
    private static final Utxo WALLET_UTXO_SMALL = LoanFixtures.adaUtxo("e3".repeat(32), 0,
            ACCOUNT.baseAddress(), 20_000_000L);

    /** {@link #adaScenario()} again, under other refs and another loan id. */
    private static Scenario secondAdaScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_2, 0, LOAN_ID_2, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_2, 0, LOAN_ID_2,
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM));
    }

    private static List<TransactionInput> inputsOf(byte[] signed) throws Exception {
        return Transaction.deserialize(signed).getBody().getInputs();
    }

    /**
     * Two ada candidates in one armed cycle against {@code submitter}, on a wallet of C, U = {@link #WALLET_UTXO}
     * and the smaller {@link #WALLET_UTXO_SMALL}; returns the log events.
     */
    private static List<ILoggingEvent> twoArmedCandidates(RecordingSubmitter submitter) {
        AppConfig.LiquidationConfiguration configuration = armed();
        LiquidationExecutor executor = armedExecutor(List.of(adaScenario(), secondAdaScenario()),
                shaped(WALLET_UTXO, WALLET_UTXO_SMALL), new LiquidationDecisionLog(configuration), submitter);
        executor.setSubmitClock(() -> NOW);
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    /**
     * FAB-134 NQ r2 (THROWN). A submission that THROWS may have gone out — a connection reset arrives
     * after the bytes as readily as before — so its inputs are treated as spent exactly like a
     * SUBMITTED one's.
     * <p>
     * FAB-183: and its outcome is AMBIGUOUS, so the stage stops there. The second candidate is never
     * built, so no wallet input of the first can be nominated again in this call.
     */
    @Test
    void aThrownSubmissionsWalletInputIsNotNominatedAgainInTheSameCycle() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.throwing(new IllegalStateException("connection reset"));

        StageRun stage = stage(armed(), List.of(adaScenario(), secondAdaScenario()), submitter, null,
                SubmitFence.OPEN);

        assertEquals(1, submitter.submitted.size(), "one transmission, then the stage stopped");
        assertTrue(inputsOf(submitter.submitted.get(0)).contains(new TransactionInput(TX_WALLET, 0)),
                "the first candidate is funded from U (FAB-185)");
        assertEquals(1, stage.log().size(), "the second candidate was never built: " + stage.log().newestFirst(10));
        assertEquals(WorkResult.AMBIGUOUS, stage.result());
    }

    /**
     * FAB-134 NQ r2 (REJ), under FAB-185's role rules. A DEFINITE rejection transmitted nothing, so its
     * inputs are not marked spent — unless the ledger says one of them already is. A {@code BadInputsUTxO}
     * rejection marks U used for the rest of the check, says so at WARN, and the second candidate — also
     * designated to U — is skipped unbuilt rather than handed the smaller output beside it.
     */
    @Test
    void aBadInputsRejectionMarksUUsedAndTheSecondCandidateIsSkipped() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded(
                "ConwayUtxowFailure (UtxoFailure (BadInputsUTxO (fromList [TxIn ...])))");

        List<ILoggingEvent> events = twoArmedCandidates(submitter);

        assertEquals(1, submitter.submitted.size(), "only the first candidate reached the wire: " + events);
        assertTrue(inputsOf(submitter.submitted.get(0)).contains(new TransactionInput(TX_WALLET, 0)),
                "the first candidate is funded from U");
        assertEquals(1, events.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .filter(event -> event.getFormattedMessage().equals("dropped wallet utxo "
                                + TX_WALLET + "#0 from this cycle: BadInputsUTxO"))
                        .count(),
                "the drop is logged at WARN naming the utxo and the marker: " + events);
        assertTrue(skippedOnUsedOutput(events, TX_LOAN_2 + "#0", TX_WALLET + "#0", "U"),
                "the second candidate is skipped naming U: " + events);
    }

    /** And an ordinary rejection keeps U: nothing said it was spent, and nothing went out. */
    @Test
    void anOrdinaryRejectionLeavesUUnused() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded("ValueNotConservedUTxO");

        List<ILoggingEvent> events = twoArmedCandidates(submitter);

        assertEquals(2, submitter.submitted.size(), "both candidates reached the wire: " + events);
        assertTrue(inputsOf(submitter.submitted.get(1)).contains(new TransactionInput(TX_WALLET, 0)),
                "a rejection that names no spent input must not cost the check its U");
    }

    /** The INFO line a candidate designated to an output that already went out in this check is skipped with. */
    private static boolean skippedOnUsedOutput(List<ILoggingEvent> events, String loanRef, String outputRef,
                                               String role) {
        return events.stream().anyMatch(event -> event.getLevel() == Level.INFO
                && event.getFormattedMessage().equals("liquidation of " + loanRef + " skipped: wallet output "
                + outputRef + " (role " + role + ") already went out in this check"));
    }

    // ======================================================================================
    // FAB-183 — the liquidation stage: one per market, the next candidate on a definite failure,
    // a stop on ambiguity, and the fence
    // ======================================================================================

    private static final String TX_LOAN_3 = "ac".repeat(32);
    private static final String TX_BOND_3 = "df".repeat(32);
    private static final String LOAN_ID_3 = "c1b2c3d4e5f6a1b2";

    /**
     * {@link #tokenPrincipalScenario()} under other refs and another loan id: a candidate in a second
     * MARKET (principal {@code PRINCIPAL_TOKEN}) beside the ada one. Needs {@link #principalOracle()}.
     */
    private static Scenario otherMarketScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000),
                LoanFixtures.adaCollateral(), LATE_LEND_DATE, LoanFixtures.liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_3, 0, LOAN_ID_3, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_3, 0, LOAN_ID_3,
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                principalFeed(), OraclePriceFeed.unit(), VALID_FROM));
    }

    private static final String TX_LOAN_4 = "ad".repeat(32);
    private static final String TX_BOND_4 = "de".repeat(32);
    private static final String LOAN_ID_4 = "d1b2c3d4e5f6a1b2";
    private static final String TX_TOKEN_WALLET = "e7".repeat(32);

    /**
     * FAB-185 r2: a TOKEN-PRINCIPAL PAY-IN-ADVANCE candidate in a second market (principal
     * {@code PRINCIPAL_TOKEN}), funded from its own TOKEN output — never from U — so it can follow a plain
     * candidate that went out on U in the same call. 1,000,000 TOK of collateral at 50 lovelace (50 ADA)
     * against 100,000,000 PRI of debt at 1 lovelace (100 ADA): under water, equity zero. The bond converts
     * to principal at the fat 500 per mille, so the bot fronts the lender's half of the collateral in PRI
     * and acquires all of it — comfortably profitable, so its verdict is the market's, not S3's. Needs
     * {@link #principalOracle()} and {@link #collateralOracle()}, and {@link #payInAdvanceMarket} to route it.
     */
    private static Scenario tokenPrincipalPayInAdvanceScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_4, 0, LOAN_ID_4, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_4, 0, LOAN_ID_4,
                LoanFixtures.convertToPrincipalBondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), PRINCIPAL_TOKEN),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                principalFeed(), collateralFeed(), VALID_FROM));
    }

    /** The pay-in-advance candidate's TOKEN output: ample PRI for the lender payout, and ada for its floor. */
    private static final Utxo TOKEN_WALLET_UTXO = LoanFixtures.utxo(TX_TOKEN_WALLET, 0, ACCOUNT.baseAddress(),
            List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(PRINCIPAL_TOKEN.toUnit(),
                            BigInteger.valueOf(100_000_000L))),
            null);

    /** {@link #armed()} with the pay-in-advance action published too, so its body fits under maxTxSize. */
    private static AppConfig.LiquidationConfiguration armedForPayInAdvance(
            AppConfig.LiquidationConfiguration.Mode payInAdvanceMarketMode) {
        var cfg = configuration(AppConfig.LiquidationConfiguration.Mode.LIVE, SMALL_MARGIN,
                new LiquidateTransactionBuilder.ReferenceScripts(
                        new TransactionInput(TX_REF_SCRIPTS, 0),
                        new TransactionInput(TX_REF_SCRIPTS, 1),
                        new TransactionInput(TX_REF_SCRIPTS, 2),
                        new TransactionInput(TX_REF_SCRIPTS, 3),
                        new TransactionInput(TX_REF_SCRIPTS, 4),
                        new TransactionInput(TX_REF_SCRIPTS, 5),
                        null,
                        new TransactionInput(TX_REF_SCRIPTS, 6),
                        null));
        cfg.setMarkets(List.of(payInAdvanceMarket(payInAdvanceMarketMode)));
        return cfg;
    }

    /** The {@code PRINCIPAL_TOKEN} market: ANTICIPATE, capped well above the payout, at {@code mode}. */
    private static AppConfig.LiquidationConfiguration.Market payInAdvanceMarket(
            AppConfig.LiquidationConfiguration.Mode mode) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(PRINCIPAL_TOKEN.toUnit());
        market.setMode(mode);
        market.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        market.setCap(BigInteger.valueOf(1_000_000_000L));
        return market;
    }

    /** A fence that reads open {@code opens} times, then closed for good. */
    private static SubmitFence openFor(int opens) {
        int[] reads = {0};
        return () -> reads[0]++ < opens;
    }

    /**
     * One stage call: what it returned, what it recorded and transmitted, what it logged, and how many times
     * the plain builder was asked to build.
     */
    private record StageRun(WorkResult result, LiquidationDecisionLog log, RecordingSubmitter submitter,
                            List<ILoggingEvent> events, int builds) {
    }

    /**
     * {@link #armedExecutor}, with the configuration chosen by the test, {@link #principalOracle()}
     * served so {@link #otherMarketScenario()} can be built, and — when {@code loanRefThatThrows} is set
     * — a resolver that throws for that loan, which escapes {@code consider()} before anything is built.
     * Runs ONE {@code cycle(NOW, fence)} with the executor's log captured.
     */
    private static StageRun stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                                  RecordingSubmitter submitter, String loanRefThatThrows, SubmitFence fence) {
        return stage(configuration, scenarios, submitter, loanRefThatThrows, fence,
                new LiquidationDecisionLog(configuration));
    }

    private static StageRun stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                                  RecordingSubmitter submitter, String loanRefThatThrows, SubmitFence fence,
                                  LiquidationDecisionLog log) {
        return stage(configuration, scenarios, submitter, loanRefThatThrows, fence, log,
                shaped(WALLET_UTXO, WALLET_UTXO_SMALL));
    }

    private static StageRun stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                                  RecordingSubmitter submitter, String loanRefThatThrows, SubmitFence fence,
                                  LiquidationDecisionLog log, List<Utxo> walletUtxos) {
        return stage(configuration, scenarios, submitter, loanRefThatThrows, fence, log, walletUtxos,
                new FakeOracleClient(List.of(principalOracle())));
    }

    /** As above, with the oracle client the stage serves made explicit. */
    private static StageRun stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                                  RecordingSubmitter submitter, String loanRefThatThrows, SubmitFence fence,
                                  LiquidationDecisionLog log, List<Utxo> walletUtxos, FluidOracleClient oracles) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO));
        universe.addAll(walletUtxos);
        Map<String, Utxo> unspent = new LinkedHashMap<>();
        List<LiquidationAssessment> assessments = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
            unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
            unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());
            assessments.add(scenario.assessment());
        }
        FakeResolver stable = FakeResolver.stable(unspent);
        LiquidationUtxoResolver resolver = new LiquidationUtxoResolver(null, null, null) {
            @Override
            public Optional<Utxo> resolveLoanUtxo(Loan loan) {
                if (loan.utxoRef().equals(loanRefThatThrows)) {
                    throw new IllegalStateException("the index could not be read for " + loan.utxoRef());
                }
                return stable.resolveLoanUtxo(loan);
            }

            @Override
            public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
                return stable.resolveBondUtxo(bond);
            }

            @Override
            public Optional<Utxo> resolveConfigUtxo() {
                return stable.resolveConfigUtxo();
            }

            @Override
            public Optional<Utxo> resolveLmConfigUtxo() {
                return stable.resolveLmConfigUtxo();
            }
        };
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), protocolParams()));
        // A spy, so the builds can be counted: the class is final, and the spy delegates every call.
        LiquidateTransactionBuilder builder = org.mockito.Mockito.spy(new LiquidateTransactionBuilder(
                LoanFixtures.registry(), LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), protocolParams()));
        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT, new FakeScanner(assessments), resolver,
                builder, payInAdvanceRouter, LoanFixtures.registry(), log, metrics(),
                provider(oracles),
                networkNamed("preview"), protocolParams(), LoanFixtures.converters(), submitter);
        executor.setSubmitClock(() -> NOW);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        WorkResult result;
        try {
            result = executor.cycle(NOW, fence);
        } finally {
            logger.detachAppender(appender);
        }
        int builds = (int) org.mockito.Mockito.mockingDetails(builder).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("build")).count();
        return new StageRun(result, log, submitter, appender.list, builds);
    }

    /** The one recorded decision for {@code scenario}'s loan, failing if there is not exactly one. */
    private static LiquidationDecision decisionFor(StageRun stage, Scenario scenario) {
        List<LiquidationDecision> rows = stage.log().newestFirst(10).stream()
                .filter(decision -> scenario.loan().loan().utxoRef().equals(decision.loanUtxoRef()))
                .toList();
        assertEquals(1, rows.size(), "one row for " + scenario.loan().loan().utxoRef() + ": "
                + stage.log().newestFirst(10));
        return rows.getFirst();
    }

    /** (a) Two submittable candidates in ONE market: the first is submitted and the second is never built. */
    @Test
    void aMarketGetsOneLiquidationPerCallAndItsSecondCandidateIsNeverBuilt() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(armed(), List.of(adaScenario(), secondAdaScenario()), submitter, null,
                SubmitFence.OPEN);

        assertEquals(1, submitter.submitted.size(), "exactly one liquidation in the ada market");
        assertEquals(1, stage.log().size(), "the second candidate was never built: " + stage.log().newestFirst(10));
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, stage.log().newestFirst(1).getFirst().outcome());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /** U1 of the M1 wallet: 100 ADA, the largest ada-only output, so U. */
    private static final Utxo U1 = LoanFixtures.adaUtxo("e5".repeat(32), 0, ACCOUNT.baseAddress(), 100_000_000L);

    /** U2 of the M1 wallet: 90 ADA, ample for a plain liquidation, and never U beside U1. */
    private static final Utxo U2 = LoanFixtures.adaUtxo("e6".repeat(32), 0, ACCOUNT.baseAddress(), 90_000_000L);

    /**
     * (b) FAB-185 M1, replacing FAB-183's "two markets on disjoint inputs". Two candidates in different
     * markets, both designated to U: an ADA-principal plain and a token-principal plain. The first is
     * SUBMITTED on U1; the second is skipped unbuilt — never funded from U2, which T-052's mutable list
     * would have handed it. One build, one transmission, and the skip said at INFO.
     */
    @Test
    void aSecondMarketDesignatedToTheSameUIsSkippedAfterTheFirstTransmissionNeverFundedFromAnother()
            throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        AppConfig.LiquidationConfiguration configuration = armed();

        StageRun stage = stage(configuration, List.of(adaScenario(), otherMarketScenario()), submitter, null,
                SubmitFence.OPEN, new LiquidationDecisionLog(configuration), shaped(U1, U2));

        assertEquals(1, stage.builds(), "the second candidate is never built: " + stage.events());
        assertEquals(1, submitter.submitted.size(), "one transmission: " + stage.log().newestFirst(10));
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decisionFor(stage, adaScenario()).outcome());
        List<TransactionInput> first = inputsOf(submitter.submitted.get(0));
        assertTrue(first.contains(new TransactionInput(U1.getTxHash(), 0)), "the first is funded from U1: " + first);
        assertFalse(first.contains(new TransactionInput(U2.getTxHash(), 0)), first.toString());
        assertEquals(1, stage.log().size(), "no row for the skipped candidate: " + stage.log().newestFirst(10));
        assertTrue(skippedOnUsedOutput(stage.events(), TX_LOAN_3 + "#0", U1.getTxHash() + "#0", "U"),
                "the skip is said at INFO, naming U1 and its role: " + stage.events());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /**
     * (c) FAB-185 M7, the node-shadow half: on a SHADOW node a WOULD_SUBMIT stands for the transmission a live
     * node would have made, so it marks U used — the same pair as (b), and the second is skipped unbuilt.
     */
    @Test
    void aNodeShadowWouldSubmitMarksUUsedAndTheSecondMarketIsSkipped() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        AppConfig.LiquidationConfiguration configuration = configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                SMALL_MARGIN, PUBLISHED);

        StageRun stage = stage(configuration, List.of(adaScenario(), otherMarketScenario()), submitter, null,
                SubmitFence.OPEN, new LiquidationDecisionLog(configuration), shaped(U1, U2));

        assertTrue(submitter.submitted.isEmpty(), "shadow transmits nothing");
        assertEquals(1, stage.builds(), "the second candidate is never built: " + stage.events());
        LiquidationDecision first = decisionFor(stage, adaScenario());
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, first.outcome(), first.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), first.submitVeto());
        assertTrue(skippedOnUsedOutput(stage.events(), TX_LOAN_3 + "#0", U1.getTxHash() + "#0", "U"),
                stage.events().toString());
        assertEquals(WorkResult.WOULD_SUBMIT, stage.result());
    }

    /**
     * (c) FAB-185 M7, the market-shadow half: on a LIVE node whose first candidate's MARKET is held at shadow,
     * that WOULD_SUBMIT is a rehearsal that marks nothing — the second market's candidate is still built on
     * the same U, and transmitted.
     */
    @Test
    void aMarketShadowWouldSubmitMarksNothingAndTheSecondMarketIsStillBuilt() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        AppConfig.LiquidationConfiguration configuration = armedWithMarket(AppConfig.LiquidationConfiguration.Mode.SHADOW);

        StageRun stage = stage(configuration, List.of(adaScenario(), otherMarketScenario()), submitter, null,
                SubmitFence.OPEN, new LiquidationDecisionLog(configuration), shaped(U1, U2));

        assertEquals(2, stage.builds(), "the second candidate is built: " + stage.events());
        LiquidationDecision first = decisionFor(stage, adaScenario());
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, first.outcome(), first.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MARKET_NOT_LIVE.name(), first.submitVeto());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decisionFor(stage, otherMarketScenario()).outcome());
        assertEquals(1, submitter.submitted.size());
        assertTrue(inputsOf(submitter.submitted.get(0)).contains(new TransactionInput(U1.getTxHash(), 0)),
                "the second market is funded from the same U");
        assertFalse(skippedOnUsedOutput(stage.events(), TX_LOAN_3 + "#0", U1.getTxHash() + "#0", "U"),
                stage.events().toString());
    }

    /**
     * (c) A DECODED Blockfrost refusal is a definite failure: nothing went out, the market is still open,
     * and its next candidate is transmitted.
     */
    @Test
    void aDecodedRefusalMovesOnToTheSameMarketsNextCandidate() {
        RecordingSubmitter submitter = new RecordingSubmitter(
                RecordingSubmitter.decodedRefusal("ValueNotConservedUTxO"), null);
        submitter.later = Result.success("ab".repeat(32)).withValue("ab".repeat(32));

        StageRun stage = stage(armed(), List.of(adaScenario(), secondAdaScenario()), submitter, null,
                SubmitFence.OPEN);

        assertEquals(2, submitter.submitted.size(), "the market's second candidate was transmitted");
        List<LiquidationDecision> rows = stage.log().newestFirst(10);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMIT_FAILED),
                rows.stream().map(LiquidationDecision::outcome).toList(), rows.toString());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /**
     * (d) The shape every ambiguous outcome shares: one transmission, then nothing — not the ada market's
     * second candidate and not the other market's — and the call says AMBIGUOUS.
     */
    private static StageRun assertAmbiguousEndsTheStage(RecordingSubmitter submitter) {
        StageRun stage = stage(armed(), List.of(adaScenario(), secondAdaScenario(), otherMarketScenario()),
                submitter, null, SubmitFence.OPEN);

        assertEquals(1, submitter.submitted.size(), "nothing after an ambiguous submit is transmitted");
        assertEquals(1, stage.log().size(),
                "no candidate after it, in any market, was built: " + stage.log().newestFirst(10));
        assertEquals(WorkResult.AMBIGUOUS, stage.result());
        return stage;
    }

    /** (d) A thrown submission: AMBIGUOUS, and its row keeps its {@code submission threw} detail (A3). */
    @Test
    void aThrownSubmissionEndsTheStageAsAmbiguous() {
        StageRun stage = assertAmbiguousEndsTheStage(
                RecordingSubmitter.throwing(new IllegalStateException("connection reset")));

        LiquidationDecision row = stage.log().newestFirst(1).getFirst();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, row.outcome());
        assertTrue(row.detail().contains("submission threw"), row.detail());
    }

    /**
     * (d) A raw, non-envelope 400 is not a decoded refusal: AMBIGUOUS. Its row says so, and it is logged
     * as ONE ERROR line carrying the HTTP code and the response — not also as a rejection (A2).
     */
    @Test
    void aRaw400EndsTheStageAsAmbiguousWithOneErrorLine() {
        StageRun stage = assertAmbiguousEndsTheStage(RecordingSubmitter.rejecting("ValueNotConservedUTxO"));

        LiquidationDecision row = stage.log().newestFirst(1).getFirst();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, row.outcome());
        assertTrue(row.detail().startsWith("ambiguous: "), row.detail());
        List<ILoggingEvent> lines = errorsContaining(stage.events(), "submitting the liquidation of");
        assertEquals(1, lines.size(), "exactly one ERROR line for the submission: " + stage.events());
        String line = lines.getFirst().getFormattedMessage();
        assertTrue(line.contains("is AMBIGUOUS: HTTP 400: ValueNotConservedUTxO — the stage stops"), line);
    }

    /** (d) An HTTP 500: AMBIGUOUS, and its row says so. */
    @Test
    void anHttp500EndsTheStageAsAmbiguous() {
        StageRun stage = assertAmbiguousEndsTheStage(new RecordingSubmitter(
                Result.<String>error("Internal Server Error").code(500), null));

        LiquidationDecision row = stage.log().newestFirst(1).getFirst();
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, row.outcome());
        assertTrue(row.detail().startsWith("ambiguous: "), row.detail());
        assertTrue(row.detail().contains("HTTP 500: Internal Server Error"), row.detail());
    }

    /** (e) SHADOW: a WOULD_SUBMIT serves its market too — only the first candidate is built and recorded. */
    @Test
    void aShadowWouldSubmitServesItsMarketForTheCall() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW, SMALL_MARGIN,
                PUBLISHED), List.of(adaScenario(), secondAdaScenario()), submitter, null, SubmitFence.OPEN);

        assertTrue(submitter.submitted.isEmpty(), "shadow transmits nothing");
        assertEquals(1, stage.log().size(), "only the first was built: " + stage.log().newestFirst(10));
        LiquidationDecision row = stage.log().newestFirst(1).getFirst();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, row.outcome(), row.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), row.submitVeto());
        assertEquals(WorkResult.WOULD_SUBMIT, stage.result());
    }

    /** (f) A fence closed before the first candidate: nothing is built, and the call is a definite failure. */
    @Test
    void aFenceClosedBeforeTheFirstCandidateBuildsNothing() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(armed(), List.of(adaScenario(), otherMarketScenario()), submitter, null,
                () -> false);

        assertTrue(submitter.submitted.isEmpty());
        assertEquals(0, stage.log().size(), "nothing was built: " + stage.log().newestFirst(10));
        assertEquals(WorkResult.DEFINITE_FAILURE, stage.result());
        assertEquals(1, stage.events().stream()
                        .filter(event -> event.getLevel() == Level.INFO)
                        .filter(event -> event.getFormattedMessage()
                                .equals("liquidation stage fenced: 2 candidate(s) not reached"))
                        .count(),
                stage.events().toString());
    }

    /** (g) A fence that closes after the first transmission: the other market's candidate is not built. */
    @Test
    void aFenceClosingAfterTheFirstTransmissionStopsTheStage() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(armed(), List.of(adaScenario(), otherMarketScenario()), submitter, null,
                () -> submitter.submitted.isEmpty());

        assertEquals(1, submitter.submitted.size());
        assertEquals(1, stage.log().size(), "the second candidate was not built: " + stage.log().newestFirst(10));
        assertEquals(WorkResult.SUBMITTED, stage.result());
        // FAB-185: the second candidate would also be skipped for sharing U, so the FENCE is pinned by its line.
        assertTrue(stage.events().stream().anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().equals("liquidation stage fenced: 1 candidate(s) not reached")),
                "the fence, not the used mark, stopped the walk: " + stage.events());
    }

    /** (h) A fence that closes between the vetoes and the wire: nothing transmitted, a fenced SUBMIT_VETOED row. */
    @Test
    void aFenceClosedAtSubmitTransmitsNothingAndRecordsAFencedVeto() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(armed(), List.of(adaScenario()), submitter, null, openFor(1));

        assertTrue(submitter.submitted.isEmpty(), "a closed fence transmits nothing");
        LiquidationDecision row = stage.log().newestFirst(1).getFirst();
        assertEquals(1, stage.log().size());
        assertEquals(LiquidationDecision.Outcome.SUBMIT_VETOED, row.outcome(), row.detail());
        assertNull(row.submitVeto(), "not one of the seven vetoes");
        assertTrue(row.detail().contains("fenced"), row.detail());
        assertEquals(WorkResult.DEFINITE_FAILURE, stage.result());
    }

    /** (i) SUBMITTED outranks WOULD_SUBMIT: the ada market held at shadow, the other market live. */
    @Test
    void submittedOutranksWouldSubmit() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));

        StageRun stage = stage(armedWithMarket(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                List.of(adaScenario(), otherMarketScenario()), submitter, null, SubmitFence.OPEN);

        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decisionFor(stage, adaScenario()).outcome());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decisionFor(stage, otherMarketScenario()).outcome());
        assertEquals(1, submitter.submitted.size());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /** (i) WOULD_SUBMIT outranks a definite failure. */
    @Test
    void wouldSubmitOutranksADefiniteFailure() {
        RecordingSubmitter submitter = RecordingSubmitter.rejectingDecoded("ValueNotConservedUTxO");

        StageRun stage = stage(armedWithMarket(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                List.of(adaScenario(), otherMarketScenario()), submitter, null, SubmitFence.OPEN);

        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decisionFor(stage, adaScenario()).outcome());
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED,
                decisionFor(stage, otherMarketScenario()).outcome());
        assertEquals(WorkResult.WOULD_SUBMIT, stage.result());
    }

    /**
     * (i) AMBIGUOUS after an earlier definite outcome ends the call as AMBIGUOUS. FAB-185: two plain markets
     * now share U, so the earlier outcome that leaves U unused is a definite (decoded) refusal — the second
     * market's transmission then answers a raw 400, AMBIGUOUS, and the call says so.
     */
    @Test
    void ambiguousOutranksAnEarlierDefiniteFailure() {
        RecordingSubmitter submitter = new RecordingSubmitter(
                RecordingSubmitter.decodedRefusal("ValueNotConservedUTxO"), null);
        submitter.later = Result.<String>error("ValueNotConservedUTxO").code(400);

        StageRun stage = stage(armed(), List.of(adaScenario(), otherMarketScenario()), submitter, null,
                SubmitFence.OPEN);

        assertEquals(2, submitter.submitted.size());
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMIT_FAILED, LiquidationDecision.Outcome.SUBMIT_FAILED),
                stage.log().newestFirst(10).stream().map(LiquidationDecision::outcome).toList());
        assertTrue(stage.log().newestFirst(1).getFirst().detail().startsWith("ambiguous: "),
                stage.log().newestFirst(1).getFirst().detail());
        assertEquals(WorkResult.AMBIGUOUS, stage.result());
    }

    /**
     * (j) A6: an exception escaping the top-ranked candidate BEFORE it reached the submitter is a definite
     * failure — that loan is skipped with today's ERROR line and the next one is tried.
     */
    @Test
    void anExceptionBeforeTheSubmitterSkipsThatCandidateAndTheNextIsTransmitted() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        Scenario first = adaScenario();
        Scenario second = secondAdaScenario();

        StageRun stage = stage(armed(), List.of(first, second), submitter, first.loan().loan().utxoRef(),
                SubmitFence.OPEN);

        assertEquals(1, errorsContaining(stage.events(),
                        "could not consider bond " + first.bond().bond().utxoRef()).size(),
                "the top-ranked candidate is the one that threw: " + stage.events());
        assertEquals(1, submitter.submitted.size(), "the next candidate was transmitted");
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decisionFor(stage, second).outcome());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /**
     * A6, the other side: an exception escaping a candidate AFTER it reached the submitter may have
     * escaped a transmission, so the stage stops as AMBIGUOUS. Here recording the SUBMITTED row throws.
     */
    @Test
    void anExceptionAfterTheSubmitterEndsTheStageAsAmbiguous() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        AppConfig.LiquidationConfiguration configuration = armed();
        LiquidationDecisionLog failingLog = new LiquidationDecisionLog(configuration) {
            @Override
            public synchronized void record(LiquidationDecision decision) {
                if (decision.outcome() == LiquidationDecision.Outcome.SUBMITTED) {
                    throw new IllegalStateException("the decision log is unavailable");
                }
                super.record(decision);
            }
        };

        StageRun stage = stage(configuration, List.of(adaScenario(), secondAdaScenario()), submitter, null,
                SubmitFence.OPEN, failingLog);

        assertEquals(1, submitter.submitted.size(), "nothing after a possibly transmitted body goes out");
        assertEquals(1, errorsContaining(stage.events(), "could not consider bond").size(), stage.events().toString());
        assertEquals(WorkResult.AMBIGUOUS, stage.result());
    }

    /**
     * (k) A SUBMITTED liquidation is what the call reports when a later market is held at shadow: the ada
     * market live, the other market held at shadow, so the transmission comes first. FAB-185: both are plain
     * and designated to U, so the later candidate is now skipped for sharing U — the rehearsal is never
     * built — and the call still ends at SUBMITTED, so the pipeline does not run compound in the same pass
     * right after a real liquidation went out. (A later WOULD_SUBMIT after a SUBMITTED now needs a candidate
     * funded from another role, a token-principal pay-in-advance; this rig builds plain candidates only.)
     */
    @Test
    void aSubmittedLiquidationIsWhatTheCallReportsWhenALaterMarketIsHeldAtShadow() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        var configuration = armed();
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(PRINCIPAL_TOKEN.toUnit());
        market.setMode(AppConfig.LiquidationConfiguration.Mode.SHADOW);
        market.setAction(AppConfig.LiquidationConfiguration.Action.CONVERT);
        configuration.setMarkets(List.of(market));

        StageRun stage = stage(configuration, List.of(adaScenario(), otherMarketScenario()), submitter, null,
                SubmitFence.OPEN);

        List<LiquidationDecision> oldestFirst = new ArrayList<>(stage.log().newestFirst(10));
        java.util.Collections.reverse(oldestFirst);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED),
                oldestFirst.stream().map(LiquidationDecision::outcome).toList(),
                "the transmission is recorded, and the later rehearsal is never built: " + oldestFirst);
        assertTrue(skippedOnUsedOutput(stage.events(), TX_LOAN_3 + "#0", TX_WALLET + "#0", "U"),
                "the later candidate is skipped for sharing U: " + stage.events());
        assertEquals(1, submitter.submitted.size());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /**
     * (k) SUBMITTED outranks a LATER WOULD_SUBMIT: the ada market live, the other market held at shadow,
     * so the transmission comes first and the rehearsal after it. {@link #submittedOutranksWouldSubmit}
     * meets them the other way round, where a result that simply took the latest outcome ends at
     * SUBMITTED too. Here it would end at WOULD_SUBMIT, and the pipeline would run compound in the same
     * pass right after a real liquidation went out.
     * <p>
     * FAB-185 r2: the later candidate is a token-principal PAY-IN-ADVANCE, funded from its own TOKEN output
     * rather than the U the first one spent, so it is still built — the case two plain candidates can no
     * longer reach ({@link #aSubmittedLiquidationIsWhatTheCallReportsWhenALaterMarketIsHeldAtShadow}).
     */
    @Test
    void aSubmittedLiquidationOutranksALaterShadowWouldSubmit() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        var configuration = armedForPayInAdvance(AppConfig.LiquidationConfiguration.Mode.SHADOW);

        StageRun stage = stage(configuration, List.of(adaScenario(), tokenPrincipalPayInAdvanceScenario()),
                submitter, null, SubmitFence.OPEN, new LiquidationDecisionLog(configuration),
                shaped(WALLET_UTXO, TOKEN_WALLET_UTXO),
                new LegPricingOracleClient(List.of(principalOracle(), collateralOracle())));

        List<LiquidationDecision> oldestFirst = new ArrayList<>(stage.log().newestFirst(10));
        java.util.Collections.reverse(oldestFirst);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.WOULD_SUBMIT),
                oldestFirst.stream().map(LiquidationDecision::outcome).toList(),
                "the transmission is recorded BEFORE the rehearsal: " + oldestFirst);
        assertEquals(1, submitter.submitted.size());
        assertEquals(WorkResult.SUBMITTED, stage.result());
        // The shape the ranking depends on: the rehearsal is the pay-in-advance, held by its MARKET, and
        // funded from its TOKEN output — never from the U the transmission spent.
        LiquidationDecision rehearsal = decisionFor(stage, tokenPrincipalPayInAdvanceScenario());
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, rehearsal.outcome(), rehearsal.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MARKET_NOT_LIVE.name(), rehearsal.submitVeto());
        assertTrue(fundedFromTokenOutput(stage.events(), TX_LOAN_4 + "#0"), stage.events().toString());
    }

    /**
     * (i) AMBIGUOUS outranks an earlier SUBMITTED in another market.
     * <p>
     * FAB-185 r2: the second market's candidate is a token-principal PAY-IN-ADVANCE, funded from its own
     * TOKEN output, so it is built and transmitted after the first went out on U — and its raw 400 answer is
     * AMBIGUOUS. ({@link #ambiguousOutranksAnEarlierDefiniteFailure} keeps r1's plain-only pair.)
     */
    @Test
    void ambiguousOutranksAnEarlierSubmitted() throws Exception {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        submitter.later = Result.<String>error("ValueNotConservedUTxO").code(400);
        var configuration = armedForPayInAdvance(AppConfig.LiquidationConfiguration.Mode.LIVE);

        StageRun stage = stage(configuration, List.of(adaScenario(), tokenPrincipalPayInAdvanceScenario()),
                submitter, null, SubmitFence.OPEN, new LiquidationDecisionLog(configuration),
                shaped(WALLET_UTXO, TOKEN_WALLET_UTXO),
                new LegPricingOracleClient(List.of(principalOracle(), collateralOracle())));

        assertEquals(2, submitter.submitted.size());
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMIT_FAILED, LiquidationDecision.Outcome.SUBMITTED),
                stage.log().newestFirst(10).stream().map(LiquidationDecision::outcome).toList());
        assertEquals(WorkResult.AMBIGUOUS, stage.result());
        // The shape the ranking depends on: the AMBIGUOUS transmission is the pay-in-advance's, funded from
        // its TOKEN output.
        assertEquals(LiquidationDecision.Outcome.SUBMIT_FAILED,
                decisionFor(stage, tokenPrincipalPayInAdvanceScenario()).outcome());
        assertTrue(fundedFromTokenOutput(stage.events(), TX_LOAN_4 + "#0"), stage.events().toString());
        assertTrue(inputsOf(submitter.submitted.get(1)).contains(new TransactionInput(TX_TOKEN_WALLET, 0)),
                "the second transmission spends the TOKEN output");
    }

    /** The INFO line naming {@link #TOKEN_WALLET_UTXO} as the funding of {@code loanRef}, under role TOKEN. */
    private static boolean fundedFromTokenOutput(List<ILoggingEvent> events, String loanRef) {
        return events.stream().anyMatch(event -> event.getFormattedMessage().contains(loanRef)
                && event.getFormattedMessage().contains("is funded from wallet output " + TX_TOKEN_WALLET
                + "#0 (role TOKEN " + PRINCIPAL_TOKEN.toUnit()));
    }

    /**
     * (l) A served market's later candidate is SKIPPED and the walk goes on: the ada market's second
     * candidate is passed over unbuilt, and the other market, ranked after it, is still REACHED. FAB-185: that
     * other market is plain too and designated to the same U, which the first transmission used, so it is
     * reached and skipped by name — its skip line is the evidence the walk did not end at the served market.
     */
    @Test
    void aServedMarketsLaterCandidateIsSkippedAndTheWalkReachesTheNextMarket() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        Scenario ada = adaScenario();
        Scenario secondAda = secondAdaScenario();
        Scenario other = otherMarketScenario();

        StageRun stage = stage(armed(), List.of(ada, secondAda, other), submitter, null, SubmitFence.OPEN);

        // The order this test depends on: the skipped candidate is ranked BETWEEN the served one and the
        // other market. Ranked anywhere else, ending the walk at the skip would not cost the other market.
        assertEquals(List.of(ada.loan().loan().utxoRef(), secondAda.loan().loan().utxoRef(),
                        other.loan().loan().utxoRef()), rankedLoanRefs(stage));
        assertEquals(1, submitter.submitted.size(), "one liquidation, in the ada market: " + stage.log().newestFirst(10));
        assertEquals(1, stage.log().size(), "the ada market's second candidate was never built: "
                + stage.log().newestFirst(10));
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decisionFor(stage, ada).outcome());
        assertTrue(skippedOnUsedOutput(stage.events(), other.loan().loan().utxoRef(), TX_WALLET + "#0", "U"),
                "the walk reached the other market after the served market's skip: " + stage.events());
        assertFalse(skippedOnUsedOutput(stage.events(), secondAda.loan().loan().utxoRef(), TX_WALLET + "#0", "U"),
                "the ada market's second candidate is skipped as a served market, before its wallet role is asked");
        assertTrue(stage.log().newestFirst(10).stream()
                        .noneMatch(row -> secondAda.loan().loan().utxoRef().equals(row.loanUtxoRef())),
                stage.log().newestFirst(10).toString());
        assertEquals(WorkResult.SUBMITTED, stage.result());
    }

    /** The loan refs of the one {@code liquidation order} INFO line the call logged, in rank order. */
    private static List<String> rankedLoanRefs(StageRun stage) {
        String prefix = "liquidation order (most profitable first): ";
        List<String> lines = stage.events().stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
        assertEquals(1, lines.size(), stage.events().toString());
        return java.util.Arrays.stream(lines.getFirst().substring(prefix.length()).split(", "))
                .map(label -> label.substring(0, label.indexOf(' ')))
                .toList();
    }

    // ======================================================================================
    // the shipped defaults
    // ======================================================================================

    /**
     * {@code mode: disabled} — the whole arming story now that {@code loans.liquidation.enabled} is
     * gone — is what an operator gets if they change nothing. Nothing is scanned, nothing is built,
     * nothing is submitted.
     */
    @Test
    void withTheShippedDefaultsNothingIsSubmitted() {
        RecordingSubmitter submitter = RecordingSubmitter.accepting("ab".repeat(32));
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                        SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()))
                .submitter(submitter)
                .run();

        run.assertNothingWasSubmitted();
        assertEquals(0, run.log().size());
        assertFalse(configuration(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                SMALL_MARGIN, LiquidateTransactionBuilder.ReferenceScripts.none()).isArmed());
    }

    /**
     * And the preview default, which is {@code shadow} — one change ({@code mode: live}) away from
     * submitting now that the separate arming flag is gone (2026-09-04).
     */
    @Test
    void theShippedPreviewDefaultIsAlsoIncapableOfSubmitting() {
        Run run = new Rig()
                .configuration(configuration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        SMALL_MARGIN, PUBLISHED))
                .run();

        // Deliberately no veto name: shadow-without-arming is stopped by S1 and would still be
        // stopped by S2 (a market can never be more live than the node — MarketGate.effectiveMode),
        // and this test is about the shipped configuration rather than about which of the two got
        // there first.
        run.assertNothingWasSubmitted();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, run.onlyDecision().outcome());
    }

    /**
     * A census reporting <b>nothing unreadable</b> — the healthy world these fakes model. T-060: a
     * non-zero {@code unreadable} would mean some bond's LOAN_NOT_FOUND is a loan we cannot read
     * rather than one that is gone, and no fake here is exercising that.
     */
    private static LoanService.Census readableCensus(List<LiquidationAssessment> assessments) {
        return new LoanService.Census(List.of(), assessments.size(), 0, 0);
    }
}
