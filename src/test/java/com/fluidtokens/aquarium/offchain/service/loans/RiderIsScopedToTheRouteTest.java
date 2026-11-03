package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The min-ada rider — and the F1 token-principal bracket beside it — must fire on the PAY-IN-ADVANCE route
 * only, and "route" means the route the candidate was BUILT on.
 *
 * <p>It was scoped by {@code bond.shouldLiquidationConvertToPrincipal()}, justified in-comment as
 * "only a convert-permitted loan reaches the pay-in-advance builder". <b>The convert path shipping
 * falsified that</b>: the routing reads the bond flag AND the market's action, so a convert-permitted
 * loan whose market says CONVERT now goes elsewhere. On that route the bot's change always carries
 * tokens (the liquidation fee is kept in collateral), so the rider counted the bot's own returning
 * money — measured on mainnet as a 25,425,321 lovelace phantom cost.
 *
 * <p>⚑ <b>FAB-194-3 falsified the next proxy the same way.</b> The guard then asked the market's action,
 * which named the route only while one action meant one route. A {@code CONVERT_BEFORE_ANTICIPATE} market
 * builds either — convert, or pay-in-advance when convert is impossible — so the action no longer says which
 * one a candidate was built on. The guards now read {@code pass.route}, which {@code plannedRoute} sets
 * before anything is recorded and only a fallback changes; and nothing that labels or prices a record asks
 * {@code actionFor(} any more.
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

    /** The guard both the rider and the F1 bracket must sit behind: the route the candidate was built on. */
    private static final String ROUTE_GUARD = "if (ROUTE_PAY_IN_ADVANCE.equals(pass.route)) {";

    /** The source of the method whose declaration starts with {@code signature}, through its closing brace. */
    private static String method(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at > 0, signature + " is gone; this test no longer checks anything — repoint it");
        int end = src.indexOf("\n    }\n", at);
        assertTrue(end > at, "no end found for " + signature);
        return src.substring(at, end);
    }

    @Test
    @DisplayName("the rider's guard asks the ROUTE BUILT, not the bond flag or the market's action")
    void theRiderIsGuardedByTheRouteBuilt() throws Exception {
        String src = Files.readString(SOURCE);
        String minAdaFunded = method(src, "private BigInteger minAdaFunded(");

        assertTrue(minAdaFunded.contains(ROUTE_GUARD + "\n            AssetType collateralAsset"),
                "the rider must be guarded by the route the candidate was built on; if this guard changed, "
                        + "the convert path's change output is being counted as a cost again");
        assertFalse(minAdaFunded.contains("if (assessment.bond().datum().shouldLiquidationConvertToPrincipal()) {"),
                "the rider is scoped by the bare bond flag again — that flag stopped selecting "
                        + "pay-in-advance uniquely when the convert path shipped");
        assertFalse(minAdaFunded.contains("actionFor("),
                "the rider asks the market's action again — a CONVERT_BEFORE_ANTICIPATE market builds either "
                        + "route, so the action is a proxy for the route");
    }

    @Test
    @DisplayName("the F1 bracket's guard asks the ROUTE BUILT, the same question as the rider's")
    void theF1BracketIsGuardedByTheRouteBuilt() throws Exception {
        String src = Files.readString(SOURCE);
        String record = method(src, "private Optional<Verdict> record(");

        assertTrue(record.contains(ROUTE_GUARD + "\n            AssetType payInAdvancePrincipal"),
                "the F1 bracket — which REPLACES the profit floor for a token principal — must be guarded by "
                        + "the route the candidate was built on");
        assertFalse(record.contains("actionFor("),
                "record() asks the market's action again; the row it writes must follow the route built");
    }

    @Test
    @DisplayName("nothing that labels a record asks the market's action")
    void theVariantAndTheDecisionHelpersReadTheRoute() throws Exception {
        String src = Files.readString(SOURCE);

        String variantOf = method(src, "private static String variantOf(CandidatePass pass)");
        assertTrue(variantOf.contains("switch (pass.route)"), "the variant must be mapped from the route built");
        assertFalse(variantOf.contains("actionFor("), "the variant asks the market's action again");

        int helpers = 0;
        for (int at = src.indexOf("private LiquidationDecision decision("); at >= 0;
             at = src.indexOf("private LiquidationDecision decision(", at + 1)) {
            String helper = src.substring(at, src.indexOf("\n    }\n", at));
            assertFalse(helper.contains("actionFor("), "a decision helper asks the market's action again");
            assertTrue(helper.contains("CandidatePass pass"), "a decision helper no longer receives the route");
            helpers++;
        }
        assertTrue(helpers >= 2, "the decision helpers moved; this test no longer checks them");

        assertFalse(method(src, "private LiquidationDecision certainlyUnprofitable(").contains("actionFor("),
                "certainlyUnprofitable() asks the market's action again");
    }

    @Test
    @DisplayName("the route is planned in one place, the only one that asks MarketGate for the action")
    void theRouteIsPlannedOnceFromTheMarketsAction() throws Exception {
        String src = Files.readString(SOURCE);
        String planned = method(src, "private String plannedRoute(");

        assertTrue(planned.contains("new MarketGate(configuration).actionFor("),
                "the route must be decided by the market's action — the bond flag says MAY, the market says HOW");
        assertTrue(java.util.regex.Pattern.compile("case (?:[A-Z_]+, )*CONVERT(?:, [A-Z_]+)* -> ROUTE_CONVERT;")
                        .matcher(planned).find(),
                "the CONVERT action must plan the convert route — the route whose change output was being "
                        + "charged to the operator");
        assertTrue(planned.contains("case ANTICIPATE -> ROUTE_PAY_IN_ADVANCE;"),
                "the ANTICIPATE action must plan the pay-in-advance route");

        int calls = src.split("actionFor\\(", -1).length - 1;
        assertTrue(calls == 1, "actionFor( is called " + calls + " times in the executor; plannedRoute must be the "
                + "only caller, or a second copy of the routing rule exists — and the first copy is what drifted");
        assertFalse(src.contains("isPayInAdvanceRoute"), "the action-reading route predicate is back");
    }
}
