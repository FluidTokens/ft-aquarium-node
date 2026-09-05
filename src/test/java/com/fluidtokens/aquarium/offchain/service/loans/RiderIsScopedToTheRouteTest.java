package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The min-ada rider must fire on the PAY-IN-ADVANCE route only.
 *
 * <p>It was scoped by {@code bond.shouldLiquidationConvertToPrincipal()}, justified in-comment as
 * "only a convert-permitted loan reaches the pay-in-advance builder". <b>The convert path shipping
 * falsified that</b>: the routing reads the bond flag AND the market's action, so a convert-permitted
 * loan whose market says CONVERT now goes elsewhere. On that route the bot's change always carries
 * tokens (the liquidation fee is kept in collateral), so the rider counted the bot's own returning
 * money — measured on mainnet as a 25,425,321 lovelace phantom cost.
 *
 * <p>⚠ <b>This is a SOURCE assertion and it is the honest shape for this defect.</b> The regression
 * is not "the rider computes the wrong number" — the arithmetic was always right — it is "the guard
 * names a proxy instead of the routing's own discriminator". A proxy that is true when written is
 * exactly what drifts, so what must be pinned is WHICH QUESTION THE GUARD ASKS. It asserts the guard
 * at its call site, not merely that a correct helper exists somewhere.
 */
class RiderIsScopedToTheRouteTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidationExecutor.java");

    @Test
    @DisplayName("the rider's guard asks the ROUTE, not the bond flag")
    void theRiderIsGuardedByTheRoute() throws Exception {
        String src = Files.readString(SOURCE);

        assertTrue(src.contains("if (isPayInAdvanceRoute(assessment)) {"),
                "the rider must be guarded by the route it applies to; if this guard changed, the "
                        + "convert path's change output is being counted as a cost again");

        assertFalse(src.contains("if (assessment.bond().datum().shouldLiquidationConvertToPrincipal()) {\n"
                        + "            BigInteger rider"),
                "the rider is scoped by the bare bond flag again — that flag stopped selecting "
                        + "pay-in-advance uniquely when the convert path shipped");
    }

    @Test
    @DisplayName("the route predicate asks MarketGate, the same question the routing asks")
    void theRouteIsTheRoutingsOwnDiscriminator() throws Exception {
        String src = Files.readString(SOURCE);
        int at = src.indexOf("private boolean isPayInAdvanceRoute(");
        assertTrue(at > 0, "isPayInAdvanceRoute is gone; the rider's scope is a proxy again");

        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(body.contains("new MarketGate(configuration).actionFor("),
                "the route must be decided by the SAME MarketGate call the routing makes — anything "
                        + "else is a second copy of the routing rule, and the first copy is what drifted");
        assertTrue(body.contains("Action.CONVERT"),
                "the predicate must exclude the CONVERT action; that is the route whose change output "
                        + "was being charged to the operator");
    }
}
