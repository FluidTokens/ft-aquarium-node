package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.Utxo;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.plugin.aspect.PluginAspect;
import com.bloxbean.cardano.yaci.store.utxo.processor.UtxoProcessor;
import com.bloxbean.cardano.yaci.store.utxo.storage.UtxoStorage;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.TxInputEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-203-3: {@link TankUtxoStorage} in the <b>production shape</b> — Yaci Store 2.0.7's full auto-configuration
 * over the shipped {@code application.yaml} ({@code YaciStoreContextHarness}), the storage constructed by the
 * container from its own constructor annotations, its {@code @Value}s resolved from the shipped document.
 *
 * <p>{@link UtxoProcessorPinTest} pins the write path through Yaci's {@link UtxoProcessor} in a minimal context
 * whose only {@code UtxoStorage} the test builds by hand. That proves the storage, not that Yaci uses it: in the node,
 * {@code UtxoStoreConfiguration}'s {@code @ConditionalOnMissingBean UtxoStorage} must back off, the processor Yaci
 * builds behind its {@code @EnableIf} aspect must hold this bean, no plugin advice may sit on it, and the
 * {@link UtxoCache} must be cleared on {@link CommitEvent} through 2.0.7's call path ({@code UtxoProcessor.handleCommit}
 * → {@code UtxoStorage.handleCommit}). This class proves each of those, and never invokes that path itself: the
 * storage is never constructed here, and the commit is only ever published.
 *
 * <h2>The fixture</h2>
 * {@code W} the wallet's base address; {@code F} an enterprise key address no credential watches; {@code O} an
 * enterprise script address no credential watches; {@code P} an address under the shipped Minswap V2 pool
 * credential. One Conway block: {@code A} creates {@code A#0→W}, {@code A#1→F}, {@code A#2→O} holding one feed NFT
 * of the shipped mainnet policy, {@code A#3→P} holding a token; {@code B} spends {@code A#0} and creates
 * {@code B#0→W}. Spent rows stay in {@code address_utxo}; spentness lives in {@code tx_input}
 * ({@code officina:yaci-store-index-scoping} §4b), and an output the filter discards leaves no row at all (§2).
 */
class TankUtxoStorageInYaciContextTest {

    /** The shipped {@code loans.minswap.pool-spend-script-hash}: the Minswap V2 pool payment credential. */
    private static final String POOL_PKH = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";

    /** The shipped mainnet {@code loans.oracle.indexed-nft-policies}: the Lending v4 feed policy. */
    private static final String FEED_POLICY = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";

    private static final AssetType FEED_NFT = new AssetType(FEED_POLICY, "4f52434c");

    /** A token held by the pool output; its policy is not an oracle policy. */
    private static final AssetType POOL_TOKEN = new AssetType("c0".repeat(28), "504f4f4c");

    private static final String HARNESS = "com.fluidtokens.aquarium.offchain.config.YaciStoreContextHarness";

    private static final long SLOT = 140_000_000L;
    private static final String BLOCK_HASH = "b1".repeat(32);

    private static final String TX_X = "09".repeat(32);
    private static final String TX_A = "0a".repeat(32);
    private static final String TX_B = "0b".repeat(32);

    private static final Map<String, String> NAMES = Map.of(TX_X, "X", TX_A, "A", TX_B, "B");

    private static final String F = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("ff".repeat(28))), Networks.testnet()).toBech32();
    private static final String O_SCRIPT = "f0".repeat(28);
    private static final String O = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(O_SCRIPT)), Networks.testnet()).toBech32();
    private static final String P = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(POOL_PKH)), Networks.testnet()).toBech32();

    // ---- the context -------------------------------------------------------------------------------------------

    /**
     * The harness's runner, unchanged, plus {@link TankUtxoStorage} registered BY CLASS — the container chooses and
     * fills its constructor — and the four collaborators the harness lacks. No {@code LoansContractRegistry}: the
     * storage's {@code ObjectProvider} provides nothing.
     */
    private static WebApplicationContextRunner runner() {
        return harness()
                .withBean(Account.class, () -> new Account(Networks.testnet()))
                .withBean(ParametersContractService.class, () -> scriptHash(ParametersContractService.class, "a1"))
                .withBean(StakerContractService.class, () -> scriptHash(StakerContractService.class, "a2"))
                .withBean(TankContractService.class, () -> scriptHash(TankContractService.class, "a3"))
                .withBean(TankUtxoStorage.class);
    }

    /**
     * {@code YaciStoreContextHarness.runner()}. The harness is package-private to {@code config} and is not this
     * slice's to change, so it is reached reflectively; the runner it returns is used as it comes.
     */
    private static WebApplicationContextRunner harness() {
        try {
            Method runner = Class.forName(HARNESS).getDeclaredMethod("runner");
            runner.setAccessible(true);
            return (WebApplicationContextRunner) runner.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach " + HARNESS + ".runner()", e);
        }
    }

    private static <T> T scriptHash(Class<T> service, String hexByte) {
        T mock = mock(service);
        String hash = hexByte.repeat(28);
        if (mock instanceof ParametersContractService parameters) {
            when(parameters.getScriptHashHex()).thenReturn(hash);
        } else if (mock instanceof StakerContractService staker) {
            when(staker.getScriptHashHex()).thenReturn(hash);
        } else if (mock instanceof TankContractService tank) {
            when(tank.getScriptHashHex()).thenReturn(hash);
        }
        return mock;
    }

    private static String walletPkh(AssertableWebApplicationContext context) {
        return context.getBean(Account.class).getBaseAddress().getPaymentCredentialHash()
                .map(HexUtil::encodeHexString).get();
    }

    // ---- (a) -----------------------------------------------------------------------------------------------------

    @Test
    void aTheContainerBuildsTheStorageFromTheShippedLoansValues() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            TankUtxoStorage storage = context.getBean(TankUtxoStorage.class);
            assertEquals(Set.of(FEED_POLICY), storage.indexedOracleNftPolicies(),
                    "(a) loans.oracle.indexed-nft-policies is read from the shipped document");
            assertTrue(storage.indexedPaymentCredentials().contains(POOL_PKH),
                    "(a) loans.minswap.pool-spend-script-hash is read from the shipped document: "
                            + storage.indexedPaymentCredentials());
            assertTrue(storage.indexedPaymentCredentials().contains(walletPkh(context)),
                    "(a) the wallet's payment credential is watched");
        });
    }

    // ---- (b) -----------------------------------------------------------------------------------------------------

    @Test
    void bYacisUtxoStorageBacksOffAndItsProcessorWritesThroughTheNodesStorage() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, UtxoStorage> storages = context.getBeansOfType(UtxoStorage.class);
            assertEquals(1, storages.size(), "(b) exactly one UtxoStorage bean: " + storages.keySet());
            UtxoStorage storage = storages.values().iterator().next();
            assertEquals(TankUtxoStorage.class, AopUtils.getTargetClass(storage),
                    "(b) the only UtxoStorage is the node's");

            UtxoProcessor processor = AopTestUtils.getUltimateTargetObject(context.getBean(UtxoProcessor.class));
            assertSame(storage, ReflectionTestUtils.getField(processor, "utxoStorage"),
                    "(b) the UtxoProcessor Yaci builds holds the node's storage bean");
        });
    }

    // ---- (c) -----------------------------------------------------------------------------------------------------

    @Test
    void cNoPluginAdviceSitsOnTheStorage() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            UtxoStorage storage = context.getBean(UtxoStorage.class);
            if (storage instanceof Advised advised) {
                for (Advisor advisor : advised.getAdvisors()) {
                    Advice advice = advisor.getAdvice();
                    assertNotEquals(PluginAspect.class, advice.getClass(), "(c) PluginAspect advice: " + advisor);
                    if (advice instanceof AbstractAspectJAdvice aspectJ) {
                        assertNotEquals(PluginAspect.class, aspectJ.getAspectJAdviceMethod().getDeclaringClass(),
                                "(c) PluginAspect advice on the storage: " + advisor);
                    }
                }
            }
            assertEquals(List.of(), List.of(context.getBeanNamesForType(PluginAspect.class)),
                    "(c) PluginAspect must not be a bean");
        });
    }

    // ---- (d) -----------------------------------------------------------------------------------------------------

    @Test
    void dABlockAsYaciPublishesItIsFilteredAndTheCacheIsClearedOnCommit() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Account account = context.getBean(Account.class);
            String w = account.baseAddress();
            String walletPkh = walletPkh(context);
            UtxoCache cache = context.getBean(UtxoCache.class);
            var cacheBeforeCommitEvent = new ArrayList<Set<String>>();
            var cacheSizeAfterCommitEvent = new ArrayList<Integer>();

            // As ShelleyBlockEventPublisher.publishBlockEvents does it: one transaction around both events.
            EventMetadata metadata = EventMetadata.builder()
                    .era(Era.Conway)
                    .slot(SLOT)
                    .block(1)
                    .blockHash(BLOCK_HASH)
                    .blockTime(1_700_000_000L + SLOT)
                    .epochNumber(560)
                    .build();
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                context.publishEvent(new TransactionEvent(metadata, block(w)));
                cacheBeforeCommitEvent.add(cacheContents(cache));
                context.publishEvent(new CommitEvent<>(metadata, List.of()));
                cacheSizeAfterCommitEvent.add(cache.size());
            });

            assertEquals(List.of(Set.of("A#0", "A#2", "A#3", "B#0")), cacheBeforeCommitEvent,
                    "(d) inside the block's transaction, before CommitEvent, the UtxoCache holds the kept outputs");
            assertEquals(List.of(0), cacheSizeAfterCommitEvent,
                    "(d) CommitEvent, through UtxoProcessor, clears the UtxoCache");

            assertEquals(new TreeSet<>(List.of(
                            "A#0 credential=" + walletPkh,
                            "A#2 credential=" + O_SCRIPT,
                            "A#3 credential=" + POOL_PKH,
                            "B#0 credential=" + walletPkh)),
                    addressUtxoRows(context.getBean(UtxoRepository.class)),
                    "(d) address_utxo holds exactly the kept outputs; A#1 (unwatched F) left no row");
            assertEquals(Set.of("A#0 spentBy=B slot=" + SLOT), txInputRows(context.getBean(TxInputRepository.class)),
                    "(d) tx_input holds exactly the same-block spend of A#0; X#0 was never indexed");
        });
    }

    // ---- the fixture -------------------------------------------------------------------------------------------

    private static List<Transaction> block(String w) {
        Transaction a = valid(TX_A, inputs(in(TX_X, 0)),
                utxo(TX_A, 0, w, lovelace(2_000_000)),
                utxo(TX_A, 1, F, lovelace(3_000_000)),
                utxo(TX_A, 2, O, lovelace(4_000_000), token(FEED_NFT, "ORCL")),
                utxo(TX_A, 3, P, lovelace(5_000_000), token(POOL_TOKEN, "POOL")));
        Transaction b = valid(TX_B, inputs(in(TX_A, 0)),
                utxo(TX_B, 0, w, lovelace(1_800_000)));
        return List.of(a, b);
    }

    private static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").policyId(null).assetName("lovelace")
                .quantity(BigInteger.valueOf(quantity)).build();
    }

    /** Yaci's shape for a native asset: the unit is {@code policy.hexName}, the asset name its UTF-8 text. */
    private static Amount token(AssetType asset, String utf8Name) {
        return Amount.builder().unit(asset.policyId() + "." + asset.assetName()).policyId(asset.policyId())
                .assetName(utf8Name).assetNameBytes(HexUtil.decodeHexString(asset.assetName()))
                .quantity(BigInteger.ONE).build();
    }

    private static Utxo utxo(String txHash, int index, String address, Amount... amounts) {
        return Utxo.builder().txHash(txHash).index(index).address(address).amounts(List.of(amounts)).build();
    }

    private static Set<TransactionInput> inputs(TransactionInput... inputs) {
        return new LinkedHashSet<>(List.of(inputs));
    }

    private static TransactionInput in(String txHash, int index) {
        return new TransactionInput(txHash, index);
    }

    private static Transaction valid(String txHash, Set<TransactionInput> inputs, Utxo... outputs) {
        return Transaction.builder()
                .blockNumber(1)
                .slot(SLOT)
                .txHash(txHash)
                .body(TransactionBody.builder().txHash(txHash).inputs(inputs).outputs(List.of())
                        .collateralInputs(Set.of()).build())
                .utxos(List.of(outputs))
                .invalid(false)
                .build();
    }

    // ---- readers -----------------------------------------------------------------------------------------------

    private static String name(String txHash, int index) {
        return NAMES.getOrDefault(txHash, txHash) + "#" + index;
    }

    private static Set<String> cacheContents(UtxoCache cache) {
        var names = new TreeSet<String>();
        cache.getAll().forEach(u -> names.add(name(u.getTxHash(), u.getOutputIndex())));
        return names;
    }

    /** {@code address_utxo}, spent rows included, one line per row. */
    private static Set<String> addressUtxoRows(UtxoRepository repository) {
        var rows = new TreeSet<String>();
        for (AddressUtxoEntity row : repository.findAll()) {
            rows.add(name(row.getTxHash(), row.getOutputIndex()) + " credential=" + row.getOwnerPaymentCredential());
        }
        return rows;
    }

    /** {@code tx_input}, one line per recorded spend. */
    private static Set<String> txInputRows(TxInputRepository repository) {
        var rows = new TreeSet<String>();
        for (TxInputEntity row : repository.findAll()) {
            rows.add(name(row.getTxHash(), row.getOutputIndex())
                    + " spentBy=" + NAMES.getOrDefault(row.getSpentTxHash(), row.getSpentTxHash())
                    + " slot=" + row.getSpentAtSlot());
        }
        return rows;
    }
}
