package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LendingConfigGate;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-116: a closed Lending v4 gate is VISIBLE on {@code /healthcheck}, and does NOT fail it. Before,
 * {@code /healthcheck} had no lending field, so an operator could re-pin, see a clean check, re-arm to
 * live and have every liquidation refused. The scheduled-payment half the check guards keeps running
 * while the gate is closed, so the verdict (HTTP status) must not move with it.
 */
class HealthcheckLendingGateTest {

    private static Healthcheck healthcheck(LendingConfigGate gate) {
        BlockEventListener listener = mock(BlockEventListener.class);
        when(listener.getIsSyncing()).thenReturn(new AtomicBoolean(false));
        AppUtxoService utxos = mock(AppUtxoService.class);
        when(utxos.listWalletUtxo()).thenReturn(List.of());
        StakerService staker = mock(StakerService.class);
        when(staker.findStakerRefInput()).thenReturn(List.of());
        return new Healthcheck(mock(ParametersService.class), staker, listener, utxos, gate);
    }

    @Test
    void aClosedGateIsReportedWithItsReasonAndDoesNotFailTheCheck() {
        LendingConfigGate gate = new LendingConfigGate();
        gate.block("Lending v4 config mismatch -- ConfigDatum[11]");

        ResponseEntity<?> response = healthcheck(gate).healthCheck();

        assertEquals(200, response.getStatusCode().value(), "a closed lending gate must not fail the scheduled half");
        Healthcheck.HealthCheck body = (Healthcheck.HealthCheck) response.getBody();
        assertEquals("closed", body.lendingGate());
        assertTrue(body.lendingGateReason().contains("ConfigDatum[11]"), body.lendingGateReason());
    }

    @Test
    void anOpenGateIsReportedOpenWithNoReason() {
        Healthcheck.HealthCheck body = (Healthcheck.HealthCheck) healthcheck(new LendingConfigGate())
                .healthCheck().getBody();
        assertEquals("open", body.lendingGate());
        assertNull(body.lendingGateReason());
    }

    /**
     * FAB-136: there is no startup wallet sweep any more, so {@code /healthcheck} carries no
     * {@code wallet_sweep} field. Pinned by reflection as well as on the JSON, because a component that
     * is always null would vanish from the JSON of a mapper that omits nulls and survive here.
     */
    @Test
    void theRecordCarriesExactlyTheSevenReportedComponents() {
        List<String> names = java.util.Arrays.stream(Healthcheck.HealthCheck.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertEquals(List.of("dbOk", "parametersOk", "parametersRefInputOk", "walletOk", "stakingOk",
                "lendingGate", "lendingGateReason"), names);
    }

    /** FAB-136: the JSON an operator's curl sees has no {@code wallet_sweep} key. */
    @Test
    void theJsonHasNoWalletSweepKey() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(healthcheck(new LendingConfigGate()).healthCheck().getBody());
        assertTrue(json.contains("\"lending_gate\":\"open\""), "positive control, the body was serialised: " + json);
        assertFalse(json.contains("wallet_sweep"), json);
    }

    /** The JSON an operator's curl sees: snake_case, like every other field. */
    @Test
    void theFieldsSerialiseInSnakeCase() throws Exception {
        LendingConfigGate gate = new LendingConfigGate();
        gate.block("why");
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(healthcheck(gate).healthCheck().getBody());
        assertTrue(json.contains("\"lending_gate\":\"closed\"") && json.contains("\"lending_gate_reason\":\"why\""), json);
    }
}
