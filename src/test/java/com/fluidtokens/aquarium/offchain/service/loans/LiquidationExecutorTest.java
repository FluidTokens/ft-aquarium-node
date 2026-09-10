package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
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
import org.slf4j.LoggerFactory;
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
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>Reviewer's orientation — what this class proves, and what it does not</h2>
 * <b>Proves:</b> the executor's decision LOGIC — which candidates it routes where, what it records,
 * what it quarantines, which of the eight submit-vetoes fires, and that shadow mode never reaches the
 * wire (every wiring here gets a submitter that fails the test on contact).
 * <b>Does NOT prove:</b> anything about the chain. Its collaborators are hand-built fakes and its
 * evaluator is either the offline PlutusV3 rig or absent entirely. A green run here says the loop
 * behaves; it says nothing about whether a transaction it produced would be accepted.
 * <b>Evaluator:</b> offline (aiken-java-binding) where ex-units matter, otherwise none.
 *
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

    // ---- ECONOMICS GATE direction (token-principals slice) -----------------------------------------

    /**
     * ⛔ THE DIRECTION. {@code PricingService.toLovelace} floors, which flatters an OUTLAY (understates
     * the cost); {@link LiquidationExecutor#outlayCeilBiased} corrects that toward the ceiling side. A
     * mutant that drops the {@code +1} (reverting to the bare floor) must change this test's result —
     * it does, because the floored price here is not already a "round" figure the +1 could hide
     * behind.
     */
    @Test
    void outlayCeilBiasedAddsOneToTheFlooredPrice() {
        assertEquals(BigInteger.valueOf(1_033_850_766L),
                LiquidationExecutor.outlayCeilBiased(BigInteger.valueOf(1_033_850_765L)),
                "the outlay must be taken ONE ABOVE the floored price, never the bare floor");
        assertNotEquals(BigInteger.valueOf(1_033_850_765L),
                LiquidationExecutor.outlayCeilBiased(BigInteger.valueOf(1_033_850_765L)),
                "a mutant reverting to the bare floor must be distinguishable from the fix");
    }

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

        private int unreadable;

        FakeScanner(List<LiquidationAssessment> assessments) {
            super(null, null, null);
            this.assessments = assessments;
        }

        @Override
        public Scan scan(long atTimeMillis) {
            scans++;
            return new Scan(assessments,
                    new LoanService.Census(List.of(), assessments.size() + unreadable, unreadable, 0));
        }
    }

    /** Answers from a fixed set of "still unspent" UTxOs, keyed by {@code txHash#index}. */
    private static final class FakeResolver extends LiquidationUtxoResolver {

        private final Map<String, Utxo> unspent;

        private int loanResolutions;

        /**
         * Thrown by the FIRST loan resolution, which {@code consider()} performs before it enters
         * either build {@code try} — so it leaves {@code consider()} entirely and can only be caught
         * by the cycle loop's outer net. Null means the resolver behaves.
         */
        private final RuntimeException firstResolutionThrows;

        FakeResolver(Map<String, Utxo> unspent) {
            this(unspent, null);
        }

        FakeResolver(Map<String, Utxo> unspent, RuntimeException firstResolutionThrows) {
            super(null, null, null);
            this.unspent = unspent;
            this.firstResolutionThrows = firstResolutionThrows;
        }

        @Override
        public Optional<Utxo> resolveLoanUtxo(Loan loan) {
            loanResolutions++;
            if (firstResolutionThrows != null) {
                throw firstResolutionThrows;
            }
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

        // F1 (round 2) — findEntry is what PricingService actually calls (never entries()); it is
        // keyed by the PRICED asset (entry.token()), never the oracle NFT (entry.oracleToken(), which
        // is the OTHER lookup, findEntryByOracleToken's). No existing test before this round drove a
        // convert candidate's economics far enough to need this — see
        // LiquidationExecutorTest#tokenPrincipalConvertEconomicsWiring, the first to.
        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return entries.stream().filter(e -> e.token().equals(token)).findFirst();
        }
    }

    /**
     * A canned convert-router stand-in for task 8 (ADDENDA): it returns a REAL, already-built
     * transaction rather than assembling a genuine Minswap order, because these tests only need
     * {@code record()} to be REACHED under a CONVERT-routed (Minswap) candidate — proving the
     * {@code record()} construction site consults the market and not just the bond flag. A working
     * Minswap integration is exercised elsewhere ({@code ConvertReferenceScriptWiringTest} and
     * friends); duplicating that here would test the router, not {@code variantOf}.
     */
    private static final class FakeConvertRouter extends ConvertLiquidationRouter {

        private final Transaction canned;

        FakeConvertRouter(Transaction canned) {
            super(null, null, null, null, null, null, null, null);
            this.canned = canned;
        }

        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo,
                                                    Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Map<String, OracleEntry> oraclesByOracleTokenUnit,
                                                    String changeAddress, long validFromMillis,
                                                    long validToMillis) {
            return canned;
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
     * The same ada/ada scenario as {@link #scenario(BigInteger)}, but with a <em>convert</em> bond
     * ({@code shouldLiquidationConvertToPrincipal == True}) — the one datum flag
     * {@link LiquidationExecutor} routes on. Every other field is identical, so the difference between
     * this and {@code scenario} is exactly the routing decision. Used by the executor-level convert
     * tests that exercise the {@code PayInAdvanceNotModelledException → REFUSED} and
     * {@code genuine-exception → quarantine} mappings without needing the full oracle universe the
     * buildable-convert path requires.
     */
    private static Scenario convertScenario(BigInteger feePerMille) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.convertToPrincipalBondDatum(feePerMille,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
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
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, margin,
                decisionLogSize, 30);
        // The market gate defaults to DISABLED for every market (Giovanni's defensive-default
        // ruling). A pay-in-advance test must therefore name the market it operates in, exactly
        // as an operator must — an ample ada cap, so the gate is satisfied and never the thing
        // under test here.
        configuration.setMarkets(java.util.List.of(
                anticipateMarket("lovelace", 1_000_000_000_000L)));
        return configuration;
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
                          CountingOracleProvider oracles,
                          BlockEventListener blockEventListener) {
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
        return wiring(configuration, scanned, inUniverse, stillUnspent, walletUtxos, oracles, syncing,
                honestExUnits, LoanFixtures.protocolParams(), null, null);
    }

    /**
     * As above, with the two seams the failure-surfacing tests drive.
     *
     * @param builderParams  the {@link ProtocolParamsSupplier} handed to the two transaction builders
     *                       — <em>not</em> to the executor, so a throwing one fails the BUILD and
     *                       nothing else. This is the branch comment's own example ("a Blockfrost
     *                       timeout fetching protocol params, say") made executable
     * @param resolverThrows thrown by the first loan resolution, which happens before either build
     *                       {@code try} and therefore reaches only the cycle loop's outer net
     * @param plainBuilder   overrides the plain {@link LiquidateTransactionBuilder}. Needed because
     *                       that builder converts <em>everything</em> its own {@code context.build()}
     *                       can throw into a named {@code RefusedException} — so no fault injected
     *                       through its suppliers can reach the executor's machinery-failure catch,
     *                       which is the site under test
     */
    private static Wiring wiring(AppConfig.LiquidationConfiguration configuration,
                                 List<LiquidationAssessment> scanned,
                                 List<Scenario> inUniverse,
                                 Map<String, Utxo> stillUnspent,
                                 List<Utxo> walletUtxos,
                                 CountingOracleProvider oracles,
                                 boolean syncing,
                                 boolean honestExUnits,
                                 ProtocolParamsSupplier builderParams,
                                 RuntimeException resolverThrows,
                                 LiquidateTransactionBuilder plainBuilder) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO));
        universe.addAll(walletUtxos);
        for (Scenario scenario : inUniverse) {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
        }
        Map<String, Utxo> unspent = new LinkedHashMap<>(stillUnspent);

        LiquidateTransactionBuilder builder = plainBuilder != null ? plainBuilder
                : new LiquidateTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                        builderParams, honestExUnits ? realExUnitsEvaluator(universe) : null);

        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(syncing);

        FakeScanner scanner = new FakeScanner(scanned);
        FakeResolver resolver = new FakeResolver(unspent, resolverThrows);
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);

        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), builderParams));

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT, scanner, resolver, builder,
                payInAdvanceRouter, LoanFixtures.registry(), log, oracles,
                previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                EXPLODING_SUBMITTER);
        return new Wiring(executor, log, scanner, resolver, oracles, blockEventListener);
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
     * A {@link ProtocolParamsSupplier} that fails the way a real one does: a transport fault wrapped
     * by the client that hit it. <b>Wrapped deliberately.</b> A cause-less fixture makes every
     * "surfaces the real cause" assertion vacuous, because {@code causeChain(e)} is then merely a
     * substring of {@code e.toString()} and the assertions pass under the very {@code toString()} the
     * fix exists to replace — the audit finding of 2026-08-21, first on the T-037 submit sites and
     * then again on these build sites. The root message below is what discriminates: no
     * {@code toString()} of any wrapper in the chain ever contains it.
     */
    private static ProtocolParamsSupplier throwingParams() {
        return () -> {
            throw new IllegalStateException("cannot fetch protocol parameters",
                    new java.net.SocketTimeoutException(BLOCKFROST_TIMEOUT));
        };
    }

    /** The root-cause message {@code throwingParams()} buries one level down. */
    private static final String BLOCKFROST_TIMEOUT = "connect timed out";

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
    // the frozen f855 convert fixture (duplicated from PayInAdvanceLiquidationRouterTest)
    // ======================================================================================

    /**
     * The frozen convert loan {@code f855d1b4…#1/#3} — the same real preview shape
     * {@code PayInAdvanceLiquidationRouterTest} and {@code LiquidatePayInAdvanceDryEvalTest} pin,
     * duplicated here so this class owns its convert inputs. 100 000 000 tFLDT of collateral against a
     * 28 000 000-lovelace (ada) principal, with a lender bond whose
     * {@code shouldLiquidationConvertToPrincipal == True} — the flag {@link LiquidationExecutor} routes
     * on. Only what the executor's buildable-convert path actually reads is duplicated.
     */
    private static final class F855 {

        private static final String LOAN_TX =
                "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
        private static final int LOAN_INDEX = 1;
        private static final int BOND_INDEX = 3;
        private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";

        private static final String LOAN_DATUM_HEX =
                "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                        + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                        + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                        + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                        + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";
        private static final String BOND_DATUM_HEX =
                "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                        + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87a80"
                        + "1832581d00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11d8799f4040ff"
                        + "ff";
        private static final String LOAN_ADDRESS =
                "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                        + "hhvnjkmcgaps3ahx5qxanp9j";
        private static final String BOND_ADDRESS =
                "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                        + "6tegngjg7zrdk23hps7h96kk";

        private static final AssetType COLLATERAL = new AssetType(
                "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
        private static final long COLLATERAL_AMOUNT = 100_000_000L;
        private static final long LOAN_LOVELACE = 3_000_000L;
        private static final long BOND_LOVELACE = 1_810_200L;

        private static final AssetType ORACLE_NFT = new AssetType(
                "9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
        private static final AssetType C3_FEED_NFT = new AssetType(
                "decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");
        private static final String ORACLE_SCRIPT_HASH =
                "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
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
        private static final String C3_PROVIDER_DATUM_HEX =
                "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff";
        private static final BigInteger PRICE = BigInteger.valueOf(338163);
        private static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(1_000_000);

        /** This loan's lendDate; the cycle instant sits ~1h after it, exactly as the router test does. */
        private static final long LEND_DATE = 1_787_216_064_000L;
        private static final long NOW = LEND_DATE + 3_600_000L;
        private static final long FEED_VALID_FROM = NOW - 35_555L;
        private static final long FEED_VALID_TO = FEED_VALID_FROM + 600_000L;

        private static final long REMAINING_DEBT = 28_000_147L;
        private static final long EQUITY = 8_919_184L;
        private static final long LIQUIDATION_FEE = 5_000_000L;

        static LoanDatum loanDatum() {
            return new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
        }

        static LenderManagerDatum bondDatum() {
            return new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
        }

        static Loan loan() {
            return new Loan(LOAN_TX, LOAN_INDEX, LOAN_ADDRESS, LOAN_ID,
                    BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), loanDatum());
        }

        static LenderBond bond() {
            return new LenderBond(LOAN_TX, BOND_INDEX, BOND_ADDRESS, LOAN_ID, BOND_DATUM_HEX, bondDatum());
        }

        static Utxo loanUtxo() {
            return LoanFixtures.utxo(LOAN_TX, LOAN_INDEX, LOAN_ADDRESS, List.of(
                    Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                    Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                    Amount.asset(LoanFixtures.registry().getLoanPolicyId() + LOAN_ID, BigInteger.ONE)),
                    LOAN_DATUM_HEX);
        }

        static Utxo bondUtxo() {
            return LoanFixtures.utxo(LOAN_TX, BOND_INDEX, BOND_ADDRESS, List.of(
                    Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                    Amount.asset(LoanFixtures.registry().getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)),
                    BOND_DATUM_HEX);
        }

        static OracleEntry oracle() {
            return LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                    OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                            FEED_VALID_FROM, FEED_VALID_TO),
                    ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);
        }

        /** The three oracle reference-input UTxOs the pay-in-advance builder resolves to assemble the body. */
        static List<Utxo> oracleUniverse() {
            List<Utxo> universe = new ArrayList<>();
            universe.add(LoanFixtures.utxo(ORACLE_REF_INPUT.getTransactionId(), ORACLE_REF_INPUT.getIndex(),
                    ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                            Amount.asset(LoanFixtures.unit(ORACLE_NFT), BigInteger.ONE)), null));
            universe.add(Utxo.builder()
                    .txHash(ORACLE_REF_SCRIPT.getTransactionId())
                    .outputIndex(ORACLE_REF_SCRIPT.getIndex())
                    .address(ORACLE_ADDRESS)
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                    .referenceScriptHash(ORACLE_SCRIPT_HASH)
                    .build());
            universe.add(LoanFixtures.utxo(C3_PROVIDER.getTransactionId(), C3_PROVIDER.getIndex(),
                    C3_PROVIDER_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                            Amount.asset(LoanFixtures.unit(C3_FEED_NFT), BigInteger.ONE)),
                    C3_PROVIDER_DATUM_HEX));
            return universe;
        }

        static LiquidationAssessment assessment() {
            return LiquidationAssessment.buildable(bond(), loan(), "f855 convert fixture",
                    BigInteger.valueOf(REMAINING_DEBT), BigInteger.valueOf(EQUITY), false,
                    BigInteger.valueOf(LIQUIDATION_FEE));
        }

        /**
         * F0 (round 2) — the frozen loan with a custom datum, and an assessment whose {@code equity}
         * field is set to {@code assessedEquity} for the router's PRECONDITION check only. The router
         * recomputes the REAL equity fresh from {@code loan(datum).datum()} + the oracle feed via
         * {@code builder.numbers()} — {@code assessedEquity} never reaches the built transaction, so a
         * caller proving a specific built shape must ALSO make {@code datum} compute to the same figure.
         */
        static LiquidationAssessment assessment(LoanDatum datum, BigInteger assessedEquity) {
            return LiquidationAssessment.buildable(bond(), loan(datum), "f855 convert fixture (custom)",
                    BigInteger.valueOf(REMAINING_DEBT), assessedEquity, false,
                    BigInteger.valueOf(LIQUIDATION_FEE));
        }

        static Loan loan(LoanDatum datum) {
            return new Loan(LOAN_TX, LOAN_INDEX, LOAN_ADDRESS, LOAN_ID,
                    BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);
        }

        /**
         * F0 (round 2) — {@link #loanDatum()} with ONLY {@code principalAmount}/{@code interestRate}
         * tuned so the REAL computed equity lands on exactly zero against the FIXED {@code
         * COLLATERAL_AMOUNT} and the FIXED oracle {@code PRICE} — the identical derivation
         * {@code PayInAdvanceLiquidationRouterTest.equityZeroLoanDatum()} pins, because it is the same
         * collateral amount and the same oracle price: solving {@code collateralInLovelace - D -
         * 0.05·D = 0} for the ada-denominated debt {@code D} gives {@code D = 32,206,000} exactly.
         */
        static LoanDatum equityZeroLoanDatum() {
            LoanDatum ada = loanDatum();
            return new LoanDatum(ada.doneRecasts(), BigInteger.valueOf(32_206_000L), ada.lendDate(),
                    ada.repaidInstallments(), BigInteger.ZERO, ada.totalInstallments(),
                    ada.principalAsset(), ada.principalOracleAsset(), ada.installmentPeriod(),
                    ada.initialGracePeriod(), ada.liquidationMode(), ada.repaymentMode(),
                    ada.repaymentTimeWindow(), ada.penaltyFeeForLateRepayment(), ada.repaymentReceipts(),
                    ada.originId(), ada.collateral());
        }
    }

    /**
     * The whole loop wired for the frozen f855 convert loan: the scanner returns its buildable convert
     * assessment, the resolver reports both UTxOs unspent, the oracle registry carries the collateral
     * c3 oracle, and the pay-in-advance builder is handed a universe that includes the three oracle
     * reference inputs so it can actually assemble the {@code LiquidateAndPayInAdvance} body. Shadow
     * mode and the exploding submitter, exactly like every other wiring in this class.
     */
    private static Wiring convertWiring() {
        return convertWiring(LoanFixtures.protocolParams());
    }

    /**
     * As above, with the {@link ProtocolParamsSupplier} the pay-in-advance builder is handed made
     * explicit. A throwing one fails <em>inside</em> that builder's own {@code try}, so the exception
     * the executor's convert catch receives is the genuine production wrapper
     * {@code IllegalStateException("cannot build the pay-in-advance transaction", cause)} raised at
     * {@code LiquidatePayInAdvanceTransactionBuilder:502} — earned by the real builder, not supplied
     * by the fixture.
     */
    private static Wiring convertWiring(ProtocolParamsSupplier payInAdvanceParams) {
        return convertWiring(payInAdvanceParams, F855.assessment());
    }

    /**
     * As above, with the {@link LiquidationAssessment} made explicit too — F0 (round 2)'s
     * {@code equityZeroIsBuildableAndRecorded} swaps in {@link F855#assessment(LoanDatum)} against a
     * tuned datum whose REAL computed equity (through {@code builder.numbers()}, never {@code
     * assessment.equity()} — the router recomputes fresh from the loan's own datum) lands on exactly
     * zero, while keeping the SAME oracle/wallet/config universe F855's other tests already prove.
     */
    private static Wiring convertWiring(ProtocolParamsSupplier payInAdvanceParams,
                                        LiquidationAssessment assessment) {
        AppConfig.LiquidationConfiguration configuration = shadow(SMALL_MARGIN);

        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                F855.loanUtxo(), F855.bondUtxo()));
        universe.addAll(F855.oracleUniverse());

        LiquidateTransactionBuilder builder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);

        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        FakeScanner scanner = new FakeScanner(List.of(assessment));

        Map<String, Utxo> unspent = new LinkedHashMap<>();
        unspent.put(assessment.loan().utxoRef(), F855.loanUtxo());
        unspent.put(assessment.bond().utxoRef(), F855.bondUtxo());
        FakeResolver resolver = new FakeResolver(unspent);

        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        CountingOracleProvider oracles = new CountingOracleProvider(
                new FakeOracleClient(List.of(F855.oracle())), new FakeOracleClient(List.of()));

        PayInAdvanceLiquidationRouter router = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), payInAdvanceParams));

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(List.of(WALLET_UTXO)), ACCOUNT, scanner, resolver, builder,
                router, LoanFixtures.registry(), log, oracles, previewNetwork(),
                LoanFixtures.protocolParams(), LoanFixtures.converters(), EXPLODING_SUBMITTER);
        return new Wiring(executor, log, scanner, resolver, oracles, blockEventListener);
    }

    /**
     * Task 8 (ADDENDA). Wired like {@link #convertWiring()} but with the market UNLISTED, so
     * {@code MarketGate.actionFor} answers CONVERT (Minswap) — the default per
     * {@code MarketGate}'s own javadoc — rather than the ANTICIPATE every other convert fixture in
     * this class forces via {@link #shadow}'s explicit {@code lovelace} market entry.
     * {@code convertRouter} is the {@link FakeConvertRouter} stand-in, so this reaches {@code record()}
     * — the OTHER {@code LiquidationDecision} construction site from {@link #decision} — under a
     * CONVERT-routed candidate.
     */
    private static Wiring convertViaMinswapWiring(Transaction canned) {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, SMALL_MARGIN, 200, 30);
        // markets left EMPTY: an unlisted market is action: CONVERT at the node's own mode.

        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        List<Utxo> universe = List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                convert.loan().utxo(), convert.bond().utxo());

        LiquidateTransactionBuilder plainBuilder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);

        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        FakeScanner scanner = new FakeScanner(List.of(convert.assessment()));
        FakeResolver resolver = new FakeResolver(allUnspent(List.of(convert)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        CountingOracleProvider oracles = noOracle();

        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(List.of(WALLET_UTXO)), ACCOUNT, scanner, resolver, plainBuilder,
                payInAdvanceRouter, new FakeConvertRouter(canned), LoanFixtures.registry(), log, oracles,
                previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                EXPLODING_SUBMITTER);
        return new Wiring(executor, log, scanner, resolver, oracles, blockEventListener);
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
     * Slice 1, task 2. Exit 3 (NO_UTXO) logged nothing at all before this: the recorded detail had
     * only the two booleans ("loan utxo present=%s, bond utxo present=%s"), which is not actionable —
     * "spent since the scan" only means something if an operator can look the ref up. The log line
     * must name the ACTUAL refs and say which of the two was missing.
     */
    @Test
    void aNoUtxoSkipLogsAtInfoNamingBothRefsAndWhichWasMissing() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        Map<String, Utxo> loanOnly = Map.of(scenario.loan().loan().utxoRef(), scenario.loan().utxo());

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(scenario.assessment()), List.of(scenario),
                loanOnly, List.of(WALLET_UTXO), noOracle(), false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, decision.outcome());

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains("spent since the scan"))
                .toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line for the missing utxo: "
                + appender.list);
        String message = infos.getFirst().getFormattedMessage();
        assertTrue(message.contains(TX_LOAN + "#0"), "must name the loan utxo ref: " + message);
        assertTrue(message.contains(TX_BOND + "#0"), "must name the bond utxo ref: " + message);
        assertTrue(message.contains("the bond utxo"),
                "must say WHICH of the two was missing, not just the booleans: " + message);
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

        // T-060 part 2: the orphan is SETTLED, not excluded. It leaves the live denominator and the
        // exclusions map, and lands in its own count — so the totals still reconcile to 3.
        assertEquals(2, wiring.log().lastRun().bondsScanned(), "only bonds with a LIVE loan count");
        assertEquals(1, wiring.log().lastRun().settled(), "the orphan is settled, not excluded");
        assertEquals(3, wiring.log().lastRun().bondsScanned() + wiring.log().lastRun().settled(),
                "every scanned bond is still accounted for — reclassified, not dropped");
        assertEquals(1, wiring.log().lastRun().buildable());
        assertEquals(Map.of(LiquidationExclusion.NOT_LIQUIDATABLE, 1),
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
     * A wallet with nothing usable in it still scans, prices and records the run — it just cannot
     * build.
     *
     * <h2>Why this reversed</h2>
     * This test previously asserted the opposite: that the loop stopped <em>before</em> the scan and
     * recorded nothing. The stated reason was sound as far as it went — better one warning an
     * operator can act on than a decision log full of misleading per-candidate refusals — but the
     * position it defended made an unfunded wallet indistinguishable from a quiet market. Nothing
     * scanned, nothing priced, no run recorded: the blind-bot shape this repo has now been bitten by
     * three times.
     * <p>
     * It bites hardest in SHADOW mode, whose entire purpose is to prove the bot can see and price
     * real loans <em>before</em> anyone funds a wallet or arms it. A gate that refuses to observe the
     * world until it could act on it makes the observation-only mode useless exactly when it is
     * wanted.
     * <p>
     * <b>The old concern is still honoured, and that is the point of the assertions below.</b> The
     * cycle records the aggregate run and returns before the per-candidate build loop, so the
     * decision log gains a run with counts — evidence the loans were seen and priced — and gains
     * <em>zero</em> per-candidate decisions. Observability without the flood.
     */
    @Test
    void aWalletWithNoCleanAdaUtxoStillScansAndRecordsTheRun() {
        Wiring wiring = wiringWithWallet(shadow(SMALL_MARGIN), scenario(FAT_FEE_PER_MILLE),
                List.of(WALLET_UTXO_WITH_DATUM, WALLET_UTXO_WITH_DATUM_HASH));

        wiring.executor().cycle(NOW);

        // The world WAS observed: scanned once, and the run recorded with its counts.
        assertEquals(1, wiring.scanner().scans, "an unfunded wallet must not suppress the scan");
        assertNotNull(wiring.log().lastRun().at(), "the run must be recorded even when nothing builds");

        // But no candidate was built, and no per-candidate refusal was logged.
        assertEquals(0, wiring.log().size(),
                "the build loop must not run, so the decision log must not fill with refusals that "
                        + "say nothing except 'the wallet is empty'");
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
        // This fixture's NPE has NO cause, so nothing here can tell causeChain(e) from e.toString():
        // one is a substring of the other. The simple-vs-qualified name is the only discrimination
        // available at this fixture, and the load-bearing proof for this site is the wrapped-cause
        // test below. Leaving this comment off is how the same hole was dug twice.
        assertTrue(first.detail().startsWith("NullPointerException"),
                "causeChain uses the SIMPLE name; e.toString() qualifies it: " + first.detail());
        assertEquals(1, wiring.executor().quarantinedCount());
        assertEquals(Set.of(TX_LOAN + "#0"), wiring.executor().quarantinedRefs());

        // A minute later, well inside the 30-minute quarantine.
        wiring.executor().cycle(NOW + 60_000L);

        // The skip now RECORDS (Outcome.QUARANTINED) instead of returning silently, so the proof of
        // "not built again" is the second decision naming the hold — which is strictly stronger than
        // the old count of one. A count of one was equally consistent with the loan having dropped
        // out of the scan altogether; the record says which.
        assertEquals(2, wiring.log().size(),
                "the second cycle must leave a record of WHY it did nothing");
        LiquidationDecision second = wiring.log().newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.QUARANTINED, second.outcome(),
                "the quarantined loan must not be built again while its quarantine holds");
        assertEquals(first.loanUtxoRef(), second.loanUtxoRef(), "and it must be about the same loan");
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

        assertEquals(LiquidationDecision.Outcome.QUARANTINED, onlyDecision(byRef).outcome(),
                "the ref-keyed quarantine must suppress the BUILD and say so — an unrecorded skip is "
                        + "indistinguishable from a loan that was never a candidate");
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

    /**
     * Slice 1, task 1. The quarantine skip used to log at DEBUG only — invisible on an INFO-level
     * node — and said nothing about WHY the hold exists or whether it survives a restart. Promoted to
     * INFO, and the log line must carry the SAME detail string the decision record does (reused, not
     * re-derived) plus the in-memory / restart fact the {@code ConcurrentHashMap} quarantine map
     * implies but does not say on its own.
     */
    @Test
    void aQuarantinedLoanLogsAtInfoNamingTheHoldAndItsInMemoryNature() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), scenario, false);
        wiring.executor().quarantineUntil(TX_LOAN + "#0", NOW + 3_600_000L);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.QUARANTINED, decision.outcome());

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains("quarantined"))
                .toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line for the quarantine hold: "
                + appender.list);
        String message = infos.getFirst().getFormattedMessage();
        assertTrue(message.contains(TX_LOAN + "#0"), "must name the loan utxo ref: " + message);
        assertTrue(message.contains(decision.detail()),
                "the log line must carry the SAME detail the record does, reused not re-derived: "
                        + message);
        assertTrue(message.toUpperCase().contains("IN-MEMORY"),
                "must say the hold does not survive a restart: " + message);
        assertTrue(message.toLowerCase().contains("restart"),
                "must name the restart remedy an operator would reach for: " + message);
    }

    // ======================================================================================
    // the executor's convert branch: routing + the two failure mappings (A3 Part 2)
    // ======================================================================================
    //
    // The A2 auditor found the executor's convert branch undefended: forcing it to `if (false)`
    // left the whole suite green, because no executor-level test fed a CONVERT assessment through
    // consider()/record(). These three tests close that — they exercise the routing selection, the
    // PayInAdvanceNotModelledException -> REFUSED mapping, and the genuine-exception -> quarantine
    // mapping at the executor layer, and every one of them goes RED under the `if (false)` mutation
    // (a convert assessment then reaches the plain builder, whose V7 guard refuses it as
    // CONVERSION_TO_PRINCIPAL_REQUIRED instead of routing / refusing-cleanly / quarantining).

    /**
     * (a) A <b>buildable</b> convert assessment — the real f855 shape — reaches the router, which
     * assembles a {@code LiquidateAndPayInAdvance} transaction that flows into the executor's normal
     * pricing + veto path and is recorded, held un-submitted by shadow mode. Nothing reaches the
     * exploding submitter.
     * <p>
     * The plain {@code Liquidate} builder refuses this loan outright (V7), so a <em>built</em> body is
     * itself proof the candidate went through the pay-in-advance router rather than the plain builder —
     * which is exactly what the {@code if (false)} mutation breaks.
     */
    @Test
    void aBuildableConvertLoanIsRoutedAndRecordedButNeverSubmitted() {
        Wiring wiring = convertWiring();

        wiring.executor().cycle(F855.NOW);

        assertEquals(1, wiring.scanner().scans);
        LiquidationDecision decision = onlyDecision(wiring);

        assertNotNull(decision.txHash(), decision.detail());
        assertNotNull(decision.txCborHex());
        assertEquals(LiquidationDecision.VARIANT_CONVERT, decision.variant(),
                "a convert loan must record its OWN variant — this assertion said Liquidate until "
                        + "2026-08-27 and was pinning the defect in place");
        assertEquals(F855.LOAN_ID, decision.loanId());
        // Shadow mode: a priced verdict (WOULD_SUBMIT or UNPROFITABLE), never a submit or a refusal.
        assertTrue(decision.outcome() == LiquidationDecision.Outcome.WOULD_SUBMIT
                        || decision.outcome() == LiquidationDecision.Outcome.UNPROFITABLE,
                "a routed convert tx is priced, not refused: " + decision.outcome() + " " + decision.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), decision.submitVeto(),
                "shadow mode is what held it, and nothing reached the submitter");
        assertNotEquals(LiquidationDecision.Outcome.SUBMITTED, decision.outcome());
        assertNotEquals(LiquidationDecision.Outcome.SUBMIT_FAILED, decision.outcome());
        // The pay-in-advance shape: both configs plus the oracle nft, its reference script and the c3
        // provider — five reference inputs the plain ada/ada path never carries.
        assertTrue(decision.referenceInputs() >= 5,
                "the convert body reads both configs and the three oracle reference inputs, got "
                        + decision.referenceInputs());
        assertEquals(0, wiring.executor().quarantinedCount(), "a clean build is not quarantined");
    }

    /**
     * (b) F0 (round 2) — REPURPOSED. This used to pin the ada/ada convert fixture's naturally-zero
     * equity as a clean REFUSED row; it is exactly backwards now. {@code loan_claim_action.ak:240-259}
     * accepts equity 0 outright — it is the validator's normal case and the common liquidation, so the
     * seam must build it, not refuse it (see {@code equityZeroNowBuildsAndIsRecordedRatherThanRefused}
     * below, which is what this test used to disprove).
     * <p>
     * What is STILL refused cleanly (never quarantined) is a genuinely NEGATIVE equity — unreachable
     * through a real assessment ({@code LoanFinance.redeemerEquity} floors it to zero), so this
     * overrides the assessment's equity field directly via {@link Scenario#withAssessment}, defence in
     * depth for the router's own precondition rather than a scenario the loan/oracle data produces.
     */
    @Test
    void aNegativeEquityAssessmentIsRefusedNotQuarantined() {
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        assertTrue(convert.bond().bond().datum().shouldLiquidationConvertToPrincipal(),
                "the fixture must be a convert bond, or the executor would not route it");
        LiquidationAssessment negativeEquity = LiquidationAssessment.buildable(
                convert.assessment().bond(), convert.assessment().loan(),
                "negative equity (defence in depth, unreachable through a real assessment)",
                convert.assessment().remainingDebt(), BigInteger.valueOf(-1),
                convert.assessment().late(), convert.assessment().liquidationFee());
        convert = convert.withAssessment(negativeEquity);

        Wiring wiring = wiring(shadow(SMALL_MARGIN), convert, false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("pay-in-advance not yet modelled for a negative equity", decision.reason());
        assertNull(decision.txHash(), "a clean not-modelled refusal builds no transaction");
        assertNull(decision.txCborHex());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "a shape the seam cannot model is a REFUSED row, never a quarantine");
    }

    /**
     * F0 (round 2) — THE POSITIVE CASE, and the whole point of this round: an equity-0 convert loan
     * (the live USDM loan's shape as of 2026-09-09) reaches {@code record()} and is priced, exactly
     * like the positive-equity f855 loan {@code aBuildableConvertLoanIsRoutedAndRecordedButNeverSubmitted}
     * already proves — never refused. {@link F855#equityZeroLoanDatum()} is tuned so
     * {@code builder.numbers()} (which the router calls fresh, never trusting
     * {@code assessment.equity()}) computes equity 0 against the SAME collateral amount and oracle
     * price the rest of {@code F855}'s tests use.
     * <p>
     * Mutant: restoring the old equity {@code <= 0} refusal in the router makes this test fail (a
     * REFUSED decision instead of a priced WOULD_SUBMIT/UNPROFITABLE one).
     */
    @Test
    void equityZeroNowBuildsAndIsRecordedRatherThanRefused() {
        LiquidationAssessment assessment = F855.assessment(F855.equityZeroLoanDatum(), BigInteger.ZERO);
        Wiring wiring = convertWiring(LoanFixtures.protocolParams(), assessment);

        wiring.executor().cycle(F855.NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertNotNull(decision.txHash(), decision.detail());
        assertTrue(decision.outcome() == LiquidationDecision.Outcome.WOULD_SUBMIT
                        || decision.outcome() == LiquidationDecision.Outcome.UNPROFITABLE,
                "an equity-0 convert tx is priced, not refused: " + decision.outcome() + " " + decision.detail());
        assertEquals(0, wiring.executor().quarantinedCount(), "a clean build is not quarantined (equityZero)");
    }

    // ======================================================================================
    // F3 (round 2) — THE EXECUTOR-LEVEL SCENARIO: nominate -> gate -> announce -> economics, with a
    // USDM+ada wallet AND a NON-UNIT principal feed. A 1:1 principal feed cannot distinguish the
    // two-feed WALL-1 composition from the old one-feed shortcut (M1b/M8's escape), and an ada-only
    // wallet cannot even reach the token nomination branch (M18) — this fixture is built specifically
    // so it cannot pass by taking either shortcut.
    // ======================================================================================

    private static final AssetType PRINCIPAL_TOKEN_2 = new AssetType("d0".repeat(28), "5553444d");
    private static final AssetType PRINCIPAL_TOKEN_2_ORACLE_NFT = new AssetType("d1".repeat(28), "555344"
            + "4d4f5241434c45");
    private static final String PRINCIPAL_TOKEN_2_CREDENTIAL = "d2".repeat(28);
    private static final String TX_PRINCIPAL_ORACLE_NFT = "d3".repeat(32);
    private static final String TX_PRINCIPAL_ORACLE_SCRIPT = "d4".repeat(32);
    private static final String TX_PRINCIPAL_C3_PROVIDER = "d5".repeat(32);
    private static final String TX_TOKEN_WALLET = "d6".repeat(32);

    /**
     * The fixture arithmetic, pinned by hand (collateralFeed = 50 lovelace/unit, principalFeed = 5
     * lovelace/unit — deliberately NOT 1:1, and deliberately DIFFERENT from the collateral's price, so
     * neither feed's ratio could stand in for the other by coincidence):
     * <pre>
     *   collateralAmount 1,000,000 TOK   -> collateralInLovelace   = 1,000,000 * 50 = 50,000,000
     *   remainingDebt    2,000,000 USDM  -> remainingDebtInLovelace = 2,000,000 * 5 = 10,000,000
     *   penalty (5%)                     -> 500,000
     *   equityInLovelace = 50,000,000 - 10,000,000 - 500,000 = 39,500,000
     *   equity (collateral currency)     = floor(39,500,000 / 50) = 790,000 TOK
     *   liquidationFee (5%, bond's OWN rate) = floor(1,000,000 * 50 / 1000) = 50,000 TOK
     *   collateralLenderShouldReceive    = 1,000,000 - 790,000 - 50,000 = 160,000 TOK
     *   converted (WALL 1, two-feed)     = ceil(160,000 * 50 / 5) = 1,600,000 USDM
     *   [the OLD one-feed shortcut would instead pay toLovelace(160,000, collFeed).ceil() =
     *    8,000,000 USDM — 5x too much; this is M1b/M8's escape route, closed by using a non-1:1 feed]
     *   botCollateral (S-15 rider)       = collateralAmount - equity = 1,000,000 - 790,000 = 210,000 TOK
     *   acquired (F1)  = value(210,000 TOK)   = 210,000 * 50 = 10,500,000 lovelace
     *   outlay   (F1)  = value(1,600,000 USDM) = 1,600,000 * 5 = 8,000,000 lovelace (+1, ceil-biased)
     *   net before tx fee / riders = 10,500,000 - 8,000,001 = 2,499,999 lovelace
     * </pre>
     */
    private static Scenario tokenPrincipalConvertEconomicsScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN_2, PRINCIPAL_TOKEN_2_ORACLE_NFT,
                BigInteger.valueOf(2_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), PRINCIPAL_TOKEN_2),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                principalFeed(), collateralFeed(), NOW);
        return new Scenario(loan, bond, assessment);
    }

    private static OraclePriceFeed principalFeed() {
        return OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN_2, BigInteger.valueOf(5), BigInteger.ONE,
                NOW - 60_000L, NOW + 600_000L);
    }

    private static OracleEntry principalOracle() {
        return LoanFixtures.charli3(PRINCIPAL_TOKEN_2, PRINCIPAL_TOKEN_2_ORACLE_NFT,
                PRINCIPAL_TOKEN_2_CREDENTIAL, principalFeed(),
                LoanFixtures.input(TX_PRINCIPAL_ORACLE_NFT, 0), LoanFixtures.input(TX_PRINCIPAL_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_PRINCIPAL_C3_PROVIDER, 0));
    }

    /**
     * ⛔ F2's OWN scenario: 3,000,000 USDM in the wallet against a ~1,600,000 payout — the leftover
     * ~1,400,000 comes back as the bot's own CHANGE, at the SAME address the S-15 collateral rider
     * lands at, in a DIFFERENT asset (USDM, never the collateral token). A rider filter keyed on "any
     * token at my address" cannot tell the two apart; one keyed on the COLLATERAL asset specifically
     * can (F2).
     */
    private static final Utxo TOKEN_WALLET_UTXO = LoanFixtures.utxo(TX_TOKEN_WALLET, 0,
            ACCOUNT.baseAddress(), List.of(
                    Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(LoanFixtures.unit(PRINCIPAL_TOKEN_2), BigInteger.valueOf(3_000_000L))),
            null);

    private static Wiring tokenPrincipalConvertEconomicsWiring() {
        return tokenPrincipalConvertEconomicsWiring(List.of(WALLET_UTXO, TOKEN_WALLET_UTXO));
    }

    /** As above, with the wallet contents made explicit — F7's own scenario overrides these. */
    private static Wiring tokenPrincipalConvertEconomicsWiring(List<Utxo> walletUtxos) {
        AppConfig.LiquidationConfiguration configuration = shadow(SMALL_MARGIN);
        configuration.setMarkets(List.of(anticipateMarket(PRINCIPAL_TOKEN_2.toUnit(), 1_500_000_000L)));

        // No evaluator is wired (offline, non-evaluating build — the same shape
        // theBuildPricesWithTheOracleSnapshotTheScanWasTakenWith uses), so reference inputs are
        // declared by COORDINATE only (from the OracleEntry objects the CountingOracleProvider below
        // hands out) and never fetched through the UtxoSupplier — the universe needs no physical
        // oracle utxos at all.
        Scenario convert = tokenPrincipalConvertEconomicsScenario();
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO,
                convert.loan().utxo(), convert.bond().utxo()));
        universe.addAll(walletUtxos);

        LiquidateTransactionBuilder plainBuilder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        FakeScanner scanner = new FakeScanner(List.of(convert.assessment()));
        FakeResolver resolver = new FakeResolver(allUnspent(List.of(convert)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        CountingOracleProvider oracles = new CountingOracleProvider(
                new FakeOracleClient(List.of(collateralOracle(), principalOracle())),
                new FakeOracleClient(List.of(collateralOracle(), principalOracle())));

        PayInAdvanceLiquidationRouter router = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));

        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT, scanner, resolver,
                plainBuilder, router, LoanFixtures.registry(), log, oracles, previewNetwork(),
                LoanFixtures.protocolParams(), LoanFixtures.converters(), EXPLODING_SUBMITTER);
        return new Wiring(executor, log, scanner, resolver, oracles, blockEventListener);
    }

    /**
     * ⛔ THE WHOLE SCENARIO, end to end: M18 (nominate must pick the USDM+ada utxo, not fail on an
     * ada-only one), M12 (the announce line must name USDM, never "ada"), M1b/M8 (the two-feed
     * composition, not the one-feed shortcut — provable only because the principal feed is non-1:1),
     * F1 (acquired credited, outlay debited — a real positive net, not the always-negative old
     * formula), F2 (the wallet's own leftover USDM change is not counted as a rider).
     */
    @Test
    void theExecutorLevelTokenPrincipalScenarioNominatesGatesAnnouncesAndPricesCorrectly() {
        Wiring wiring = tokenPrincipalConvertEconomicsWiring();

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertNotNull(decision.txHash(), "M18 — nominate() must pick the USDM+ada wallet utxo and "
                + "actually build; got: " + decision.detail());

        // ⛔ M18 — nominate() must ACTUALLY TAKE the token branch, named by its OWN nominated utxo and
        // log shape. This is NOT provable by "the build succeeded" alone: cardano-client-lib's own
        // balancer scans the whole wallet during balanceTx and will happily source the missing USDM
        // from TOKEN_WALLET_UTXO even if nominate() itself picked the WRONG (ada-only) input — measured
        // disabling the branch outright and finding every OTHER assertion in this test still passed.
        // The nominate() log line is the only observable that actually pins WHICH utxo was chosen and
        // by WHICH branch's logic (its message shape differs from the ada-only branch's).
        boolean nominatedTheTokenUtxoByName = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains(TX_TOKEN_WALLET)
                        && e.getFormattedMessage().contains("fee ceiling + min-ada rider"));
        assertTrue(nominatedTheTokenUtxoByName, "nominate() must log choosing " + TX_TOKEN_WALLET
                + " through its TOKEN branch (\"fee ceiling + min-ada rider\"), not fall through to the "
                + "ada-only branch and rely on the balancer to paper over the wrong nomination: "
                + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // M12 — the announce line must name USDM, never "ada", for a token principal.
        boolean announcedTheToken = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains(PRINCIPAL_TOKEN_2.toUnit())
                        && e.getFormattedMessage().contains("PAY-IN-ADVANCE"));
        assertTrue(announcedTheToken, "the PAY-IN-ADVANCE announce line must name " + PRINCIPAL_TOKEN_2.toUnit()
                + ", never \"ada\": " + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // F1/F2 — the economics detail carries the REPLACED bracket with the exact pinned figures.
        String detail = decision.detail();
        assertTrue(detail.contains("REPLACED (F1, token principal)"),
                "a token-principal candidate must go through the REPLACED acquired/outlay economics: "
                        + detail);
        assertTrue(detail.contains("acquired 210000 " + COLLATERAL_TOKEN.toUnit() + " (10500000 lovelace)"),
                "acquired must be the FULL botCollateral share (collateralAmount - equity), priced "
                        + "through PricingService — M1b/M8 killed by this being the two-feed number: "
                        + detail);
        assertTrue(detail.contains("outlay 1600000 " + PRINCIPAL_TOKEN_2.toUnit() + " (8000001 lovelace"),
                "outlay must be the two-feed WALL-1 payout (1,600,000 USDM, not the one-feed shortcut's "
                        + "8,000,000), ceil-biased by exactly +1 (F1's second mutant, M7b): " + detail);

        // ⛔ THE EXACT FLOOR, MEASURED AND PINNED — this is what makes F1's two mutants (dropping the
        // acquired credit, or dropping the outlay's ceil-bias) and F2's (counting the USDM change as
        // a rider) each change an OBSERVABLE number here, not just a bracket of text that could stay
        // correct-looking while the real arithmetic drifted. Measured once (txFee 1,206,413, min-ada
        // 2,245,350 — both protocol-param-derived and not hand-derivable), then pinned: floorProfit =
        // 10,500,000 - 8,000,001 - 1,206,413 - 2,245,350 = -951,764. Note this is UNPROFITABLE at
        // SMALL_MARGIN, which is itself informative: the OLD bug (subtracting the outlay alone against
        // a puny fee slice) would have been far more negative still — this fixture does not need to
        // clear a margin to prove the fix, only to prove the CORRECT number is being computed.
        assertTrue(detail.contains("= floor -951764"),
                "the exact pinned floorProfit — a mutant removing the acquired credit, weakening the "
                        + "outlay's ceil-bias, or counting the USDM change as a rider each move this "
                        + "number: " + detail);
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(),
                "pinned alongside the floor above — a routed convert tx is priced, not refused: " + detail);
        assertEquals(0, wiring.executor().quarantinedCount(), "a clean build is not quarantined: " + detail);
    }

    /**
     * F7 (round 2) — a token-carrying utxo with too little ada alongside it is NOT a candidate the
     * selector could ever actually pick, so it must not count toward the balance {@link MarketGate}
     * sees. 2,000 USDM base units against a bare 1.4 ADA — well short of {@code nominate()}'s own
     * {@code requiredAda} (fee ceiling + {@code TOKEN_PRINCIPAL_MIN_ADA_CEILING}) — must be excluded
     * entirely, so the gate sees balance ZERO, never 2,000. Kills M13 (balance summed over ALL
     * nominable-for-token utxos, ignoring the ada-sufficiency filter).
     */
    @Test
    void aTokenUtxoWithTooLittleAdaAlongsideItDoesNotCountTowardTheMarketBalance() {
        Utxo tooLittleAda = LoanFixtures.utxo("d7".repeat(32), 0, ACCOUNT.baseAddress(), List.of(
                Amount.lovelace(BigInteger.valueOf(1_400_000L)),
                Amount.asset(LoanFixtures.unit(PRINCIPAL_TOKEN_2), BigInteger.valueOf(2_000L))), null);
        Wiring wiring = tokenPrincipalConvertEconomicsWiring(List.of(WALLET_UTXO, tooLittleAda));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains("holds 0 "),
                "the 2,000-USDM-but-1.4-ADA utxo must not count at all — the gate must see zero, not "
                        + "2000: " + decision.detail());
        assertFalse(decision.detail().contains("holds 2000 "),
                "M13's exact escape: summing balance over every nominable-for-token utxo regardless of "
                        + "whether it carries enough ada to ever be selected: " + decision.detail());
    }

    /**
     * Slice 1, task 3. Exit 7 recorded REFUSED and logged NOTHING. The router's own message is
     * generic ("… for a negative equity" / "… no oracle entry for principal oracle asset") and never
     * says which asset — the log line must, because the operator's next question is always "which
     * loan, which token". The message itself is what lets a reader tell the two triggers apart, so it
     * must be carried verbatim. (F0, round 2: the equity trigger is now a genuinely negative equity,
     * not "non-positive" — equity 0 is buildable.)
     */
    @Test
    void aPayInAdvanceNotModelledRefusalLogsAtInfoNamingThePrincipalAsset() {
        // F0 (round 2) — a genuinely negative equity, overridden directly on the assessment (defence
        // in depth for the router's precondition; see aNegativeEquityAssessmentIsRefusedNotQuarantined
        // for why the ada/ada fixture's naturally-zero equity no longer refuses at all).
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        LiquidationAssessment negativeEquity = LiquidationAssessment.buildable(
                convert.assessment().bond(), convert.assessment().loan(),
                "negative equity (defence in depth, unreachable through a real assessment)",
                convert.assessment().remainingDebt(), BigInteger.valueOf(-1),
                convert.assessment().late(), convert.assessment().liquidationFee());
        convert = convert.withAssessment(negativeEquity);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), convert, false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("pay-in-advance not yet modelled for a negative equity", decision.reason());

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains("not yet modelled"))
                .toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line for the not-modelled refusal: "
                + appender.list);
        String message = infos.getFirst().getFormattedMessage();
        assertTrue(message.contains("lovelace"), "must name the principal asset (the unit): " + message);
        assertTrue(message.contains("negative equity"),
                "must carry the router's own message verbatim — it is what distinguishes the two "
                        + "triggers: " + message);
        // ⛔ AND THE REMEDY MUST BE ABSENT HERE. The principal IS ada, so "set this market to CONVERT"
        // is not merely unhelpful — `action` is a MARKET-level setting keyed by principal asset, so an
        // operator taking that advice re-routes EVERY loan in the market away from pay-in-advance on
        // the strength of one loan's equity sign. This assertion is the whole point of the pair: the
        // sibling test below proves the remedy DOES appear when it is the right advice.
        assertFalse(message.contains("action to CONVERT"),
                "a non-positive-equity refusal must NOT advise a market-wide routing change — the "
                        + "equity sign says nothing about the mechanism: " + message);
    }

    /**
     * ⛔ REPURPOSED, token-principals slice: {@code PayInAdvanceLiquidationRouter:143} — the outright
     * "non-ada principal" refusal this test used to pin — is GONE (Part 1 of that slice; a non-ada
     * principal is now modelled). The remaining {@code PayInAdvanceNotModelledException} trigger a
     * non-ada principal can still hit is "no oracle entry for principal oracle asset X" (WALL 3): the
     * executor's {@code oraclesByUnit} snapshot has nothing for the loan's own
     * {@code principalOracleAsset}. The remedy logic this test exists to pin ({@code action: CONVERT}
     * is sound advice for ANY non-ada-principal trigger, never for a non-positive-equity one) is
     * unchanged and still keyed on the principal asset alone, so it still fires here.
     */
    @Test
    void aNonAdaPrincipalNotModelledRefusalCarriesTheConvertRemedy() {
        Scenario convert = tokenPrincipalConvertScenario();
        AppConfig.LiquidationConfiguration configuration =
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, SMALL_MARGIN, 200);
        configuration.setMarkets(java.util.List.of(
                anticipateMarket(COLLATERAL_TOKEN.toUnit(), 1_000_000_000_000L)));
        Wiring wiring = wiring(configuration, convert, false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains("not yet modelled"))
                .toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line for the not-modelled refusal: "
                + appender.list);
        String message = infos.getFirst().getFormattedMessage();
        assertTrue(message.contains("no oracle entry for principal oracle asset"),
                "must carry the router's own message, which is what names this trigger: " + message);
        assertTrue(message.contains(COLLATERAL_TOKEN.toUnit()),
                "must name the principal unit that makes the remedy apply: " + message);
        assertTrue(message.contains("action to CONVERT"),
                "a non-ada-principal refusal MUST carry the remedy — it is the one trigger the advice "
                        + "is correct for: " + message);
        // REMEDY WORDING (Machine Owner ruling, 2026-09-09) — "routable" is not "profitable": for the
        // live USDM loan the FLDT/USDM pool returns ~827M against a 980M minimum_receive, so CONVERT
        // would build, submit and REFUND rather than fill the debt. The remedy must carry that caveat
        // until PR3's POOL_TOO_THIN pre-check exists, or it reads as an unconditional fix it is not.
        assertTrue(message.contains("only if a Minswap pool can fill the debt")
                        && message.contains("thin pool refunds rather than fills"),
                "the CONVERT remedy must carry the pool-depth caveat, not read as unconditional advice: "
                        + message);
    }

    /**
     * A convert-flagged loan whose PRINCIPAL is a token, with equity forced strictly positive so the
     * router's equity precondition clears and the NEXT gate — WALL 3's missing-principal-oracle
     * refusal — is what actually fires (no oracle entry is wired for {@code COLLATERAL_TOKEN} as a
     * PRINCIPAL in this fixture's {@code oraclesByUnit}, only, where applicable, as a collateral).
     */
    private static Scenario tokenPrincipalConvertScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(COLLATERAL_TOKEN, BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum,
                COLLATERAL_LOVELACE, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.convertToPrincipalBondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), COLLATERAL_TOKEN),
                2_000_000L);

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);
        LiquidationAssessment positiveEquity = LoanFixtures.withNumbers(assessment,
                assessment.remainingDebt(), BigInteger.ONE, assessment.liquidationFee());
        return new Scenario(loan, bond, positiveEquity);
    }

    /**
     * (c) A convert assessment that clears the router's preconditions (ada principal, positive equity)
     * but whose {@code builder.build} then throws a <em>genuine</em> exception — the machinery failing,
     * not a verdict on the loan — is mapped to the QUARANTINE path, exactly like the plain path's
     * machinery-failure branch. Here the positive-equity ada-collateral convert loan carries no oracle
     * entry for its (nominal) collateral leg, so the promoted builder dereferences a null oracle
     * ({@code NullPointerException}); in production the same branch would catch a Blockfrost timeout.
     */
    @Test
    void aConvertAssessmentWhoseBuildThrowsIsQuarantined() {
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        // Force a strictly positive equity so the router clears both preconditions (ada principal +
        // positive equity) and actually calls the promoted builder, which then throws.
        LiquidationAssessment positiveEquity = LoanFixtures.withNumbers(convert.assessment(),
                convert.assessment().remainingDebt(), BigInteger.ONE, convert.assessment().liquidationFee());
        Scenario tampered = convert.withAssessment(positiveEquity);

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(tampered.assessment()), List.of(tampered),
                allUnspent(List.of(tampered)), List.of(WALLET_UTXO), noOracle(), false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertNull(decision.txHash(), "the build threw, so nothing was routed");
        assertEquals(1, wiring.executor().quarantinedCount(),
                "a genuine build failure quarantines, unlike a clean not-modelled refusal");
        assertEquals(Set.of(TX_LOAN + "#0"), wiring.executor().quarantinedRefs());
        // The refusal must SAY WHY. The old code recorded e.getMessage(), which is null for this
        // message-less NPE, leaving the operator debugging blind — the exact defect Giovanni hit. The
        // detail now carries the cause chain, so it is non-null and names the fault even with no message.
        assertEquals("NullPointerException", decision.reason(),
                "the refusal names the root-cause class");
        assertNotNull(decision.detail(),
                "a build-failure refusal must carry a detail — the old e.getMessage() was null here");
        assertTrue(decision.detail().contains("NullPointerException"),
                "the cause chain surfaces the real fault: " + decision.detail());
        // Cause-less fixture — see the plain path's twin: this assertion discriminates only on the
        // simple-vs-qualified name, and the wrapped-cause test below is what really defends this site.
        assertTrue(decision.detail().startsWith("NullPointerException"),
                "causeChain uses the SIMPLE name; e.toString() qualifies it: " + decision.detail());
    }

    // ======================================================================================
    // failure surfacing — the swallowed-cause fix (causeChain / rootReason)
    // ======================================================================================

    /**
     * Giovanni's exact shape: the pay-in-advance builder wraps the real fault as
     * {@code IllegalStateException("cannot build the pay-in-advance transaction", realCause)}. The old
     * executor recorded only the wrapper's message and dropped the cause, so the decision read
     * "cannot build the pay-in-advance transaction" with nothing actionable. The chain keeps the
     * wrapper AND surfaces the real cause; the reason names the root, not the wrapper.
     */
    @Test
    void causeChainSurfacesTheWrappedRealCauseNotJustTheWrapper() {
        Exception real = new IllegalArgumentException("not enough funds to front the pay-in-advance principal");
        Exception wrapped = new IllegalStateException("cannot build the pay-in-advance transaction", real);

        String chain = LiquidationExecutor.causeChain(wrapped);

        assertTrue(chain.contains("cannot build the pay-in-advance transaction"),
                "the wrapper message is kept: " + chain);
        assertTrue(chain.contains("not enough funds to front the pay-in-advance principal"),
                "and the REAL cause is surfaced, not swallowed: " + chain);
        assertEquals("IllegalArgumentException", LiquidationExecutor.rootReason(wrapped),
                "the reason names the root cause, not the wrapper");
    }

    /** A message-less exception still yields a non-null, class-named detail — never a null refusal. */
    @Test
    void causeChainIsNeverNullForAMessagelessException() {
        assertEquals("NullPointerException", LiquidationExecutor.causeChain(new NullPointerException()),
                "a message-less exception contributes its class name, never a null detail");
        assertEquals("NullPointerException", LiquidationExecutor.rootReason(new NullPointerException()));
    }

    /**
     * A pathological cyclic cause chain (a caused-by b caused-by a) must not hang the executor's error
     * path. The depth bound is what guarantees termination — remove it and this test hangs.
     */
    @Test
    void causeChainTerminatesOnACyclicCauseChain() {
        Exception a = new RuntimeException("a");
        Exception b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);

        String chain = LiquidationExecutor.causeChain(a);

        assertTrue(chain.startsWith("RuntimeException: a"), chain);
        assertTrue(chain.length() < 500, "the bounded walk cannot run away on a cycle: " + chain.length());
        assertNotNull(LiquidationExecutor.rootReason(a), "rootReason must also terminate on a cycle");
    }

    /** Audit residue: {@code rootCause} used to deref {@code getCause()} before any null check. */
    @Test
    void rootReasonAndCauseChainAreTotalOnANullThrowable() {
        assertEquals("", LiquidationExecutor.rootReason(null), "must not NPE on a null throwable");
        assertEquals("", LiquidationExecutor.causeChain(null));
    }

    // ======================================================================================
    // the outer net (T-036): consider() throwing OUTSIDE its own build try/catch
    // ======================================================================================

    /**
     * A "buildable" assessment ({@code exclusion == null}) whose {@link LiquidationAssessment#loan()}
     * is {@code null} — the shape {@link LiquidationExecutor}'s outer-catch comment names explicitly.
     * The model's own javadoc says a null loan is only ever paired with
     * {@code LiquidationExclusion#LOAN_NOT_FOUND}, so a real scanner never produces this; it is
     * constructed directly here to drive the defence-in-depth the outer catch exists for.
     * <p>
     * {@code consider()}'s very first statement is {@code assessment.loan().utxoRef()}, before any
     * try block, so this NPEs straight out of {@code consider()} and is caught only by the cycle
     * loop's outer net — never by either build-path {@code catch}.
     */
    private static LiquidationAssessment nullLoanAssessment() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        return LiquidationAssessment.buildable(honest.bond().bond(), null,
                "buildable per the scanner, but the join dropped the loan",
                honest.assessment().remainingDebt(), honest.assessment().equity(), false,
                honest.assessment().liquidationFee());
    }

    /**
     * The outer net is the ONLY observability for this failure — it records no decision — so the log
     * line is what this test proves. Before T-036 the two lines were
     * {@code log.warn(..., e.toString())} + {@code log.debug(..., e)}: a generic message at WARN and
     * the real exception buried at DEBUG, invisible on an INFO-level node. Now it is a single ERROR
     * line carrying the full cause chain plus the exception itself.
     */
    @Test
    void anExceptionOutsideTheBuildTryIsCaughtByTheOuterNetAndLoggedAtErrorWithTheCause() {
        LiquidationAssessment assessment = nullLoanAssessment();
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(assessment), List.of(), Map.of(),
                List.of(WALLET_UTXO), noOracle(), false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);

            // The tell that this is genuinely the OUTER net and not the build-path catch: no decision
            // was recorded for it at all — consider() threw before it ever produced one.
            assertEquals(0, wiring.log().size(),
                    "the outer net records NO decision — that is exactly why the log is the only "
                            + "observability for this failure");

            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertEquals(1, errors.size(), "expected exactly one ERROR event from the outer net: "
                    + appender.list);
            ILoggingEvent event = errors.getFirst();
            assertTrue(event.getFormattedMessage().contains("could not consider bond"),
                    "must name the operation: " + event.getFormattedMessage());
            assertTrue(event.getFormattedMessage().contains("NullPointerException"),
                    "must surface the real cause, not just a generic message: "
                            + event.getFormattedMessage());
            // Cause-less fixture: this pair discriminates only on the simple-vs-qualified name. The
            // wrapped-cause test below is what proves this site actually walks the chain.
            assertFalse(event.getFormattedMessage().contains("java.lang.NullPointerException"),
                    "causeChain uses the SIMPLE name; e.toString() qualifies it: "
                            + event.getFormattedMessage());
            assertNotNull(event.getThrowableProxy(),
                    "the exception itself must be attached for the stack trace, not just the message");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * <b>T-052 — a fee-only liquidation nominates the SMALL utxo, leaving the large ones for the
     * candidates that need them.</b>
     * <p>
     * Giovanni's rule 5: <i>"the amount of ada a liquidator might spend might be little, just tx fee
     * or something, so a 5 ada utxo would be perfect."</i> On the plain path the collateral funds
     * every output, so the only ada the bot brings is the fee — measured here at 2,405,077 lovelace
     * against the fixture's protocol parameters, which the 5-ada utxo covers comfortably.
     * <p>
     * ⚠ <b>This test used to assert the opposite</b> — that the LARGEST was nominated — and the
     * reason it did is still true and still guarded: the nominated utxo is the only wallet input the
     * builder declares, and cardano-client-lib evaluates script cost BEFORE balancing can add any
     * more, so it must cover the transaction alone. Blockfrost refuses one that cannot with
     * {@code EvaluationFailure} and an EMPTY {@code ScriptFailures} map, which reads as anything but
     * a funding problem (measured on preview 2026-08-24: a 776-ada wallet across 14 utxos with a
     * 5-ada one listed first, and every CONVERT liquidation failing on it).
     * <p>
     * <b>T-052 keeps that guarantee and stops paying for it with the biggest input:</b> the
     * requirement is computed instead of over-provisioned. The convert half — where the requirement
     * really is large — is asserted in {@code PayInAdvanceLiquidationRouterTest}, and the selection
     * arithmetic in {@code WalletInputSelectionTest}.
     */
    @Test
    void aFeeOnlyLiquidationNominatesTheSmallestSufficientUtxo() {
        Utxo small = LoanFixtures.adaUtxo("11".repeat(32), 0, ACCOUNT.baseAddress(), 5_000_000L);
        Utxo large = LoanFixtures.adaUtxo("22".repeat(32), 0, ACCOUNT.baseAddress(), 58_384_544L);
        Utxo middling = LoanFixtures.adaUtxo("33".repeat(32), 0, ACCOUNT.baseAddress(), 38_839_574L);
        Scenario honest = scenario(FAT_FEE_PER_MILLE);

        // Deliberately listed smallest-first, the order that produced the live failure.
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(small, large, middling), noOracle(), false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        String chosen = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("nominates wallet utxo"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the executor must say which utxo it picked: "
                        + appender.list));
        assertTrue(chosen.contains("11".repeat(32)),
                "a fee-only liquidation must take the small sufficient utxo: " + chosen);
        assertFalse(chosen.contains("22".repeat(32)) || chosen.contains("33".repeat(32)),
                "the large utxos must be left for candidates that need them: " + chosen);

        // ⇒ AND IT MUST STILL COVER WHAT IT WAS CHOSEN AGAINST. This is the half the old
        // largest-first rule bought by over-provisioning; asserting it here keeps the guarantee
        // attached to the new mechanism instead of to a heuristic that happened to imply it.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\((\\d+) lovelace\\) for a requirement of (\\d+)").matcher(chosen);
        assertTrue(m.find(), "the nomination line must state both figures: " + chosen);
        assertTrue(Long.parseLong(m.group(1)) >= Long.parseLong(m.group(2)),
                "the nominated utxo does not cover the requirement it was chosen against: " + chosen);
    }

    /** A reference-script or datum-bearing utxo is still excluded, however large it is. */
    @Test
    void theLargestUtxoIsStillSubjectToEveryEligibilityRule() {
        Utxo huge = Utxo.builder()
                .txHash("44".repeat(32)).outputIndex(0).address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(900_000_000L))))
                .referenceScriptHash("9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d")
                .build();
        Utxo modest = LoanFixtures.adaUtxo("55".repeat(32), 0, ACCOUNT.baseAddress(), 60_000_000L);
        Scenario honest = scenario(FAT_FEE_PER_MILLE);

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(huge, modest), noOracle(), false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        String chosen = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("nominates wallet utxo"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a nomination: " + appender.list));
        assertTrue(chosen.contains("55".repeat(32)),
                "largest-first must not override the reference-script exclusion: " + chosen);
    }

    // ======================================================================================
    // the vacuity fix: these three sites must surface a WRAPPED cause, not just toString()
    //
    // 943960d (both build paths) and d4b2192 (the outer net) each replaced e.toString()/e.getMessage()
    // with causeChain(e), but every test defending them seeded a CAUSE-LESS exception. For those,
    // causeChain(e) is a substring of e.toString(), so re-applying the exact mutant the fixes exist to
    // kill — causeChain(e) -> e.toString() at all three sites — left the whole suite GREEN. Proven by
    // running it, 2026-08-24: 53 tests, 0 failures, mutant survived. The cause-chain half of all three
    // fixes was undefended decoration, exactly as on the T-037 submit sites before 9539fb3.
    //
    // Each test below wraps a real fault and asserts on the ROOT cause's message, which no wrapper's
    // toString() ever contains. That, not a green suite, is what makes these tests load-bearing.
    // ======================================================================================

    /**
     * The convert build path. This is Giovanni's own failure shape and the reason 943960d exists: the
     * refusal read "cannot build the pay-in-advance transaction" and the real cause was discarded.
     * <p>
     * Nothing here is mocked. The pay-in-advance builder is handed a protocol-params supplier that
     * throws, which fails <em>inside</em> that builder's own {@code try}, so the wrapper the executor
     * catches — {@code IllegalStateException("cannot build the pay-in-advance transaction", cause)} —
     * is raised by production code at {@code LiquidatePayInAdvanceTransactionBuilder:502}. The fixture
     * supplies the fault; the builder earns the wrapping.
     */
    @Test
    void aConvertBuildFailureSurfacesTheRootCauseBehindTheProductionWrapper() {
        Wiring wiring = convertWiring(throwingParams());

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            // F855.NOW, not NOW: the frozen fixture's oracle feed is only valid around its own
            // instant, and at any other one the numbers this builder derives are not the f855 ones.
            wiring.executor().cycle(F855.NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertNull(decision.txHash(), "the build threw, so nothing was routed");
        assertEquals(1, wiring.executor().quarantinedCount(),
                "a genuine build failure quarantines — unchanged by the surfacing fix");

        // The production wrapper really is in the chain: this is the string that used to be the WHOLE
        // detail, and keeping it is what makes the next assertion a statement about walking the chain
        // rather than about having replaced one message with another.
        assertTrue(decision.detail().contains("cannot build the pay-in-advance transaction"),
                "the wrapper is kept: " + decision.detail());
        // THE DISCRIMINATOR. toString() on that wrapper stops at its own message; only a walked chain
        // reaches this. Revert the site to e.toString() and this line is what fails.
        assertTrue(decision.detail().contains(BLOCKFROST_TIMEOUT),
                "the detail must carry the ROOT cause, not just the wrapper: " + decision.detail());
        assertEquals("SocketTimeoutException", decision.reason(),
                "the reason names the ROOT cause, never the IllegalStateException wrapper");

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event: " + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains(BLOCKFROST_TIMEOUT),
                "the log must surface the ROOT cause too, not just toString() of the wrapper: "
                        + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "the exception itself must be attached for the stack trace, not just the message");
    }

    /**
     * The plain build path. Its branch comment names this exact failure — "a Blockfrost timeout
     * fetching protocol params, say" — so the fixture is that sentence made executable, with the
     * timeout wrapped the way a transport client wraps one.
     */
    @Test
    void aPlainBuildFailureSurfacesTheRootCauseNotJustTheWrapper() {
        // The wrapper shape a transport client really produces. It has to be stubbed rather than
        // provoked: the plain builder turns everything its own context.build() can throw into a named
        // RefusedException — a DIFFERENT executor branch, which is why a throwing params supplier
        // lands as TRANSACTION_NOT_BUILDABLE and never reaches the catch under test here. The convert
        // sibling above is the one that proves production earns its wrapper; this test isolates the
        // executor's catch, which is all that is in question at this site.
        RuntimeException boom = new IllegalStateException("cannot fetch protocol parameters",
                new java.net.SocketTimeoutException(BLOCKFROST_TIMEOUT));
        LiquidateTransactionBuilder brokenBuilder = mock(LiquidateTransactionBuilder.class);
        when(brokenBuilder.build(any(LiquidateTransactionBuilder.Request.class))).thenThrow(boom);

        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false, false,
                LoanFixtures.protocolParams(), null, brokenBuilder);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals(1, wiring.executor().quarantinedCount(),
                "a machinery failure quarantines — unchanged by the surfacing fix");

        // THE DISCRIMINATOR, as above: unreachable from toString() on the outermost exception.
        assertTrue(decision.detail().contains(BLOCKFROST_TIMEOUT),
                "the detail must carry the ROOT cause, not just the wrapper: " + decision.detail());
        assertEquals("SocketTimeoutException", decision.reason(),
                "the reason names the ROOT cause, not whatever wrapped it");

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event: " + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains(BLOCKFROST_TIMEOUT),
                "the log must surface the ROOT cause too: " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "the exception must be attached for the stack trace");
    }

    /**
     * The outer net (d4b2192). Its sibling test drives a cause-less NPE, which cannot tell
     * {@code causeChain(e)} from {@code e.toString()}; this one drives a wrapped fault through the
     * same net. The seam is the first loan resolution, which {@code consider()} performs before it
     * enters either build {@code try} — so this can only be the outer net, and the zero-decisions
     * assertion below is what proves that.
     * <p>
     * The net records no decision at all, so the log line is the only observability this failure has.
     */
    @Test
    void theOuterNetSurfacesTheRootCauseBehindAWrapper() {
        RuntimeException boom = new IllegalStateException("the local index is not readable",
                new java.io.IOException("index segment 000042 is corrupt"));
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false, false,
                LoanFixtures.protocolParams(), boom, null);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(0, wiring.log().size(),
                "the outer net records NO decision — the log is the only observability, which is "
                        + "exactly why its content is the thing under test");

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event: " + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains("could not consider bond"),
                "must name the operation: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("the local index is not readable"),
                "the wrapper is kept: " + event.getFormattedMessage());
        // THE DISCRIMINATOR: toString() on the wrapper stops one link short of this.
        assertTrue(event.getFormattedMessage().contains("index segment 000042 is corrupt"),
                "must surface the ROOT cause, not just toString() of the wrapper: "
                        + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "the exception must be attached for the stack trace");
    }

    /**
     * T-040, the FIFTH site — found while building the plain-path test above, not by re-auditing.
     * <p>
     * A {@code RefusedException} is normally the builder <em>working</em>: a verdict on the candidate,
     * and forty-eight of the builder's fifty refusals carry no cause at all. But TWO of them wrap a
     * real failure — {@code SCRIPT_COST_EVALUATION_FAILED} and {@code TRANSACTION_NOT_BUILDABLE}, both
     * raised from the catch around {@code context.build()}. Recording only {@code e.getMessage()} threw
     * that cause away, so "Blockfrost is unreachable" and "this loan is not liquidatable" reached the
     * operator as the same kind of row.
     * <p>
     * Nothing is mocked: a protocol-params supplier that throws IS the natural way into that branch,
     * which is exactly how this site was discovered.
     */
    @Test
    void aRefusalWrappingARealFailureSurfacesTheCauseInsteadOfReadingLikeAVerdict() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false, false,
                throwingParams(), null, null);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals(LiquidateTransactionBuilder.Refusal.TRANSACTION_NOT_BUILDABLE.name(),
                decision.reason(), "recorded under the builder's own Refusal name, unchanged");
        assertEquals(0, wiring.executor().quarantinedCount(),
                "a refusal is not a machinery failure and is still not quarantined");
        // THE DISCRIMINATOR: the refusal's own message stops at the wrapper.
        assertTrue(decision.detail().contains(BLOCKFROST_TIMEOUT),
                "the detail must carry the cause underneath the refusal: " + decision.detail());

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(),
                "a refusal that wraps a failure gets exactly one ERROR line: " + appender.list);
        assertTrue(errors.getFirst().getFormattedMessage().contains(BLOCKFROST_TIMEOUT),
                "which must name the root cause: " + errors.getFirst().getFormattedMessage());
        assertNotNull(errors.getFirst().getThrowableProxy(), "with the exception attached");
    }

    /**
     * The other half of the same rule: a refusal that is a genuine verdict on the candidate — no cause
     * — keeps its clean message and produces NO error log. Forty-eight of the fifty refusals are this
     * shape, and turning them all into ERROR rows would bury the two that matter.
     *
     * <p>Slice 1, task 7 (ADDENDA): before this slice the causeless forty-eight logged NOTHING at any
     * level — {@code if (e.getCause() != null)} was the ENTIRE log statement. The remedy is a level,
     * not a flood: INFO for the causeless verdict, ERROR kept for the two that carry a fault (proven
     * by the sibling test above). So this test now asserts BOTH halves: still no ERROR line, and now
     * exactly one INFO line naming the refusal and carrying its detail.
     */
    @Test
    void aPlainVerdictRefusalLogsAtInfoInsteadOfSilence() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Scenario tampered = honest.withAssessment(LoanFixtures.withNumbers(honest.assessment(),
                honest.assessment().remainingDebt(), honest.assessment().equity(),
                honest.assessment().liquidationFee().add(BigInteger.ONE)));

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(tampered.assessment()), List.of(tampered),
                false);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidateTransactionBuilder.Refusal.LIQUIDATION_FEE_NOT_REPRODUCIBLE.name(),
                decision.reason());
        assertFalse(decision.detail().contains("\u21d0"),
                "a cause-less refusal keeps the builder's own message, with no chain appended: "
                        + decision.detail());
        assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.ERROR),
                "a clean verdict must not produce an ERROR line: " + appender.list);

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains("was refused as"))
                .toList();
        assertEquals(1, infos.size(), "the causeless forty-eight must now log at INFO, not silently: "
                + appender.list);
        String message = infos.getFirst().getFormattedMessage();
        assertTrue(message.contains(
                        LiquidateTransactionBuilder.Refusal.LIQUIDATION_FEE_NOT_REPRODUCIBLE.name()),
                "must name the refusal reason: " + message);
        assertTrue(message.contains(decision.detail()),
                "must carry the same detail the record does: " + message);
    }

    /**
     * T-040, the serialisation catch. The most surprising omission of the family: the exception reaches
     * NOTHING else — the recorded decision carries the pricing arithmetic, not the fault — so this log
     * line is the only place the cause can ever appear. It used to be WARN, with e.toString() and no
     * throwable.
     * <p>
     * The transaction under test is a REAL built one, re-read from the cbor a clean cycle produced and
     * spied so that only {@code serialize()} fails. Nothing about the pricing that precedes it is
     * fabricated — the executor reads the real fee off the real body.
     */
    @Test
    void aTransactionThatCannotBeSerialisedIsLoggedAtErrorWithTheRootCause() throws Exception {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring clean = wiring(shadow(SMALL_MARGIN), honest, false);
        clean.executor().cycle(NOW);
        String cborHex = onlyDecision(clean).txCborHex();
        assertNotNull(cborHex, "the fixture must build a real transaction to spy on");

        Transaction real = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
        Transaction broken = spy(real);
        doThrow(new CborSerializationException("the witness set could not be encoded",
                new java.io.IOException("cbor writer refused a 4-byte tag")))
                .when(broken).serialize();

        LiquidateTransactionBuilder unserialisable = mock(LiquidateTransactionBuilder.class);
        when(unserialisable.build(any(LiquidateTransactionBuilder.Request.class))).thenReturn(broken);

        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false, false,
                LoanFixtures.protocolParams(), null, unserialisable);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationExecutor.SubmitVeto.TX_TOO_LARGE.name(), decision.submitVeto(),
                "a transaction that cannot be measured cannot be cleared — the verdict is unchanged");

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR event: " + appender.list);
        ILoggingEvent event = errors.getFirst();
        assertTrue(event.getFormattedMessage().contains("could not serialise"),
                "must name the operation: " + event.getFormattedMessage());
        // THE DISCRIMINATOR: toString() on the wrapper stops one link short of this.
        assertTrue(event.getFormattedMessage().contains("cbor writer refused a 4-byte tag"),
                "must surface the ROOT cause, not just toString(): " + event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(),
                "and the throwable must be attached — the old WARN passed none");
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

    /**
     * A census reporting <b>nothing unreadable</b> — the healthy world these fakes model. T-060: a
     * non-zero {@code unreadable} would mean some bond's LOAN_NOT_FOUND is a loan we cannot read
     * rather than one that is gone, and no fake here is exercising that.
     */

    // ======================================================================================
    // T-060 — a settled loan and an unreadable one must not read the same
    // ======================================================================================

    /**
     * ⛔ <b>When a loan is present but unreadable, the scan line says the histogram is
     * CONTAMINATED.</b>
     * <p>
     * {@code LOAN_NOT_FOUND} is raised whenever no loan in our index matches the bond, which
     * collapses "the loan was settled and its NFT burned" together with "the loan is alive, indexed,
     * and this node cannot decode it". The second is blindness — and the loans datum decoder is
     * hand-written, so an unmodelled shape produces exactly it. Without this line, the fix Giovanni
     * asked for (stop counting them) would hide it permanently.
     */
    @Test
    void anUnreadableLoanMakesTheScanLineSayTheHistogramIsContaminated() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false);
        wiring.scanner().unreadable = 2;

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        String scanLine = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("liquidation scan:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scan line: " + appender.list));
        assertTrue(scanLine.contains("2 LOAN UTXO(S) UNREADABLE"),
                "the count of loans we cannot read must be ON the scan line, beside the exclusions "
                        + "it contaminates — not in a separate message an operator may never "
                        + "correlate: " + scanLine);
        assertTrue(scanLine.contains("CONTAMINATED"),
                "and it must say what that MEANS for the LOAN_NOT_FOUND count: " + scanLine);
    }

    /**
     * ⇒ And the converse, which is the half that makes the fix safe: with nothing unreadable, the
     * line says so <b>positively</b>. The absence of a warning is not evidence; an explicit zero is.
     * This is the reading that licenses treating {@code LOAN_NOT_FOUND} as settled loans.
     */
    @Test
    void aFullyReadableLoanPopulationSaysSoPositively() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            wiring.executor().cycle(NOW);
        } finally {
            logger.detachAppender(appender);
        }

        String scanLine = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("liquidation scan:"))
                .findFirst().orElseThrow();
        assertTrue(scanLine.contains("were readable"),
                "a clean population must be stated, not merely left unmentioned: " + scanLine);
        assertFalse(scanLine.contains("CONTAMINATED"), scanLine);
    }

    // ======================================================================================
    // T-061 — a refusal names every gate it can see, and no gate it cannot
    // ======================================================================================

    /**
     * ⛔ <b>The live refusal named ONE of TWO gates and offered a remedy that does not work.</b>
     * <p>
     * It said <i>"fund the wallet with one ada-only utxo of at least that amount"</i>, where "that
     * amount" was the fee ceiling. Doing exactly that clears the spend gate and then fails the
     * <b>collateral</b> one, which T-050 made a separate selection with a separate — and larger —
     * ceiling, summed across several utxos rather than found in one. The operator spends a funding
     * transaction and returns to the same message.
     * <p>
     * <b>A remedy is a claim.</b> If a message tells you what to do, doing it must be sufficient, or
     * the message must say it is not.
     */
    @Test
    void aWalletRefusalNamesBothTheSpendAndTheCollateralGate() {
        Utxo tiny = LoanFixtures.adaUtxo("77".repeat(32), 0, ACCOUNT.baseAddress(), 1_000_000L);
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(tiny), noOracle(), false);

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        String detail = decision.detail();
        assertTrue(detail.contains("(1) SPEND INPUT"),
                "the spend gate must be named: " + detail);
        assertTrue(detail.contains("(2) COLLATERAL"),
                "THE COLLATERAL GATE MUST BE NAMED TOO — funding for the first does not satisfy it: "
                        + detail);
        assertTrue(detail.contains("TOTAL across"),
                "and it must say the collateral requirement is a TOTAL across utxos, not a single "
                        + "utxo like the spend gate — an operator who reads it as 'one more utxo of "
                        + "that size' is back where they started: " + detail);
        assertFalse(detail.contains("Fund the wallet with one ada-only utxo of at least that amount"),
                "the old single-gate remedy must be gone, not merely supplemented: " + detail);
    }

    /**
     * ⚠ The other half, and it is the one that keeps the message honest: <b>it may only name gates it
     * has actually evaluated.</b> With the protocol parameters unavailable neither ceiling can be
     * sized, so the refusal must say so rather than print a plausible figure. <b>Listing a
     * requirement nobody computed is a worse failure than listing too few</b>, because an operator
     * cannot tell a measured number from an invented one.
     */
    @Test
    void withNoProtocolParametersTheRefusalNamesNoFiguresRatherThanGuessing() {
        String detail = LiquidationExecutor.walletDiagnosis(
                List.of(LoanFixtures.adaUtxo("88".repeat(32), 0, ACCOUNT.baseAddress(), 1_000_000L)),
                Optional.empty(), Optional.empty(), BigInteger.ZERO);

        assertTrue(detail.contains("could not be fetched")
                        && detail.contains("names no figure rather than guessing one"),
                "it must say WHY no figure is given, not merely omit one — an omission reads as "
                        + "'no requirement', which is the opposite of the truth: " + detail);
        assertTrue(detail.contains("1000000"),
                "what it CAN see — the largest nominable holding — is still reported: " + detail);
        assertFalse(detail.contains("(1) SPEND INPUT") || detail.contains("(2) COLLATERAL"),
                "it must not present numbered gates whose figures it never computed: " + detail);
    }

    /**
     * And the collateral gate states a TOTAL across a bounded number of utxos, because that is what
     * {@code collateralInputsFor} actually does. Reporting it as a single-utxo requirement would be
     * the same masking defect wearing a second gate's name.
     */
    @Test
    void theCollateralGateIsReportedAsATotalAcrossABoundedNumberOfUtxos() {
        String gate = LiquidationExecutor.collateralGate(
                List.of(LoanFixtures.adaUtxo("99".repeat(32), 0, ACCOUNT.baseAddress(), 2_000_000L),
                        LoanFixtures.adaUtxo("aa".repeat(32), 0, ACCOUNT.baseAddress(), 3_000_000L)),
                Optional.of(LoanFixtures.protocolParams().getProtocolParams()));

        assertTrue(gate.contains("TOTAL across"), gate);
        assertTrue(gate.contains("5000000"),
                "the available total must be the SUM of nominable utxos, not the largest: " + gate);
    }

    /**
     * ⛔ <b>T-060 part 2 — a bond whose loan is gone is not a fault, and must not be reported as one.</b>
     * <p>
     * Giovanni, from the logs: <i>"what's this 7 bonds and 5 not found... if it's related to a utxo
     * being spent, the loan does not exist anymore. so we should stop counting both as a bond in the
     * first place and as not found in the brackets."</i> He is right, and the reason is structural:
     * closing a loan <b>burns the loan NFT while the lender bond survives</b> as the lender's
     * separate claim ticket. One stale row was inflating the denominator <em>and</em> manufacturing
     * an alarming exclusion.
     * <p>
     * ⚠ <b>Reclassified, not deleted</b> — a deliberate departure from "stop counting". Delete the
     * number and the day a loan is absent for a reason that is <em>not</em> "settled", the line that
     * would have said so is the line we removed. <b>A metric that goes quiet because the population
     * left and one that goes quiet because the reader broke look identical.</b>
     */
    @Test
    void aBondWhoseLoanIsGoneIsSettledRatherThanAnExclusion() {
        Scenario scenario = scenario(FAT_FEE_PER_MILLE);
        LiquidationAssessment orphanA = LiquidationAssessment.excluded(scenario.bond().bond(), null,
                LiquidationExclusion.LOAN_NOT_FOUND, "no loan shares this bond's asset name");
        LiquidationAssessment orphanB = LiquidationAssessment.excluded(scenario.bond().bond(), null,
                LiquidationExclusion.LOAN_NOT_FOUND, "no loan shares this bond's asset name");

        Wiring wiring = wiring(shadow(SMALL_MARGIN),
                List.of(scenario.assessment(), orphanA, orphanB), List.of(scenario), false);
        wiring.executor().cycle(NOW);

        assertEquals(1, wiring.log().lastRun().bondsScanned(),
                "two of the three bonds have no loan, so only one is a live candidate");
        assertEquals(2, wiring.log().lastRun().settled());
        assertFalse(wiring.log().lastRun().exclusions()
                        .containsKey(LiquidationExclusion.LOAN_NOT_FOUND),
                "LOAN_NOT_FOUND must not appear in the exclusions — it is not a fault: "
                        + wiring.log().lastRun().exclusions());
        assertEquals(3, wiring.log().lastRun().bondsScanned() + wiring.log().lastRun().settled(),
                "and NOTHING IS LOST: the settled bonds are still counted, just not as failures");
    }

    /**
     * ⛔ <b>THE WIRING ASSERTION — and it exists because its absence was caught by a mutant that did
     * NOT fire.</b>
     * <p>
     * {@code ValidityWindowAnchorTest} proves the anchor arithmetic, and reverting the executor to
     * {@code anchor = now} — the exact behaviour that was rejected on chain — left it entirely GREEN,
     * because it calls the helper directly and nothing asserted the executor USES it. <b>A correct
     * function nobody calls is the shape this whole day has been about</b>, and it was in my own test.
     * <p>
     * So this drives a real {@code cycle()} with a lagging chain tip and reads
     * {@code validityStartInterval} off the <b>built transaction's CBOR</b> — the artefact, not the
     * arithmetic.
     */
    @Test
    void theBuiltTransactionsWindowFollowsTheChainTipNotTheWallClock() throws Exception {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false);

        // The chain sits 157 seconds behind wall clock — the measured gap that caused the first live
        // rejection (tx 8062f42d…, OutsideValidityIntervalUTxO, a 182-slot preview block gap).
        long laggingSlot = LoanFixtures.converters().time().toSlot(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(NOW - 157_000L), java.time.ZoneOffset.UTC));
        wiring.blockEventListener().getLastAppliedSlot().set(laggingSlot);

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertNotNull(decision.txCborHex(), "a transaction must have been built: " + decision.detail());
        var body = com.bloxbean.cardano.client.transaction.spec.Transaction
                .deserialize(com.bloxbean.cardano.client.util.HexUtil
                        .decodeHexString(decision.txCborHex())).getBody();

        assertNotNull(body.getValidityStartInterval());
        assertTrue(body.getValidityStartInterval() <= laggingSlot,
                "invalidBefore " + body.getValidityStartInterval() + " must not outrun the chain's "
                        + "last applied slot " + laggingSlot + " — that is the on-chain rejection, "
                        + "reproduced");

        // ⚠ And the fixture must reproduce the ORIGINAL failure, or this proves nothing: the OLD
        // wall-clock anchor would have put invalidBefore comfortably past the chain.
        long oldWallClockStart = LoanFixtures.converters().time().toSlot(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(NOW - 30_000L), java.time.ZoneOffset.UTC));
        assertTrue(oldWallClockStart > laggingSlot,
                "the fixture no longer reproduces the failure it was built from");
    }

    /**
     * ⛔ <b>A convert liquidation must not record itself as a plain one.</b>
     * <p>
     * The two paths have OPPOSITE SIGNS: on {@code Liquidate} the bot earns a fee slice and keeps
     * the collateral; on {@code LiquidateAndPayInAdvance} it <b>pays the lender in ada out of its
     * own wallet</b> and acquires the collateral token. That is a purchase, not a fee.
     * <p>
     * {@code LiquidationDecision.variant} was hardcoded to the plain value for the entire time the
     * convert path was live — <b>so every convert decision would have been stored as a plain
     * Liquidate.</b> The field's own javadoc said it existed <i>"so a second action can be added
     * later without every stored decision becoming ambiguous"</i>. The second action was added; the
     * field was not. <b>The ambiguity landed in the field that existed to prevent it.</b>
     * <p>
     * Found because a peer went looking for a convert log signature on the running pod and found
     * none — the path was armed, executable, and observable only as a FALLING wallet balance
     * inferred from chain.
     */
    @Test
    void aConvertCandidateIsRecordedAsConvertAndNotAsAPlainLiquidate() {
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(convert.assessment()), List.of(convert),
                allUnspent(List.of(convert)), List.of(WALLET_UTXO), noOracle(), false);

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.VARIANT_CONVERT, decision.variant(),
                "a convert liquidation stored as \"" + decision.variant() + "\" is indistinguishable "
                        + "from a plain one in the durable record, and the two have opposite signs");
    }

    /** And the plain path keeps its own label — the positive control for the test above. */
    @Test
    void aPlainCandidateIsStillRecordedAsAPlainLiquidate() {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring wiring = wiring(shadow(SMALL_MARGIN), List.of(honest.assessment()), List.of(honest),
                allUnspent(List.of(honest)), List.of(WALLET_UTXO), noOracle(), false);

        wiring.executor().cycle(NOW);

        assertEquals(LiquidationDecision.VARIANT, onlyDecision(wiring).variant());
    }

    /**
     * Slice 1, task 8 (ADDENDA). {@code variantOf} used to read ONLY the bond's
     * {@code shouldLiquidationConvertToPrincipal} flag, so every convert loan recorded itself as
     * {@code LiquidateAndPayInAdvance} — whose own javadoc says the bot PAID the lender out of its own
     * wallet — <b>even when the market routes it through Minswap, which fronts nothing.</b> That is the
     * opposite sign of what happened; the live record on 2026-09-08 proved it for real.
     * <p>
     * This is the {@code decision()} helper's construction site: an UNLISTED market is CONVERT by
     * default (no {@code shadow()} override here, unlike every other convert fixture in this class),
     * and {@code convertRouter} is absent (the 7-arg {@code wiring} overload never wires one), so the
     * candidate is refused as {@code CONVERT_UNAVAILABLE} — a REFUSED row built through
     * {@code decision()}, not {@code record()}. It must still say Minswap: the market and the bond flag
     * both say CONVERT; only the capability is missing.
     */
    @Test
    void aConvertUnavailableRefusalIsStillRecordedWithTheMinswapVariant() {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, SMALL_MARGIN, 200, 30);
        // markets left EMPTY: an unlisted market is action: CONVERT at the node's own mode.
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);

        Wiring wiring = wiring(configuration, List.of(convert.assessment()), List.of(convert),
                allUnspent(List.of(convert)), List.of(WALLET_UTXO), noOracle(), false);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("CONVERT_UNAVAILABLE", decision.reason());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, decision.variant(),
                "the decision() construction site must consult the SAME market the routing did, not "
                        + "just the bond flag: " + decision.variant());
    }

    /**
     * Slice 1, task 8 (ADDENDA), the OTHER construction site. The file's own comment on
     * {@code variantOf} records that fixing only one site previously left refusals and successes
     * labelled inconsistently, "which is worse than the original defect" — so this proves
     * {@code record()} too, with a candidate that is actually ROUTED and PRICED (not refused) under a
     * CONVERT market. {@link FakeConvertRouter} returns a real, already-built transaction so
     * {@code record()}'s pricing math has something real to run against; the routing decision under
     * test is {@code variantOf}, not the Minswap integration itself.
     */
    @Test
    void aRoutedConvertCandidateIsRecordedWithTheMinswapVariant() throws Exception {
        Scenario honest = scenario(FAT_FEE_PER_MILLE);
        Wiring plain = wiring(shadow(SMALL_MARGIN), honest, false);
        plain.executor().cycle(NOW);
        Transaction canned = Transaction.deserialize(
                HexUtil.decodeHexString(onlyDecision(plain).txCborHex()));

        Wiring wiring = convertViaMinswapWiring(canned);
        wiring.executor().cycle(NOW);

        LiquidationDecision decision = onlyDecision(wiring);
        assertNotNull(decision.txHash(), "record() must have been reached, not decision(): "
                + decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, decision.variant(),
                "a Minswap convert must record its OWN variant, not the pay-in-advance one, at the "
                        + "record() construction site too: " + decision.variant());
    }

    /**
     * A market that permits pay-in-advance up to {@code cap}. Since 2026-09-03 a test that exercises
     * that path must SAY which market it operates in and that the operator chose ANTICIPATE there —
     * exactly what an operator must now do, and no longer inferable from a cap alone.
     */
    private static AppConfig.LiquidationConfiguration.Market anticipateMarket(String unit, long cap) {
        var m = new AppConfig.LiquidationConfiguration.Market();
        m.setUnit(unit);
        m.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        m.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        m.setCap(java.math.BigInteger.valueOf(cap));
        return m;
    }

    // ===== the CONVERT route must be handed a NOMINABLE wallet utxo, never the first raw one =====

    /**
     * Captures the wallet utxo the executor's SELECTOR nominates, then stops the build.
     *
     * <p>⚠ It applies the selector itself, exactly as the real router does — the executor no longer
     * hands over a utxo, it hands over the means to choose one against a stated requirement. Asking
     * for {@code requiredLovelace} is what makes the SIZE observable as well as the nominability.
     */
    private static final class CapturingConvertRouter extends ConvertLiquidationRouter {
        Utxo captured;
        private final BigInteger requiredLovelace;
        CapturingConvertRouter(long requiredLovelace) {
            super(null, null, null, null, null, null, null, null);
            this.requiredLovelace = BigInteger.valueOf(requiredLovelace);
        }
        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo,
                                                    Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Map<String, OracleEntry> oracles,
                                                    String changeAddress, long validFromMillis, long validToMillis) {
            captured = walletSelector.apply(requiredLovelace).orElse(null);
            throw new RuntimeException("captured the wallet input; nothing past this point is under test");
        }
    }

    /**
     * ⛔ The regression the slice-3 round-2 audit caught, pinned. fbcf6b1 made the cycle's wallet list
     * the UNFILTERED listing (so the token path could see multi-asset utxos), and the convert hand-off
     * — {@code walletUtxos.get(0)}, untouched — silently became "the first utxo Blockfrost returns".
     * Blockfrost lists ascending, so that is the OLDEST utxo at the bot's address: the published
     * reference scripts. A convert would have spent {@code loan_claim_action}'s reference script.
     *
     * <p>The listing here puts a reference-script utxo FIRST on purpose; the captured input must be
     * the ada-only one. Mutant: hand the router {@code walletUtxos.get(0)} again — this test fails with
     * the reference-script hash in the message.
     */
    @Test
    void theConvertRouteIsHandedANominableWalletUtxoNeverTheFirstRawOne() {
        Utxo refScriptUtxo = Utxo.builder().txHash("f9".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(30_000_000L))))
                .referenceScriptHash("9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d").build();
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, SMALL_MARGIN, 200, 30);
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        List<Utxo> universe = List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO, refScriptUtxo,
                convert.loan().utxo(), convert.bond().utxo());
        LiquidateTransactionBuilder plainBuilder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        FakeScanner scanner = new FakeScanner(List.of(convert.assessment()));
        FakeResolver resolver = new FakeResolver(allUnspent(List.of(convert)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        CountingOracleProvider oracles = noOracle();
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));
        // The order overhead a token-collateral convert carries — what the real router asks for.
        CapturingConvertRouter capturing = new CapturingConvertRouter(4_000_000L);
        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(List.of(refScriptUtxo, WALLET_UTXO)), ACCOUNT, scanner, resolver, plainBuilder,
                payInAdvanceRouter, capturing, LoanFixtures.registry(), log, oracles,
                previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                EXPLODING_SUBMITTER);
        executor.cycle(NOW);
        assertNotNull(capturing.captured, "the convert route was never reached");
        assertNull(capturing.captured.getReferenceScriptHash(),
                "the CONVERT route was handed the published REFERENCE-SCRIPT utxo as its wallet input: "
                        + capturing.captured.getTxHash() + "#" + capturing.captured.getOutputIndex());
        assertEquals(WALLET_UTXO.getTxHash(), capturing.captured.getTxHash(),
                "the convert route must receive a NOMINABLE utxo");
    }

    /**
     * ⛔ <b>NOMINABLE IS NOT ENOUGH — the input must also be big enough for the order.</b>
     *
     * <p>The previous test would pass against "the first nominable utxo", which is what the hand-off
     * was before this slice. This one cannot: the first nominable utxo in the listing holds 5 ADA and
     * the convert order needs 60, so a first-match selection returns the small one and a SIZED
     * selection returns the large one. Together the two tests pin both halves — nominability and size —
     * and the mutant that reverts either is failed by one of them.
     *
     * <p>⚠ An ADA-collateral convert is what makes this matter: its {@code orderLovelace} is the whole
     * swappable amount PLUS the Minswap overhead, not the overhead alone, so it can be arbitrarily
     * larger than any fixed headroom the old hand-off happened to have.
     */
    @Test
    void theConvertRouteNominatesAWalletUtxoLargeEnoughForTheOrderNotMerelyTheFirstNominableOne() {
        Utxo tooSmall = LoanFixtures.adaUtxo("ab".repeat(32), 0, ACCOUNT.baseAddress(), 5_000_000L);
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, SMALL_MARGIN, 200, 30);
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        List<Utxo> universe = List.of(CONFIG_UTXO, LM_CONFIG_UTXO, tooSmall, WALLET_UTXO,
                convert.loan().utxo(), convert.bond().utxo());
        LiquidateTransactionBuilder plainBuilder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        FakeScanner scanner = new FakeScanner(List.of(convert.assessment()));
        FakeResolver resolver = new FakeResolver(allUnspent(List.of(convert)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));
        // 60 ADA: more than the 5 ADA utxo listed FIRST, less than the 200 ADA one listed second.
        CapturingConvertRouter capturing = new CapturingConvertRouter(60_000_000L);
        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(List.of(tooSmall, WALLET_UTXO)), ACCOUNT, scanner, resolver,
                plainBuilder, payInAdvanceRouter, capturing, LoanFixtures.registry(), log, noOracle(),
                previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                EXPLODING_SUBMITTER);
        executor.cycle(NOW);

        assertNotNull(capturing.captured, "the convert route was never reached");
        assertEquals(WALLET_UTXO.getTxHash(), capturing.captured.getTxHash(),
                "the convert route nominated the FIRST nominable utxo (5 ADA) for an order needing "
                        + "60 ADA; the nomination must be sized to the order, not to list position");
    }

    // ===== a VERDICT on the convert route is recorded, never quarantined as machinery =====

    /** Throws whatever it is given, and counts how often the executor actually reached it. */
    private static final class ThrowingConvertRouter extends ConvertLiquidationRouter {
        private final RuntimeException toThrow;
        int calls;
        ThrowingConvertRouter(RuntimeException toThrow) {
            super(null, null, null, null, null, null, null, null);
            this.toThrow = toThrow;
        }
        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo,
                                                    Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Map<String, OracleEntry> oracles,
                                                    String changeAddress, long validFromMillis, long validToMillis) {
            calls++;
            throw toThrow;
        }
    }

    /** Wires a shadow executor whose convert router throws, sharing the fixtures of the tests above. */
    private static ConvertWiring convertWiringThrowing(RuntimeException toThrow) {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, SMALL_MARGIN, 200, 30);
        Scenario convert = convertScenario(FAT_FEE_PER_MILLE);
        List<Utxo> universe = List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                convert.loan().utxo(), convert.bond().utxo());
        LiquidateTransactionBuilder plainBuilder = new LiquidateTransactionBuilder(LoanFixtures.registry(),
                LoanFixtures.NETWORK, LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), null);
        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));
        ThrowingConvertRouter router = new ThrowingConvertRouter(toThrow);
        LiquidationExecutor executor = new LiquidationExecutor(configuration, blockEventListener,
                new FakeAppUtxoService(List.of(WALLET_UTXO)), ACCOUNT,
                new FakeScanner(List.of(convert.assessment())),
                new FakeResolver(allUnspent(List.of(convert))), plainBuilder, payInAdvanceRouter,
                router, LoanFixtures.registry(), log, noOracle(), previewNetwork(),
                LoanFixtures.protocolParams(), LoanFixtures.converters(), EXPLODING_SUBMITTER);
        return new ConvertWiring(executor, log, router);
    }

    private record ConvertWiring(LiquidationExecutor executor, LiquidationDecisionLog log,
                                 ThrowingConvertRouter router) {
    }

    /**
     * ⛔ <b>A CLEAN VERDICT IS NOT A MACHINERY FAULT.</b> {@code POOL_TOO_THIN} — like the other four
     * {@code ConvertOrderPlan} refusals — is a statement about this loan against this pool: no
     * transaction was built, nothing broke, and one Minswap swap can make it viable again. It must be
     * recorded under its own name and NOT held.
     *
     * <p>Before this slice every one of these fell to the executor's generic {@code catch (Exception)}
     * and was quarantined for thirty minutes at ERROR, indistinguishable from a broken bot.
     *
     * <p>Mutant: remove the {@code ConvertOrderPlan.RefusedException} catch so it falls through again —
     * the reason becomes the exception class and the quarantine count becomes 1.
     */
    @Test
    void aConvertPlanRefusalIsRecordedUnderItsOwnNameAndNeverQuarantined() {
        ConvertWiring wiring = convertWiringThrowing(new ConvertOrderPlan.RefusedException(
                ConvertOrderPlan.Refusal.POOL_TOO_THIN,
                "swapping 22003200000 would return about 824348402, but minimum_receive is 980001429"));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = wiring.log().newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome());
        assertEquals("POOL_TOO_THIN", decision.reason(),
                "the refusal's own name, not the exception class the generic catch would have used");
        assertTrue(decision.detail().contains("824348402"),
                "the operator needs the numbers, not just the verdict: " + decision.detail());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "a verdict about the candidate must not be held as though the bot were broken");

        // …and it is genuinely reconsidered on the very next cycle, which is what "not held" means.
        wiring.executor().cycle(NOW + 1_000L);
        assertEquals(2, wiring.router().calls,
                "the candidate must be reconsidered immediately, not suppressed");
    }

    /**
     * ⛔ <b>A TRANSPORT FAILURE GETS A SHORT HOLD — NOT NOTHING, AND NOT THIRTY MINUTES.</b>
     *
     * <p>Measured on mainnet 2026-09-09: one Blockfrost hiccup on the pool lookup produced <b>39
     * {@code QUARANTINED} records</b> on a single live loan, because {@code LOOKUP_FAILED} was reaching
     * the machinery quarantine. A provider that cannot be reached says nothing about the candidate —
     * but retrying every cycle against a provider that is down is what the quarantine exists for, so
     * the answer is a SHORT hold: two cycles.
     *
     * <p>Both bounds are asserted, because each is a different mutant. Inside one cycle it must still
     * be held (removing the hold entirely fails here); past two cycles it must be reconsidered (setting
     * the hold to {@code quarantine-minutes} fails there).
     */
    @Test
    void aTransportFailureOnThePoolLookupIsHeldForTwoCyclesNotTheThirtyMinuteQuarantine() {
        ConvertWiring wiring = convertWiringThrowing(new MinswapPoolResolver.RefusedException(
                MinswapPoolResolver.Refusal.LOOKUP_FAILED, "the pool lookup failed: java.net.SocketTimeoutException"));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = wiring.log().newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome());
        assertEquals("LOOKUP_FAILED", decision.reason(),
                "a transport failure must be distinguishable from NO_MINSWAP_POOL, which is a verdict");
        assertEquals(1, wiring.executor().quarantinedCount(),
                "SOME hold is required: a candidate retried every cycle against a provider that is "
                        + "down is exactly what the quarantine was built for");
        assertEquals(1, wiring.router().calls);

        // One cycle later (60s), still inside the two-cycle hold.
        wiring.executor().cycle(NOW + 60_000L);
        assertEquals(1, wiring.router().calls, "still held after one cycle");
        assertEquals(LiquidationDecision.Outcome.QUARANTINED,
                wiring.log().newestFirst(10).getFirst().outcome());

        // Past two cycles (121s) it must be reconsidered — the whole point of not using 30 minutes.
        wiring.executor().cycle(NOW + 121_000L);
        assertEquals(2, wiring.router().calls,
                "a transport blip must clear in two cycles, not in the 30-minute machinery quarantine");
    }

    /**
     * ⛔ <b>F1 — A WALLET TOO SMALL IS A VERDICT ABOUT THE WALLET, NOT A BROKEN BOT.</b>
     *
     * <p>Round-1 audit: mutant M12 — neuter this catch so the exception falls to the generic
     * {@code catch (Exception)} and its 30-minute machinery quarantine — killed <b>0 of 1062</b>
     * tests. The catch existed, behaved correctly, and nothing held it there; reordering the ladder
     * (the refactor r1 flags as "tempting, separate slice") would have silently restored pre-slice
     * behaviour.
     *
     * <p>⚠ The operator can top the wallet up between cycles, so holding this for thirty minutes
     * keeps refusing a convert that has already become fundable. Same treatment the pay-in-advance
     * path gives its own {@code WalletInputTooSmallException}.
     *
     * <p>The THROW side is pinned separately, in {@code ConvertLiquidationRouterTest} — a fake router
     * can prove what the executor does with the exception and nothing about whether it is ever raised.
     */
    @Test
    void aWalletTooSmallForTheConvertOrderIsRecordedAndNeverQuarantined() {
        ConvertWiring wiring = convertWiringThrowing(
                new ConvertLiquidationRouter.WalletInputTooSmallException(
                        "no nominable wallet utxo can fund this convert: the Minswap order carries "
                                + "4000000 lovelace (the order overhead; the collateral is a token)"));

        wiring.executor().cycle(NOW);

        LiquidationDecision decision = wiring.log().newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome());
        assertEquals("WALLET_INPUT_TOO_SMALL", decision.reason(),
                "recorded under its own name, not under the exception class the generic catch uses");
        assertTrue(decision.detail().contains("4000000"),
                "the operator needs the figure to fund against: " + decision.detail());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "a wallet the operator can top up between cycles must not be held for 30 minutes");
        assertTrue(wiring.executor().quarantinedRefs().isEmpty(),
                "and the quarantine map must be untouched, not merely small");

        // Reconsidered on the very next cycle — which is what "not held" has to mean to be worth it.
        wiring.executor().cycle(NOW + 1_000L);
        assertEquals(2, wiring.router().calls,
                "a topped-up wallet must be retried immediately, not after a machinery quarantine");
    }

    /**
     * The resolver's OTHER refusals are facts about the chain — reproducible next cycle and unaffected
     * by waiting — so they are verdicts, held no longer than any other verdict. Only
     * {@code LOOKUP_FAILED} is transport.
     */
    @Test
    void anAmbiguousPoolIsAVerdictAboutTheChainAndIsNotHeld() {
        ConvertWiring wiring = convertWiringThrowing(new MinswapPoolResolver.RefusedException(
                MinswapPoolResolver.Refusal.AMBIGUOUS_POOL, "2 UTxOs hold the LP asset"));

        wiring.executor().cycle(NOW);

        assertEquals("AMBIGUOUS_POOL", wiring.log().newestFirst(10).getFirst().reason());
        assertEquals(0, wiring.executor().quarantinedCount(),
                "waiting cannot change how many pool UTxOs hold the LP asset");
    }
}
