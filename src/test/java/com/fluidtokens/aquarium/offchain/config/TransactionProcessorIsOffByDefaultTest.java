package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.service.ScheduledTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The transaction processor builds and SUBMITS transactions from the operator's wallet, so its
 * default must be OFF: a node that is merely started spends nothing.
 *
 * <p>⚠ <b>The load-bearing detail is the ABSENCE of {@code matchIfMissing}.</b>
 * {@code @ConditionalOnProperty(havingValue = "true")} with {@code matchIfMissing = true} reads
 * almost identically and means the opposite — it arms a node whose operator said nothing. That one
 * attribute is the whole safety property, so it is asserted directly on the annotation rather than
 * inferred from behaviour.
 */
class TransactionProcessorIsOffByDefaultTest {

    private static final String KEY = "scheduling.transaction-processor.enabled";

    @Test
    @DisplayName("the processor is gated, and the gate defaults to OFF")
    void theGateExistsAndDefaultsOff() {
        ConditionalOnProperty gate =
                ScheduledTransactionService.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate, "ScheduledTransactionService is not gated at all, so a node that is "
                + "merely started will build and submit transactions");

        assertEquals("scheduling.transaction-processor", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());

        // ⛔ THE ASSERTION THAT CARRIES THE SAFETY PROPERTY.
        assertFalse(gate.matchIfMissing(),
                "matchIfMissing=true would arm the processor for every operator who never set the "
                        + "flag — the exact opposite of the intended default, and invisible in review");
    }

    @Test
    @DisplayName("application.yaml ships the key, defaulting false, with an env override")
    void theShippedDefaultIsFalse() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertTrue(yaml.contains("enabled: ${SCHEDULING_TRANSACTION_PROCESSOR_ENABLED:false}"),
                "application.yaml must ship " + KEY + " defaulting to false with an env override; "
                        + "a gate an operator cannot see or set from the environment is not usable");
    }
}
