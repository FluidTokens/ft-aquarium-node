package com.fluidtokens.aquarium.offchain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The two properties an operator is promised about the readiness UI.</b>
 *
 * <p>The interesting one is the flag. This UI ships in the same image every operator runs, most of
 * whom will never turn it on, and it has <b>no authentication</b> — so "off by default" is not a
 * convenience, it is the security posture. A default that silently stopped working would expose loan
 * positions on every node, and nothing about the node's behaviour would look different.
 */
class LiquidationReadinessControllerTest {

    @Configuration
    @Import(LiquidationReadinessController.class)
    static class Ctx {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Ctx.class);
    }

    /** ⛔ Unset means ABSENT: no bean, no route, nothing served. */
    @Test
    void theUiDoesNotExistUnlessItIsTurnedOn() {
        runner().run(ctx -> assertTrue(
                ctx.getBeanNamesForType(LiquidationReadinessController.class).length == 0,
                "the readiness controller was constructed without loans.ui.enabled=true. It has no "
                        + "authentication and shows loan positions, so its absence by default is the "
                        + "posture, not a convenience"));
    }

    /** ⚠ And FALSE means absent too — a flag that only honours 'true' vs unset would be a trap. */
    @Test
    void anExplicitFalseAlsoLeavesItAbsent() {
        runner().withPropertyValues("loans.ui.enabled=false")
                .run(ctx -> assertEquals(0,
                        ctx.getBeanNamesForType(LiquidationReadinessController.class).length));
    }

    /**
     * Turning it on constructs it. ⚠ The context fails for a MISSING-DEPENDENCY reason rather than a
     * condition one, which is the proof that the condition passed: this controller takes only
     * {@code ObjectProvider}s of the lending beans plus two config objects, so the failure here is
     * about {@code AppConfig}, never about the flag.
     */
    @Test
    void turningItOnMakesTheConditionPass() {
        runner().withPropertyValues("loans.ui.enabled=true").run(ctx -> {
            if (ctx.getStartupFailure() != null) {
                assertTrue(!ctx.getStartupFailure().toString().contains("ui.enabled"),
                        "the context failed on the FLAG rather than on a missing collaborator, which "
                                + "would mean the condition never passed: " + ctx.getStartupFailure());
            } else {
                assertEquals(1, ctx.getBeanNamesForType(LiquidationReadinessController.class).length);
            }
        });
    }

    // ---- the ordering contract -------------------------------------------------------------------

    private static LiquidationReadinessController.Row row(String id, Double healthFactor) {
        return new LiquidationReadinessController.Row(id, id + "#0", "lovelace", BigInteger.TEN,
                "tok", BigInteger.TEN, healthFactor, null, null, null,
                null, null, null, "PLAIN LIQUIDATE", "", null);
    }

    /**
     * ⛔ <b>Closest to liquidation first, and UNKNOWN LAST.</b>
     *
     * <p>The ordering is the whole product: an operator reads the top of this list to decide what to
     * prepare for. ⚠ And the null placement is the load-bearing half — a loan whose health cannot be
     * computed sorting to the <b>top</b> would push the genuinely urgent ones off the fold, and it
     * would look exactly like a correct list.
     */
    @Test
    void theListPutsTheClosestToLiquidationFirstAndTheUncomputableLast() {
        List<LiquidationReadinessController.Row> rows = new ArrayList<>(List.of(
                row("healthy", 2.4), row("unknown", null), row("critical", 0.87), row("near", 1.05)));

        rows.sort(Comparator.comparingDouble(LiquidationReadinessController.Row::sortKey));

        assertEquals(List.of("critical", "near", "healthy", "unknown"),
                rows.stream().map(LiquidationReadinessController.Row::loanId).toList(),
                "closest to liquidation first; a row whose health is unknown must never outrank one "
                        + "that is measurably about to go");
    }

    /**
     * ⚠ An uncomputable figure is {@code null} plus a reason, and the row carries no zero anywhere to
     * be mistaken for one. <b>A fabricated number on this page would be acted on.</b>
     */
    @Test
    void anUncomputableRowCarriesNullsAndAReasonRatherThanZeros() {
        var r = new LiquidationReadinessController.Row("id", "id#0", "lovelace", BigInteger.TEN,
                "tok", BigInteger.TEN, null, null, null, "no usable oracle feed",
                null, null, "no usable oracle feed", "UNKNOWN", "no bond indexed", null);

        assertNull(r.healthFactor());
        assertNull(r.feeValueLovelace());
        assertNull(r.advanceLovelace());
        assertEquals("no usable oracle feed", r.healthUnknownReason());
        assertTrue(r.sortKey() == Double.MAX_VALUE, "and it sorts last");
    }
}
