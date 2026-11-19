package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONVERTERS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_ORDER_SPEND;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_POOL_POLICY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_POOL_SPEND;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.NETWORK;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.POOL_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * FAB-224-3 — ONE {@code cycle(now)} of a REAL {@link LiquidationExecutor} on the convert route, over production's
 * convert wiring, and everything it left behind: the decision log, the transaction the real builder returned, what
 * reached the submitter, and the executor's INFO lines.
 * <p>
 * The wiring is {@code OracleProbeRoutesProductionTest}'s convert rig with only the leaves supplied: the executor's
 * 17-argument constructor with the convert seam; {@code YaciConfig}'s router bean over a {@link MinswapPoolResolver}
 * reading the given pool rows from a mocked index, a real {@link ConvertEconomics} and the production 6-argument
 * {@link ConvertTransactionBuilder} — a Mockito spy that calls the real {@code build} and keeps what it returns, its
 * evaluator the real Aiken machine over the given universe and scripts, its script supplier production's
 * {@link HashCheckedScriptSupplier}. The plain builder and the pay-in-advance router are real and unused. The
 * protocol parameters are {@link EvalFixtures#protocolParams()} throughout, so role C is
 * {@code WalletShape.collateralLovelace} of those parameters. No market is listed, so the candidate's unlisted
 * market converts.
 * <p>
 * SHADOW: the submitter fails the test if it is called. LIVE: {@link RouteStageHarness#accepting(String)}, recording
 * every byte string it is handed. Test-only; it carries no {@code @Test}, and its name does not end in {@code Test}.
 */
final class ConvertExecutorRig {

    /** The bot's account. Random per JVM and never stored: anything that depends on its address is derived. */
    static final Account ACCOUNT = new Account(Networks.mainnet());

    /**
     * What one candidate brings: the UTxO universe the builder and evaluator see, the scripts they are served, the
     * wallet the executor lists, the scan's assessment, the four UTxOs the resolver answers, the six reference-script
     * coordinates by hash, the pool index rows and the oracle registry's entries.
     */
    record Candidate(List<Utxo> universe, ScriptSupplier scripts, List<Utxo> wallet, LiquidationAssessment assessment,
                     Utxo loanUtxo, Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                     Map<String, TransactionInput> referenceScripts, List<AddressUtxoEntity> poolRows,
                     List<OracleEntry> oracles) {
    }

    final ConvertTransactionBuilder builder;
    final ConvertTokenToTokenFixture.Recording evaluator;
    final LiquidationDecisionLog log;
    final LiquidationExecutor executor;
    /** Every transaction the real {@code build} returned. */
    final List<Transaction> built = new CopyOnWriteArrayList<>();
    /** Every byte string the submitter was handed, in order. */
    final List<byte[]> submitted = new CopyOnWriteArrayList<>();
    /** The executor's INFO lines of the last {@link #cycle}, formatted, in order. */
    final List<String> info = new ArrayList<>();
    private final List<Utxo> wallet;

    private ConvertExecutorRig(AppConfig.LiquidationConfiguration.Mode mode, BigInteger margin,
                               BigInteger minProfitAbsolute, String acceptedHash, Candidate c) throws Exception {
        this.wallet = c.wallet();
        var params = EvalFixtures.protocolParams();
        UtxoSupplier utxos = LoanFixtures.utxoSupplier(c.universe());

        this.evaluator = new ConvertTokenToTokenFixture.Recording(
                new AikenTransactionEvaluator(utxos, params, c.scripts(), SlotConfigs.mainnet()));
        this.builder = spy(new ConvertTransactionBuilder(REGISTRY, NETWORK, utxos, params,
                new HashCheckedScriptSupplier(c.scripts()), evaluator));
        doAnswer(invocation -> {
            Transaction transaction = (Transaction) invocation.callRealMethod();
            built.add(transaction);
            return transaction;
        }).when(builder).build(any());

        Map<String, TransactionInput> refs = c.referenceScripts();
        var references = new LiquidateTransactionBuilder.ReferenceScripts(refs.get(REGISTRY.getLoanPolicyId()),
                refs.get(REGISTRY.getLoanSpendScriptHash()), refs.get(REGISTRY.getLenderManagerWithdrawScriptHash()),
                refs.get(REGISTRY.getLenderManagerSpendScriptHash()), refs.get(REGISTRY.getLoanClaimActionScriptHash()),
                null, null, null, refs.get(REGISTRY.getLmLiquidateAndConvertActionScriptHash()));
        var configuration = new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, margin, 200, true,
                minProfitAbsolute, references);
        configuration.setMarkets(List.of());
        this.log = new LiquidationDecisionLog(configuration);

        AppConfig.Network mainnet = network();
        UtxoRepository poolIndex = mock(UtxoRepository.class);
        when(poolIndex.findUnspentByOwnerPaymentCredential(eq(MS_POOL_SPEND), any()))
                .thenReturn(Optional.of(c.poolRows()));
        ConvertLiquidationRouter router = new YaciConfig().convertLiquidationRouter(REGISTRY, minswapConfigured(),
                new MinswapPoolResolver(poolIndex, MS_POOL_SPEND, MS_POOL_POLICY),
                new ConvertEconomics(new AppConfig.ConvertConfiguration(true), configuration, mainnet), builder,
                configuration, CONVERTERS, mainnet);
        PayInAdvanceLiquidationRouter unusedPia = new PayInAdvanceLiquidationRouter(REGISTRY, CONVERTERS,
                configuration, new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, NETWORK, utxos, params));

        BlockEventListener notSyncing = new BlockEventListener(null);
        notSyncing.getIsSyncing().set(false);

        LiquidationExecutor.TransactionSubmitter submitter;
        if (mode == AppConfig.LiquidationConfiguration.Mode.LIVE) {
            LiquidationExecutor.TransactionSubmitter accepting = RouteStageHarness.accepting(acceptedHash);
            submitter = bytes -> {
                submitted.add(bytes);
                return accepting.submit(bytes);
            };
        } else {
            submitter = bytes -> {
                submitted.add(bytes);
                throw new AssertionError("a SHADOW cycle reached the submitter");
            };
        }
        this.executor = new LiquidationExecutor(configuration, notSyncing, new FakeAppUtxoService(c.wallet()),
                ACCOUNT, new FakeScanner(c.assessment()),
                new FakeResolver(c.loanUtxo(), c.bondUtxo(), c.configUtxo(), c.lmConfigUtxo()),
                new LiquidateTransactionBuilder(REGISTRY, NETWORK, CONVERTERS, utxos, params),
                unusedPia, router, REGISTRY, log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                new FixedOracles(c.oracles().toArray(new OracleEntry[0])), mainnet, params, CONVERTERS, submitter);
    }

    /** A SHADOW node: nothing may reach the submitter. */
    static ConvertExecutorRig shadow(BigInteger margin, BigInteger minProfitAbsolute, Candidate candidate)
            throws Exception {
        return new ConvertExecutorRig(AppConfig.LiquidationConfiguration.Mode.SHADOW, margin, minProfitAbsolute, null,
                candidate);
    }

    /** A LIVE node whose submitter accepts every transaction, answering {@code acceptedHash}. */
    static ConvertExecutorRig live(BigInteger margin, BigInteger minProfitAbsolute, String acceptedHash,
                                   Candidate candidate) throws Exception {
        return new ConvertExecutorRig(AppConfig.LiquidationConfiguration.Mode.LIVE, margin, minProfitAbsolute,
                acceptedHash, candidate);
    }

    /** {@link LoanFixtures#shapedWallet}: an ada-only U of {@code uLovelace} and role C, at {@link #ACCOUNT}'s address. */
    static List<Utxo> wallet(long uLovelace) {
        return LoanFixtures.shapedWallet(EvalFixtures.protocolParams(), ACCOUNT.baseAddress(),
                LoanFixtures.adaUtxo("e0".repeat(32), 0, ACCOUNT.baseAddress(), uLovelace));
    }

    /** ONE {@code cycle(now)}, its submit clock at {@code now}, with the executor's INFO lines captured. */
    ConvertExecutorRig cycle(long now) {
        executor.setSubmitClock(() -> now);
        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            executor.cycle(now);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        info.clear();
        appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .forEach(e -> info.add(e.getFormattedMessage()));
        return this;
    }

    /** The one decision the cycle recorded. */
    LiquidationDecision onlyDecision() {
        List<LiquidationDecision> decisions = log.newestFirst(10);
        assertEquals(1, decisions.size(), decisions.toString());
        return decisions.getFirst();
    }

    /** The one transaction the real builder returned, deserialised: the body the executor priced. */
    Transaction builtOnce() throws Exception {
        assertEquals(1, built.size(), "exactly one build");
        Transaction reread = Transaction.deserialize(built.getFirst().serialize());
        assertEquals(built.getFirst().getBody().getFee(), reread.getBody().getFee());
        return reread;
    }

    /** The refs of {@code body}'s inputs that are outputs of the wallet the executor listed. */
    Set<String> walletInputs(Transaction body) {
        Set<String> walletRefs = new HashSet<>();
        wallet.forEach(utxo -> walletRefs.add(utxo.getTxHash() + "#" + utxo.getOutputIndex()));
        Set<String> spent = new HashSet<>();
        for (TransactionInput input : body.getBody().getInputs()) {
            String ref = input.getTransactionId() + "#" + input.getIndex();
            if (walletRefs.contains(ref)) {
                spent.add(ref);
            }
        }
        return spent;
    }

    /** Σ coin of the wallet outputs {@code body} spends. */
    BigInteger walletInputCoin(Transaction body) {
        Set<String> spent = walletInputs(body);
        return wallet.stream().filter(u -> spent.contains(u.getTxHash() + "#" + u.getOutputIndex()))
                .map(LedgerCeilings::lovelaceOf).reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** Σ coin of {@code body}'s outputs at the bot's own address: the change, the carriers, the withdrawal dummy. */
    static BigInteger coinAtBot(Transaction body) {
        return body.getBody().getOutputs().stream().filter(o -> ACCOUNT.baseAddress().equals(o.getAddress()))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** The coin of {@code body}'s single output at {@code credential} (a script payment credential). */
    static BigInteger coinAtCredential(Transaction body, String credential) {
        List<TransactionOutput> at = body.getBody().getOutputs().stream()
                .filter(o -> new Address(o.getAddress()).getPaymentCredentialHash().map(HexUtil::encodeHexString)
                        .filter(credential::equalsIgnoreCase).isPresent())
                .toList();
        assertEquals(1, at.size(), "exactly one output at " + credential);
        return at.getFirst().getValue().getCoin();
    }

    private static final Pattern MIN_ADA = Pattern.compile(" - min-ada (-?\\d+)");
    private static final Pattern FLOOR = Pattern.compile(" = floor (-?\\d+);");

    /** The min-ada term of a convert row's detail. */
    static BigInteger minAda(String detail) {
        return term(MIN_ADA, detail);
    }

    /** The floor of a convert row's detail: fee slice − tx fee − min-ada. */
    static BigInteger floor(String detail) {
        return term(FLOOR, detail);
    }

    private static BigInteger term(Pattern pattern, String detail) {
        Matcher matcher = pattern.matcher(detail);
        assertTrue(matcher.find(), "no " + pattern + " in: " + detail);
        return new BigInteger(matcher.group(1));
    }

    private static AppConfig.Network network() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return network;
    }

    private static AppConfig.LoansConfiguration minswapConfigured() {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return POOL_ADDRESS;
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return MS_POOL_POLICY;
            }

            @Override
            public String getMinswapPoolSpendScriptHash() {
                return MS_POOL_SPEND;
            }

            @Override
            public String getMinswapOrderSpendScriptHash() {
                return MS_ORDER_SPEND;
            }
        };
    }

    // ---- private copies of OracleProbeRoutesProductionTest's leaves --------------------------------

    private static final class FakeScanner extends LiquidationCandidateScanner {
        private final List<LiquidationAssessment> assessments;

        FakeScanner(LiquidationAssessment... assessments) {
            super(null, null, null);
            this.assessments = List.of(assessments);
        }

        @Override
        public Scan scan(long atTimeMillis) {
            return new Scan(assessments, new LoanService.Census(List.of(), assessments.size(), 0, 0));
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        private final Utxo loanUtxo;
        private final Utxo bondUtxo;
        private final Utxo configUtxo;
        private final Utxo lmConfigUtxo;

        FakeResolver(Utxo loanUtxo, Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo) {
            super(null, null, null);
            this.loanUtxo = loanUtxo;
            this.bondUtxo = bondUtxo;
            this.configUtxo = configUtxo;
            this.lmConfigUtxo = lmConfigUtxo;
        }

        @Override
        public Optional<Utxo> resolveLoanUtxo(Loan loan) {
            return Optional.of(loanUtxo);
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.of(bondUtxo);
        }

        @Override
        public Optional<Utxo> resolveConfigUtxo() {
            return Optional.of(configUtxo);
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(lmConfigUtxo);
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

        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return entries.stream().filter(e -> e.token().equals(token)).findFirst();
        }

        @Override
        public Optional<OracleEntry> findEntryByOracleToken(AssetType oracleToken) {
            return entries.stream().filter(e -> e.oracleToken().equals(oracleToken)).findFirst();
        }
    }

    private static final class FixedOracles implements ObjectProvider<FluidOracleClient> {
        private final FluidOracleClient client;

        FixedOracles(OracleEntry... entries) {
            this.client = new FakeOracleClient(List.of(entries));
        }

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
    }
}
