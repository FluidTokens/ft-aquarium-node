package com.fluidtokens.aquarium.offchain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ <b>A missing stake is said ONCE, loudly — and again only when it changes.</b>
 *
 * <p>{@link StakerService#findStakerRefInput()} runs at boot, on every tank cycle and on every
 * healthcheck. It used to WARN "It was not possible to find staked tokens." on every one of those
 * calls, which taught the reader that the WARN meant nothing — and never said the consequence. Now a
 * transition to ABSENT warns once that tank processing is disabled; a transition to PRESENT says once
 * that it is enabled; an unchanged status says nothing above DEBUG.
 *
 * <p>Giovanni's ruling 2026-10-15: the missing-stake message speaks of TANKS only. The stake gates the
 * tank processor; nothing else may be implied by it.
 */
class StakerServiceTest {

    /** Public test vector (cardano-client-lib v0.7.2 AccountTest) — never a real seed. */
    private static final String MNEMONIC = "damp wish scrub sentence vibrant gauge tumble raven game extend "
            + "winner acid side amused vote edge affair buzz hospital slogan patient drum day vital";
    private static final String STAKER_HASH = "11".repeat(28);
    private static final Pattern OTHER_FEATURES = Pattern.compile("(?i)compound|liquidat|rebalanc|lending");

    private final Account account = Account.createFromMnemonic(Networks.mainnet(), MNEMONIC);
    private final UtxoRepository repository = mock(UtxoRepository.class);
    private final StakerContractService stakerContract = mock(StakerContractService.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(StakerService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        when(stakerContract.getScriptHash()).thenReturn(HexUtil.decodeHexString(STAKER_HASH));
        when(stakerContract.getScriptHashHex()).thenReturn(STAKER_HASH);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private String stakeUnit(Account owner) {
        return STAKER_HASH + HexUtil.encodeHexString(owner.getBaseAddress().getDelegationCredentialHash().get());
    }

    private static AddressUtxoEntity utxo(String txHash, int index, String unit) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(index);
        entity.setOwnerPaymentCredential(STAKER_HASH);
        entity.setAmounts(new ArrayList<>(List.of(
                Amt.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000L)).build(),
                Amt.builder().unit(unit).quantity(BigInteger.ONE).build())));
        return entity;
    }

    /** Somebody else's stake: sits at the staker script, keyed on another wallet's stake key. */
    private AddressUtxoEntity someoneElsesStake() {
        Account other = Account.createFromMnemonic(Networks.mainnet(), MNEMONIC, 1, 0);
        return utxo("bb".repeat(32), 0, stakeUnit(other));
    }

    private AddressUtxoEntity ourStake() {
        return utxo("aa".repeat(32), 2, stakeUnit(account));
    }

    private record Call(List<TransactionInput> result, List<ILoggingEvent> events) {
        long at(Level level) {
            return events.stream().filter(e -> e.getLevel() == level).count();
        }
    }

    @Test
    void theStakeStatusIsLoggedOncePerChangeAndTheResultIsUnchanged() {
        Optional<List<AddressUtxoEntity>> absent = Optional.of(List.of(someoneElsesStake()));
        Optional<List<AddressUtxoEntity>> present = Optional.of(List.of(someoneElsesStake(), ourStake()));
        when(repository.findUnspentByOwnerPaymentCredential(ArgumentMatchers.eq(STAKER_HASH),
                ArgumentMatchers.any(Pageable.class)))
                .thenReturn(absent, absent, present, present, absent);

        var service = new StakerService(account, repository, stakerContract);
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            appender.list.clear();
            var result = service.findStakerRefInput();
            calls.add(new Call(result, List.copyOf(appender.list)));
        }

        // The returned lists: exactly what the method returned before — our stake only, by out-ref.
        var ours = List.of(TransactionInput.builder().transactionId("aa".repeat(32)).index(2).build());
        assertEquals(List.of(List.of(), List.of(), ours, ours, List.of()),
                calls.stream().map(Call::result).toList());

        assertEquals(List.of(1L, 0L, 0L, 0L, 1L), calls.stream().map(c -> c.at(Level.WARN)).toList(),
                "one WARN on each transition to absent (calls 1 and 5), none while unchanged");
        assertEquals(List.of(0L, 0L, 1L, 0L, 0L), calls.stream().map(c -> c.at(Level.INFO)).toList(),
                "one INFO on the transition to present (call 3), none while unchanged");
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), calls.stream().map(c -> c.at(Level.ERROR)).toList());

        for (int i : new int[]{0, 4}) {
            String warning = calls.get(i).events().stream().filter(e -> e.getLevel() == Level.WARN)
                    .findFirst().orElseThrow().getFormattedMessage();
            String lower = warning.toLowerCase();
            assertTrue(lower.contains("tank") && lower.contains("disabled"),
                    "the warning must say tank processing is disabled: " + warning);
            assertTrue(warning.contains(account.stakeAddress()), "the warning must name the stake address: " + warning);
            assertFalse(OTHER_FEATURES.matcher(warning).find(),
                    "the missing-stake warning speaks of tanks only: " + warning);
        }
        String enabled = calls.get(2).events().stream().filter(e -> e.getLevel() == Level.INFO)
                .findFirst().orElseThrow().getFormattedMessage();
        assertTrue(enabled.toLowerCase().contains("enabled") && enabled.contains("aa".repeat(32)),
                "the recovery line must say tank processing is enabled and name the ref input: " + enabled);
    }

    /** Boot is the first observation: an absent stake is announced loudly at startup. */
    @Test
    void theFirstObservationAtBootIsLoud() {
        when(repository.findUnspentByOwnerPaymentCredential(ArgumentMatchers.eq(STAKER_HASH),
                ArgumentMatchers.any(Pageable.class)))
                .thenReturn(Optional.of(List.of()));

        new StakerService(account, repository, stakerContract).init();

        assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count());
    }
}
