package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A convert had NEVER built — on any network, under any configuration — because milliseconds
 * were handed to the ledger as a slot number.</b>
 *
 * <h2>The defect</h2>
 * {@code ConvertLiquidationRouter.buildConvertLiquidation(…, long validFromMillis, long validToMillis)}
 * passed those two straight into {@code ConvertTransactionBuilder.Request}, whose last two components
 * are {@code long validFromSlot, long validToSlot}. <b>Both sides {@code long}, both positional — javac
 * cannot see it.</b> The ledger could:
 *
 * <pre>PastHorizon … (ELit (SlotNo 1788596164000))</pre>
 *
 * 1,788,596,164,000 is ~1.79e12 against a mainnet tip near 1.4e8 — <b>about 12,776× past the tip</b>,
 * rejected as "beyond the foreseeable end of the current era". Read as POSIX milliseconds it is
 * 2026-09-05T08:16:04Z, and the log line reporting it is stamped 08:16:47Z. <b>Forty-three seconds
 * apart: it is wall-clock time being used as a slot.</b>
 *
 * <h2>⚑ How it was localised, which is the reusable part</h2>
 * Not by reading the convert path looking for something novel — by <b>counting `toSlot(` per
 * router</b>. Convert had zero; every sibling had two. <b>The question that finds this class of bug is
 * "what is this path omitting that the working ones do", never "what is this path doing wrong"</b> —
 * and that count is now asserted below, so the next router added without a conversion fails here
 * rather than on chain.
 */
class ValidityIntervalIsSlotsNotMillisTest {

    private static final Path SRC =
            Path.of("src/main/java/com/fluidtokens/aquarium/offchain/service/loans");

    /** A real millisecond timestamp — the exact value the ledger rejected. */
    private static final long THE_REJECTED_VALUE = 1_788_596_164_000L;

    /** A plausible mainnet slot at the same instant, for contrast. */
    private static final long A_REAL_SLOT = 196_962_323L;

    // ==========================================================================================

    /**
     * ⛔ THE GUARD, at the boundary the confusion crosses. It lives on the record rather than in the
     * router because <b>a rule stated where the mistake is made fires for every future caller</b>;
     * one stated in the caller that got it wrong protects only that caller.
     */
    @Test
    void theRequestRefusesMillisecondsWhereASlotBelongs() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> request(THE_REJECTED_VALUE, THE_REJECTED_VALUE + 120_000));

        assertTrue(refused.getMessage().contains("MILLISECONDS"),
                "the message must name the confusion, not just the range: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(THE_REJECTED_VALUE)),
                "and it must name the offending value: " + refused.getMessage());
    }

    /** ⚠ Each side independently — a guard that only fires when BOTH are wrong is half a guard. */
    @Test
    void eitherSideAloneIsEnoughToRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> request(THE_REJECTED_VALUE, A_REAL_SLOT), "validFrom alone");
        assertThrows(IllegalArgumentException.class,
                () -> request(A_REAL_SLOT, THE_REJECTED_VALUE), "validTo alone");
    }

    /**
     * ⛔ AND THE CONTROL, or the guard could be "reject everything". A real slot pair must construct,
     * and so must a slot far in the future — the threshold has to hold for the lifetime of the chain,
     * not just for today's tip.
     */
    @Test
    void realSlotsStillConstructIncludingOnesFarInTheFuture() {
        request(A_REAL_SLOT, A_REAL_SLOT + 150);
        // A slot in roughly the year 2100: one per second from 2017 is ~2.4e9, still far below the
        // threshold. If this ever fails, the guard has been tightened past what a slot can reach.
        request(2_400_000_000L, 2_400_000_150L);
    }

    /**
     * ⚑ SIBLING PARITY, ASSERTED AS A COUNT — because that count is what found the bug. Every path
     * that builds a validity interval converts milliseconds to slots at its own boundary; convert was
     * the only one that did not, and the only one that had never built.
     *
     * <p>⚠ A source scan is a weak instrument, used here where it is the honest one: driving each
     * router for real needs a live evaluator, a resolved pool and an oracle feed. It cannot see
     * whether a conversion is <em>correct</em> — the guard above does that — only that one is
     * <em>present</em>, which is exactly the thing that was missing.
     */
    @Test
    void everyLiquidationRouterConvertsMillisecondsToSlotsAtItsBoundary() throws IOException {
        List<String> withoutConversion = new ArrayList<>();
        for (String router : List.of("ConvertLiquidationRouter", "PayInAdvanceLiquidationRouter")) {
            String source = Files.readString(SRC.resolve(router + ".java"));
            if (!source.contains("toSlot(")) {
                withoutConversion.add(router);
            }
        }
        assertEquals(List.of(), withoutConversion,
                "a router that builds a validity interval without converting milliseconds to slots "
                        + "hands the ledger a PastHorizon and never builds — silently, because both "
                        + "types are long");
    }

    /**
     * ⛔ <b>AND THAT THE CONVERTED VALUE IS WHAT REACHES THE BUILDER — because everything above
     * survives putting the bug back.</b>
     *
     * <p>Measured while writing this: restoring {@code validFromMillis, validToMillis} at the
     * builder call site left this whole class GREEN. The guard only fires when a Request is actually
     * constructed, and no test constructs one through the router; the scan above only proves a
     * conversion <em>exists in the file</em>, and {@code validitySlots} still exists when nothing
     * calls it.
     *
     * <p>⚠ <b>That is the second time today a test I wrote reproduced the defect it was written
     * for</b> — the first was asserting {@code referenceScripts()} builds the right map while the
     * call site could still pass {@code Map.of()}. <b>The pattern is the same both times: I tested
     * the thing I fixed and not the seam where it is used.</b> A conversion that exists and a
     * conversion that is applied are two claims, and only the second was ever the bug.
     */
    @Test
    void theBuilderCallSiteReceivesTheCONVERTEDValues() throws IOException {
        String source = Files.readString(SRC.resolve("ConvertLiquidationRouter.java"));

        int request = source.indexOf("new ConvertTransactionBuilder.Request(");
        assertTrue(request > 0, "the builder call site moved; this assertion is now about nothing");
        String call = source.substring(request, source.indexOf("));", request));

        assertTrue(call.contains("slots["),
                "the call site does not pass the CONVERTED slots. A conversion that exists and is "
                        + "not applied is the same defect with an alibi:\n" + call);
        assertTrue(!call.contains("validFromMillis") && !call.contains("validToMillis"),
                "milliseconds are back at the builder call site — this is the PastHorizon bug:\n"
                        + call);
    }

    /**
     * ⚠ Proof of harness for the scan: it must be able to see a conversion that IS there, or its
     * inability to find a missing one proves nothing.
     */
    @Test
    void theScanCanSeeAConversionThatIsPresent() throws IOException {
        assertTrue(Files.readString(SRC.resolve("PayInAdvanceLiquidationRouter.java"))
                        .contains("converters.time().toSlot("),
                "the sibling's conversion is the positive control and it must be visible to the scan");
    }

    // ------------------------------------------------------------------------------------------

    /** A Request differing from a valid one only in the two values under test. */
    private static ConvertTransactionBuilder.Request request(long from, long to) {
        return new ConvertTransactionBuilder.Request(
                null, null, null, null, null, null, null, java.util.Map.of(),
                null, null, null, null, false, "addr_order", "addr_change", from, to);
    }
}
