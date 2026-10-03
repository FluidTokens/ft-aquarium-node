package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-134 B2 — the wallet is read <b>only from the local Yaci index, by payment credential</b>.
 *
 * <h2>Why there is no provider here any more</h2>
 * Giovanni ruled it first-hand: the wallet is never read from Blockfrost. The index is complete
 * because the startup wallet sweep and its {@code walletReady} gate (FAB-134-3) guarantee every
 * wallet UTxO is an output the index watched being created — which is what retires the old fear
 * ({@code officina:yaci-store-index-scoping} §5) that an index-backed view is silently partial.
 * Because the gate closes that hole, there is NO fallback: an empty index answer is the answer.
 *
 * <h2>What each test kills</h2>
 * <ul>
 *   <li>a lookup by the base ADDRESS rather than by the payment CREDENTIAL — the enterprise-address
 *       row would vanish;</li>
 *   <li>a credential derived from the stake part rather than the payment part;</li>
 *   <li>the {@code AddressUtxo} mapper overload, which writes raw {@code scriptRef} hex into
 *       {@code referenceScriptHash};</li>
 *   <li>a database failure turned into an empty wallet;</li>
 *   <li>an order that depends on the query's (absent) ORDER BY.</li>
 * </ul>
 */
class AppUtxoServiceTest {

    private static final Account ACCOUNT = new Account(Networks.testnet());

    /** Derived exactly as {@code TankUtxoStorage:47} derives the credential the index keeps. */
    private static final String WALLET_PKH = ACCOUNT.getBaseAddress().getPaymentCredentialHash()
            .map(HexUtil::encodeHexString).get();

    private static final String TX_A = "aa".repeat(32);
    private static final String TX_B = "bb".repeat(32);
    private static final String TX_C = "cc".repeat(32);

