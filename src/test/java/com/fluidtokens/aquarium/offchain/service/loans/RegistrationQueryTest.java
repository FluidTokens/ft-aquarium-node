package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-125: the reward-account registration check reads the account's CURRENT state -- its newest event.
 * Every live account had exactly one event on 2026-10-01, so the live run could not tell newest-first from
 * oldest-first; this keyless test can.
 */
class RegistrationQueryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theQueryAsksForTheNewestEventOnly() {
        String path = MainnetReferenceScriptsTest.registrationsPath("stake17abc");
        assertTrue(path.contains("order=desc") && path.contains("count=1"), path);
    }

    /** A history registered → deregistered, newest first: the account is NOT registered now. */
    @Test
    void aDeregisteredAccountReadsAsDeregistered() throws Exception {
        String newestFirst = """
                [{"tx_hash":"bb","action":"deregistered"},{"tx_hash":"aa","action":"registered"}]""";
        assertEquals("deregistered", MainnetReferenceScriptsTest.mostRecentAction(MAPPER.readTree(newestFirst)));
    }

    @Test
    void noHistoryReadsAsNothing() throws Exception {
        assertNull(MainnetReferenceScriptsTest.mostRecentAction(MAPPER.readTree("[]")));
    }
}
