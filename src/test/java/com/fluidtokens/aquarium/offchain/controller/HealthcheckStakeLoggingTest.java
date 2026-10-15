package com.fluidtokens.aquarium.offchain.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LendingConfigGate;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FAB-142: a missing stake is logged ONCE, by {@code StakerService} when the status changes -- never by
 * {@code /healthcheck}, which Docker Compose polls every 10 s. Before, a node with a usable wallet and no
 * stake logged two WARNs per poll (the stake line and a "wallet or staking issues" summary). The stake is
 * still REPORTED ({@code staking_ok: false}); it just no longer decides the summary line or the log level.
 */
class HealthcheckStakeLoggingTest {

    private static final Pattern STAKE_OR_OTHER_FEATURES =
            Pattern.compile("(?i)stak|fldt|compound|liquidat|rebalanc|lending");

    private final Logger logger = (Logger) LoggerFactory.getLogger(Healthcheck.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
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

    private static Utxo ada(long lovelace) {
        Utxo utxo = new Utxo();
        utxo.setTxHash("aa".repeat(32));
        utxo.setOutputIndex(0);
        utxo.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))));
        return utxo;
    }

    /** Not syncing, parameters load, and NO stake: {@code findStakerRefInput()} is empty. */
    private static Healthcheck healthcheck(List<Utxo> walletUtxos) {
        BlockEventListener listener = mock(BlockEventListener.class);
        when(listener.getIsSyncing()).thenReturn(new AtomicBoolean(false));
        AppUtxoService utxos = mock(AppUtxoService.class);
        when(utxos.listWalletUtxo()).thenReturn(walletUtxos);
        StakerService staker = mock(StakerService.class);
        when(staker.findStakerRefInput()).thenReturn(List.of());
        return new Healthcheck(mock(ParametersService.class), staker, listener, utxos, new LendingConfigGate());
    }

    private List<ILoggingEvent> at(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void aUsableWalletWithoutAStakeLogsHealthyEveryPollAndNeverWarns() {
        Healthcheck healthcheck = healthcheck(List.of(ada(5_000_000L)));

        for (int poll = 0; poll < 3; poll++) {
            ResponseEntity<?> response = healthcheck.healthCheck();
            assertEquals(200, response.getStatusCode().value());
            Healthcheck.HealthCheck body = (Healthcheck.HealthCheck) response.getBody();
            assertTrue(body.walletOk(), "positive control, the wallet is usable");
            assertFalse(body.stakingOk(), "the missing stake is still reported");
        }

        assertEquals(List.of(), at(Level.WARN).stream().map(ILoggingEvent::getFormattedMessage).toList(),
                "the healthcheck must not warn about the stake on every poll");
        List<ILoggingEvent> infos = at(Level.INFO);
        assertEquals(3, infos.size(), infos.toString());
        infos.forEach(e -> assertTrue(e.getFormattedMessage().contains("healthy and ready"), e.getFormattedMessage()));
    }

    @Test
    void anUnusableWalletWarnsAboutTheWalletOnly() {
        ResponseEntity<?> response = healthcheck(List.of()).healthCheck();

        assertEquals(200, response.getStatusCode().value());
        assertFalse(((Healthcheck.HealthCheck) response.getBody()).stakingOk());
        List<String> warns = at(Level.WARN).stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(warns.stream().anyMatch(m -> m.contains("healthy but")), "the summary WARN is emitted: " + warns);
        warns.forEach(m -> assertFalse(STAKE_OR_OTHER_FEATURES.matcher(m).find(),
                "a WARN names the stake or another feature: " + m));
    }
}