    /**
     * An in-memory Yaci repository serving ONLY the payment-credential query. Any other method —
     * {@code findUnspentByOwnerAddr} included — throws, so a service that stops asking by
     * credential fails loudly rather than reading an empty answer.
     */
    private static UtxoRepository index(Function<String, Optional<List<AddressUtxoEntity>>> byCredential,
                                        List<String> askedFor) {
        return (UtxoRepository) Proxy.newProxyInstance(
                AppUtxoServiceTest.class.getClassLoader(),
                new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findUnspentByOwnerPaymentCredential" -> {
                        askedFor.add((String) args[0]);
                        yield byCredential.apply((String) args[0]);
                    }
                    case "toString" -> "stub UtxoRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the wallet read called " + method.getName() + "; it must read the index "
                                    + "by the wallet's PAYMENT CREDENTIAL and nothing else");
                });
    }

    private static UtxoRepository index(List<AddressUtxoEntity> walletRows) {
        return index(credential -> Optional.of(WALLET_PKH.equals(credential) ? walletRows : List.of()),
                new ArrayList<>());
    }

    private static AddressUtxoEntity row(String txHash, int outputIndex, String ownerAddr, long lovelace) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(outputIndex);
        entity.setOwnerAddr(ownerAddr);
        Amt amount = new Amt();
        amount.setUnit("lovelace");
        amount.setQuantity(BigInteger.valueOf(lovelace));
        entity.setAmounts(new ArrayList<>(List.of(amount)));
        return entity;
    }

    @Test
    void theLookupIsByThePaymentCredentialDerivedLikeTheIndexDerivesIt() {
        List<String> askedFor = new ArrayList<>();
        var service = new AppUtxoService(ACCOUNT, index(
                credential -> Optional.of(WALLET_PKH.equals(credential)
                        ? List.of(row(TX_A, 0, ACCOUNT.baseAddress(), 5_000_000L)) : List.of()),
                askedFor));

        List<Utxo> wallet = service.listWalletUtxo();

        assertEquals(List.of(WALLET_PKH), askedFor,
                "the wallet must be looked up once, by the PAYMENT credential TankUtxoStorage indexes — "
                        + "not by an address, and not by the stake part");
        assertEquals(1, wallet.size());
        assertEquals(TX_A, wallet.get(0).getTxHash());
    }

    /** ⛔ The address-keyed query would miss every output under the same key at another address. */
    @Test
    void anOutputAtTheENTERPRISEAddressUnderTheSameKeyIsPartOfTheWallet() {
        var service = new AppUtxoService(ACCOUNT, index(List.of(
                row(TX_A, 0, ACCOUNT.baseAddress(), 5_000_000L),
                row(TX_B, 1, ACCOUNT.enterpriseAddress(), 7_000_000L))));

        List<Utxo> wallet = service.listWalletUtxo();

        assertEquals(2, wallet.size(), "the enterprise-address output is the bot's money too");
        assertTrue(wallet.stream().anyMatch(u -> ACCOUNT.enterpriseAddress().equals(u.getAddress())),
                "the output at the enterprise address must be returned");
    }

    /**
     * ⛔ The {@code AddressUtxo} overload maps raw {@code scriptRef} hex into
     * {@code referenceScriptHash}; the entity overload keeps the derived hash. Every spend decision
     * reads that field, so the faithful one is the only acceptable mapper.
     */
    @Test
    void aReferenceScriptOutputCarriesItsHASHNotTheRawScriptRef() {
        String scriptHash = "ab".repeat(28);
        AddressUtxoEntity withScript = row(TX_A, 0, ACCOUNT.baseAddress(), 20_000_000L);
        withScript.setReferenceScriptHash(scriptHash);
        withScript.setScriptRef("8203" + "4e4d01000033222220051200120011");
        var service = new AppUtxoService(ACCOUNT, index(List.of(withScript)));

        Utxo utxo = service.listWalletUtxo().get(0);

        assertEquals(scriptHash, utxo.getReferenceScriptHash(),
                "the reference-script HASH must come through, not the raw scriptRef bytes");
    }

    /** ⛔ A database failure is not an empty wallet; turning one into the other understates silently. */
    @Test
    void aDatabaseFailurePROPAGATESAndIsNeverAnEmptyWallet() {
        var failure = new DataAccessResourceFailureException("connection refused");
        var service = new AppUtxoService(ACCOUNT, index(credential -> {
            throw failure;
        }, new ArrayList<>()));

        var thrown = assertThrows(DataAccessResourceFailureException.class, service::listWalletUtxo);
        assertSame(failure, thrown);
    }

    /** An empty answer is authoritative: the gate guarantees the index is complete. */
    @Test
    void anEmptyOptionalOrAnEmptyListIsAnEmptyWallet() {
        assertTrue(new AppUtxoService(ACCOUNT, index(credential -> Optional.empty(), new ArrayList<>()))
                .listWalletUtxo().isEmpty());
        assertTrue(new AppUtxoService(ACCOUNT, index(List.of())).listWalletUtxo().isEmpty());
    }

    /** The query has no ORDER BY; coin selection must not depend on how Postgres felt today. */
    @Test
    void theOrderIsDeterministicByTxHashThenOutputIndex() {
        var service = new AppUtxoService(ACCOUNT, index(List.of(
                row(TX_C, 0, ACCOUNT.baseAddress(), 1_000_000L),
                row(TX_A, 2, ACCOUNT.baseAddress(), 1_000_000L),
                row(TX_B, 0, ACCOUNT.enterpriseAddress(), 1_000_000L),
                row(TX_A, 0, ACCOUNT.baseAddress(), 1_000_000L))));

        List<String> order = service.listWalletUtxo().stream()
                .map(u -> u.getTxHash().substring(0, 2) + "#" + u.getOutputIndex())
                .toList();

        assertEquals(List.of("aa#0", "aa#2", "bb#0", "cc#0"), order);
    }

    /** ⛔ No provider: the class can no longer even be handed one. */
    @Test
    void theServiceHoldsNoProvider() {
        assertTrue(Arrays.stream(AppUtxoService.class.getDeclaredFields())
                        .map(f -> f.getType().getName().toLowerCase())
                        .noneMatch(type -> type.contains("blockfrost") || type.contains("backendservice")),
                "AppUtxoService must not hold a Blockfrost or backend-service dependency");
        assertTrue(Arrays.stream(AppUtxoService.class.getDeclaredConstructors())
                        .flatMap(c -> Arrays.stream(c.getParameterTypes()))
                        .map(t -> t.getName().toLowerCase())
                        .noneMatch(type -> type.contains("blockfrost") || type.contains("backendservice")),
                "no AppUtxoService constructor may take a provider");
    }
}
