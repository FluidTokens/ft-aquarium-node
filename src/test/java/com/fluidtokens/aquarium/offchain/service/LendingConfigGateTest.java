package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ <b>A Lending v4 config change must never stop the node (FAB-115).</b>
 *
 * <p>2026-10-01 08:09Z: mainnet bounced and would not start. FluidTokens had republished the
 * ConfigDatum on 2026-09-30 00:50Z, re-pointing the claim action ({@code [11]}) and three other fields
 * at one unpublished script, and {@code LoansConfigVerifier} threw out of {@code @PostConstruct} — so
 * the scheduled-payment processor, which uses nothing from Lending v4, went down with it. The second
 * time: 2026-09-19 did the same over pool-side fields.
 *
 * <p>This drives the REAL startup path, {@link LoansConfigVerifier#verify()}, against the REAL datum
 * that grounded the node, served through a stubbed Blockfrost exactly where production reads it.
 */
class LendingConfigGateTest {

    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static String fixture(String name) throws IOException {
        try (InputStream in = LendingConfigGateTest.class.getResourceAsStream("/loans-v4/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static Utxo configUtxo(String policy, String datumHex) {
        return Utxo.builder().txHash("00".repeat(32)).outputIndex(0)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)),
                        Amount.asset(policy + ASSET, BigInteger.ONE)))
                .inlineDatum(datumHex).build();
    }

    private static String enterpriseAddress(String policy) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(policy)),
                Networks.mainnet()).getAddress();
    }

    /** Blockfrost answering both config lookups with the given datums, as the live chain did. */
    /**
     * Judged by the artefact the node shipped BEFORE FluidTokens' 2026-10-01 redeploy -- for the
     * incidents that happened against it (2026-09-19, 2026-09-30).
     */
    private static LoansConfigVerifier verifierServingBefore20261001(String configDatum, String lmConfigDatum)
            throws Exception {
        return verifierServing(configDatum, lmConfigDatum, new LoansContractRegistry(
                "loans-v4-2026-09-17.plutus.json", CONFIG, LM_CONFIG, ASSET, SMART, null, null, null));
    }

    private static LoansConfigVerifier verifierServing(String configDatum, String lmConfigDatum) throws Exception {
        return verifierServing(configDatum, lmConfigDatum,
                new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, null, null, null));
    }

    private static LoansConfigVerifier verifierServing(String configDatum, String lmConfigDatum,
                                                        LoansContractRegistry registry) throws Exception {
        UtxoService utxos = mock(UtxoService.class);
        when(utxos.getUtxos(anyString(), anyInt(), anyInt())).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of()));
        when(utxos.getUtxos(enterpriseAddress(CONFIG), 100, 1)).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of(configUtxo(CONFIG, configDatum))));
        when(utxos.getUtxos(enterpriseAddress(LM_CONFIG), 100, 1)).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of(configUtxo(LM_CONFIG, lmConfigDatum))));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(utxos);

        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return new LoansConfigVerifier(registry, SMART, network, bf, false);
    }

    @Test
    void theDatumThatGroundedMainnetNoLongerStopsTheNodeButClosesTheLendingGate() throws Exception {
        LoansConfigVerifier verifier = verifierServingBefore20261001(
                fixture("mainnet-config-datum-2026-09-30.hex"), fixture("mainnet-lm-config-datum.hex"));

        assertDoesNotThrow(verifier::verify,
                "a Lending v4 config mismatch must not throw out of @PostConstruct — that takes the "
                        + "scheduled-payment processor down with it");

        assertTrue(verifier.gate().isBlocked(), "but every Lending v4 transaction must be refused");
        String reason = verifier.gate().blockedReason().orElseThrow();
        assertTrue(reason.contains("ConfigDatum[11]"), "the reason must name the claim action: " + reason);
        assertTrue(reason.contains("PAUSED") && reason.contains("64d9b13f973be664a05c22365f90b222b0f9018b94918d3cb5d0220f"),
                "four actions re-pointed at one script reads as a pause, not a redeploy: " + reason);
    }

    @Test
    void aCleanDatumLeavesTheGateOpen() throws Exception {
        // The live datums after FluidTokens' 2026-10-01 redeploy: only the advisory recast pause differs.
        LoansConfigVerifier verifier = verifierServing(
                fixture("mainnet-config-datum-2026-10-01.hex"), fixture("mainnet-lm-config-datum-2026-10-01.hex"));

        verifier.verify();

        assertFalse(verifier.gate().isBlocked(), "the gate must only close on a real mismatch: "
                + verifier.gate().blockedReason());
    }

    @Test
    void theSeptember19DatumStillOnlyWarnsAndLeavesTheGateOpen() throws Exception {
        // 2026-09-19: three pool-side fields this node never invokes. ADVISORY — unchanged by FAB-115.
        LoansConfigVerifier verifier = verifierServingBefore20261001(
                fixture("mainnet-config-datum-2026-09-19.hex"), fixture("mainnet-lm-config-datum.hex"));

        verifier.verify();

        assertFalse(verifier.gate().isBlocked(), "advisory-only mismatches must not close the gate");
    }

    @Test
    void aMissingConfigNftClosesTheGateInsteadOfThrowing() throws Exception {
        // The config NFT is not at its address any more: the configured policy id is stale.
        UtxoService empty = mock(UtxoService.class);
        when(empty.getUtxos(anyString(), anyInt(), anyInt())).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of()));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(empty);
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        LoansConfigVerifier verifier = new LoansConfigVerifier(
                new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, null, null, null),
                SMART, network, bf, false);

        assertDoesNotThrow(verifier::verify);
        assertTrue(verifier.gate().blockedReason().orElse("").contains("not found"),
                "a vanished config NFT is a lending fault, reported as one: " + verifier.gate().blockedReason());
    }

    /**
     * ⛔ Round-2 finding 6: any fault, not only IllegalStateException. A provider answering success with
     * no value list NPEs in the lookup; that used to escape @PostConstruct and ground the node.
     */
    @Test
    void anUnexpectedFaultClosesTheGateInsteadOfEscapingStartup() throws Exception {
        UtxoService broken = mock(UtxoService.class);
        when(broken.getUtxos(anyString(), anyInt(), anyInt())).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(null));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(broken);
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        LoansConfigVerifier verifier = new LoansConfigVerifier(
                new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, null, null, null),
                SMART, network, bf, false);

        assertDoesNotThrow(verifier::verify, "a lending fault must not stop the node");
        assertTrue(verifier.gate().isBlocked(), "it must close the lending gate instead");
    }

    private static LoansConfigVerifier verifierBehindA5xx(boolean failOnUnreachable) throws Exception {
        UtxoService down = mock(UtxoService.class);
        when(down.getUtxos(anyString(), anyInt(), anyInt())).thenReturn(
                Result.<List<Utxo>>error("upstream 503").code(503));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(down);
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return new LoansConfigVerifier(new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, null, null, null),
                SMART, network, bf, failOnUnreachable);
    }

    /** V10 (documented residue, now pinned): an UNREACHABLE backend warns and leaves the gate OPEN. */
    @Test
    void anUnreachableBackendLeavesTheGateOpenAndDoesNotStopTheNode() throws Exception {
        LoansConfigVerifier verifier = verifierBehindA5xx(false);

        assertDoesNotThrow(verifier::verify);
        assertFalse(verifier.gate().isBlocked(), "an outage is not a mismatch: the gate stays open, unverified");
    }

    /** V7: the operator's explicit opt-in still turns an unreachable backend into a startup failure. */
    @Test
    void failOnUnreachableStillStopsTheNodeWhenTheOperatorAskedForIt() throws Exception {
        LoansConfigVerifier verifier = verifierBehindA5xx(true);

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, verifier::verify,
                "loans.verify-config.fail-on-unreachable=true must keep its throw");
        assertTrue(thrown.getMessage().contains("Cannot verify Lending v4 config"), thrown.getMessage());
    }

    @Test
    void severalMismatchesOnOneHashReadAsAPauseAndDistinctOnesAsARedeploy() {
        assertTrue(LoansConfigVerifier.pausedReading(List.of(
                        "ConfigDatum[11]: derived aa, chain " + "ab".repeat(28),
                        "ConfigDatum[13]: derived bb, chain " + "ab".repeat(28)))
                .contains("PAUSED these actions: ConfigDatum[11], ConfigDatum[13]"));
        assertTrue(LoansConfigVerifier.pausedReading(List.of(
                        "ConfigDatum[11]: derived aa, chain " + "ab".repeat(28),
                        "ConfigDatum[13]: derived bb, chain " + "cd".repeat(28)))
                .contains("redeployed"));
    }
}
