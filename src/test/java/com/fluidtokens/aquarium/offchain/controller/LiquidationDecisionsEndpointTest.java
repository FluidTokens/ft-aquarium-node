package com.fluidtokens.aquarium.offchain.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationDecisionLog;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /loans/liquidations} — the endpoint an operator judges the bot by before arming it.
 * <p>
 * The controller is constructed directly, exactly as {@code OracleStatusEndpointTest} constructs
 * {@link LoanController}: no Spring context, so what is under test is the view mapping and nothing
 * else. The JSON assertions go through a plain {@link ObjectMapper} because the two claims that
 * matter — snake_case field names and {@code tx_cbor_hex} being <em>absent</em> rather than null —
 * are claims about serialisation, not about the record.
 */
class LiquidationDecisionsEndpointTest {

    private static final long NOW = 1_700_000_000_000L;

    private static final String CBOR = "84a4008182582000".repeat(4);

    private static AppConfig.LiquidationConfiguration config(
            AppConfig.LiquidationConfiguration.Mode mode, boolean enabled) {
        return new AppConfig.LiquidationConfiguration(mode, enabled, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, 30);
    }

    /** A fully populated decision — every "null unless built" field carries a value. */
    private static LiquidationDecision wouldSubmit(String loanId) {
        return new LiquidationDecision(NOW, loanId, "aa#0", "bb#0", LiquidationDecision.VARIANT,
                LiquidationDecision.Outcome.WOULD_SUBMIT, "WOULD_SUBMIT", "fee 50 - fee 2 - margin 1.5",
                true, BigInteger.valueOf(110_000_000), BigInteger.ZERO, BigInteger.valueOf(50_000_000),
                AssetType.LOVELACE, BigInteger.valueOf(50_000_000), BigInteger.valueOf(2_000_000),
                BigInteger.valueOf(1_500_000), BigInteger.valueOf(46_500_000),
                "cc".repeat(32), 19_838, CBOR, 3, 5, 2, 6);
    }

