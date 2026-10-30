package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174-2 residue R3: what {@code ev} counts. An emitted INFO or WARN line advances it by exactly one; a DEBUG
 * line — emitted or not — and an INFO the logger does not emit never do. So a gap in {@code ev} in an operator's
 * log always means a LOST line, never a filtered one.
 *
 * <p>On a private logback {@link LoggerContext}, so the level set here touches no other test.
 */
class OracleStreamLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void privateLogger() {
        LoggerContext context = new LoggerContext();
        logger = context.getLogger("oracle-stream-log-test");
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    void anEmittedInfoOrWarnAdvancesEvByOne() {
        logger.setLevel(Level.INFO);
        OracleStreamLog log = new OracleStreamLog(logger);
        assertEquals(0, log.ev());

        log.info("connected", "attempt=1");
        assertEquals(1, log.ev());
        log.warn("closed", "reason=no-pong up_s=3");
        assertEquals(2, log.ev());
        log.info("on", "network=preview", "free text");
        assertEquals(3, log.ev());

        assertEquals(List.of(" ev=1 ", " ev=2 ", " ev=3 "), evs());
    }

    @Test
    void anEmittedDebugLineCarriesTheCurrentEvWithoutAdvancingIt() {
        logger.setLevel(Level.DEBUG);
        OracleStreamLog log = new OracleStreamLog(logger);
        log.info("connected", "attempt=1");
        log.debug("connecting", "attempt=2");
        log.debug("connecting", "attempt=3");
        assertEquals(1, log.ev(), "DEBUG never advances ev");
        log.warn("refused", "status=429");
        assertEquals(2, log.ev());

        assertEquals(4, appender.list.size(), "the DEBUG lines were emitted: " + messages());
        assertEquals(List.of(" ev=1 ", " ev=1 ", " ev=1 ", " ev=2 "), evs());
    }

    @Test
    void aSuppressedDebugOrInfoNeverAdvancesEv() {
        logger.setLevel(Level.WARN);
        OracleStreamLog log = new OracleStreamLog(logger);
        log.debug("connecting", "attempt=1");
        log.info("connected", "attempt=1");
        log.info("push", "push=1");
        assertEquals(0, log.ev(), "a filtered line is not a lost line, so it takes no number");
        assertTrue(appender.list.isEmpty(), messages().toString());

        log.warn("closed", "reason=server-close(1000) up_s=1");
        assertEquals(1, log.ev());
        assertEquals(List.of(" ev=1 "), evs(), "the first emitted line is ev=1: no gap from the filtered ones");
    }

    @Test
    void warnOnChangeAdvancesOnlyOnTheWarn() {
        logger.setLevel(Level.DEBUG);
        OracleStreamLog log = new OracleStreamLog(logger);
        log.warnOnChange("push-ignored", "binary", "push-ignored", "reason=binary count=1");
        log.warnOnChange("push-ignored", "binary", "push-ignored", "reason=binary count=2");
        assertEquals(1, log.ev(), "the repeat is DEBUG");
        log.warnOnChange("push-ignored", "malformed", "push-ignored", "reason=malformed count=1");
        assertEquals(2, log.ev());
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Each emitted line's {@code ev} field, with its surrounding spaces. */
    private List<String> evs() {
        return messages().stream().map(m -> {
            int start = m.indexOf(" ev=");
            int end = m.indexOf(' ', start + 1);
            return m.substring(start, end + 1);
        }).toList();
    }
}