    private static LiquidationDecisionLog logWith(List<LiquidationDecision> decisions,
                                                  Map<LiquidationExclusion, Integer> exclusions,
                                                  int bondsScanned, int buildable) {
        LiquidationDecisionLog log = new LiquidationDecisionLog(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false));
        log.recordRun(NOW, bondsScanned, 0, buildable, exclusions);
        decisions.forEach(log::record);
        return log;
    }

    // ======================================================================================

    /**
     * The whole point of the shadow workstream in one assertion pair: the bot says it would have
     * submitted, and says in the same breath that it is not armed.
     */
    @Test
    void reportsTheModeTheArmingFlagAndTheLastRun() {
        LiquidationDecisionLog log = logWith(List.of(wouldSubmit("a1")),
                Map.of(LiquidationExclusion.NOT_LIQUIDATABLE, 7,
                        LiquidationExclusion.LOAN_NOT_FOUND, 1),
                9, 1);
        var controller = new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log);

        var view = controller.liquidations(50, false);

        assertEquals("SHADOW", view.mode());
        assertFalse(view.armed(), "shadow can never be armed");
        assertEquals("2023-11-14T22:13:20Z", view.lastRunAt());
        assertEquals(9, view.bondsScanned());
        assertEquals(1, view.buildable());
        assertEquals(Map.of("NOT_LIQUIDATABLE", 7, "LOAN_NOT_FOUND", 1), view.exclusions());
        assertEquals(1, view.decisions().size());
        assertEquals("WOULD_SUBMIT", view.decisions().getFirst().outcome());
    }

    /** Arming takes both switches; either one alone leaves the bot unarmed. */
    @Test
    void armedRequiresBothLiveModeAndTheEnabledFlag() {
        LiquidationDecisionLog log = logWith(List.of(), Map.of(), 0, 0);

        assertFalse(new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.LIVE, false), log)
                .liquidations(50, false).armed());
        assertFalse(new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, true), log)
                .liquidations(50, false).armed());
        assertTrue(new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.LIVE, true), log)
                .liquidations(50, false).armed(),
                "the arming rule itself still has to work — nothing in this slice sets it");
    }

    /** A node that has never completed a cycle reports that, rather than a fabricated zero-time run. */
    @Test
    void anEmptyLogReportsNoLastRunRatherThanAFabricatedOne() {
        LiquidationDecisionLog log = new LiquidationDecisionLog(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false));

        var view = new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log)
                .liquidations(50, false);

        assertNull(view.lastRunAt());
        assertEquals(0, view.bondsScanned());
        assertTrue(view.exclusions().isEmpty());
        assertTrue(view.decisions().isEmpty());
    }

    @Test
    void theLimitTakesFromTheNewestEnd() {
        LiquidationDecisionLog log = logWith(
                List.of(wouldSubmit("a1"), wouldSubmit("a2"), wouldSubmit("a3")), Map.of(), 3, 3);

        var view = new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log)
                .liquidations(2, false);

        assertEquals(List.of("a3", "a2"),
                view.decisions().stream().map(LiquidationController.DecisionView::loanId).toList());
    }

    // ---- the cbor flag -----------------------------------------------------------------------

    /**
     * The unsigned transaction is kilobytes of hex per decision, so it is opt-in — and <em>absent</em>
     * rather than null, so a client polling without the flag cannot mistake "not asked for" for "not
     * built". Asserted on the serialised JSON, because that is where the difference lives.
     */
    @Test
    void txCborHexIsOmittedUnlessAskedFor() throws Exception {
        LiquidationDecisionLog log = logWith(List.of(wouldSubmit("a1")), Map.of(), 1, 1);
        var controller = new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log);

        var without = controller.liquidations(50, false);
        assertNull(without.decisions().getFirst().txCborHex());

        JsonNode json = new ObjectMapper().valueToTree(without);
        JsonNode decision = json.get("decisions").get(0);
        assertFalse(decision.has("tx_cbor_hex"),
                "tx_cbor_hex must be absent, not null, when it was not asked for");
        // The neighbouring built-transaction fields are still there, so the omission is targeted
        // rather than a blanket "drop every null".
        assertEquals(19_838, decision.get("tx_size_bytes").asInt());
        assertNotNull(decision.get("tx_hash").asText());
    }

    @Test
    void txCborHexIsPresentWhenAskedFor() throws Exception {
        LiquidationDecisionLog log = logWith(List.of(wouldSubmit("a1")), Map.of(), 1, 1);
        var controller = new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log);

        var with = controller.liquidations(50, true);
        assertEquals(CBOR, with.decisions().getFirst().txCborHex());

        JsonNode decision = new ObjectMapper().valueToTree(with).get("decisions").get(0);
        assertEquals(CBOR, decision.get("tx_cbor_hex").asText());
    }

    /** The view is snake_case on the wire, like every other loans view in this package. */
    @Test
    void everyFieldIsRenderedInSnakeCase() {
        LiquidationDecisionLog log = logWith(List.of(wouldSubmit("a1")),
                Map.of(LiquidationExclusion.NOT_LIQUIDATABLE, 2), 3, 1);

        JsonNode json = new ObjectMapper().valueToTree(new LiquidationController(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW, false), log)
                .liquidations(50, false));

        assertTrue(json.has("last_run_at"));
        assertTrue(json.has("bonds_scanned"));
        assertTrue(json.has("armed"));
        assertEquals(2, json.get("exclusions").get("NOT_LIQUIDATABLE").asInt(),
                "the histogram is keyed by the exclusion's own name, not a renamed one");

        JsonNode decision = json.get("decisions").get(0);
        assertTrue(decision.has("loan_utxo"));
        assertTrue(decision.has("bond_utxo"));
        assertTrue(decision.has("expected_profit_lovelace"));
        assertTrue(decision.has("reference_inputs"));
    }
}
