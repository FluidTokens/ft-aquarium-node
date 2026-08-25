package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe exists to separate two states an operator cannot otherwise tell apart — a dead pin and a
 * quiet market — so every test here is written to fail if that separation collapses in either
 * direction. A probe that never fires is useless; one that fires on any lull is noise that gets
 * muted, which is the same thing one step later.
 * <p>
 * {@link UtxoRepository} is stubbed with the same {@link Proxy} idiom as
 * {@code LiquidationUtxoResolverTest}: it serves the single method the probe calls and throws on
 * everything else, so a change of lookup strategy shows up as a failure rather than as a silently
 * empty result that would read as "no activity" — the exact false alarm this class must not produce
 * by accident.
 */
class DeploymentLivenessProbeTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** 48h, the shipped default. */
    private static final long MAX_QUIET_SLOTS = 172_800L;

    private static final long NOW_SLOT = 120_000_000L;

    // ---- stubs ---------------------------------------------------------------------------------

    private static UtxoRepository index(Map<String, List<AddressUtxoEntity>> unspentByCredential) {
        return (UtxoRepository) Proxy.newProxyInstance(
                DeploymentLivenessProbeTest.class.getClassLoader(),
                new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findUnspentByOwnerPaymentCredential" ->
                            Optional.of(unspentByCredential.getOrDefault((String) args[0], List.of()));
                    case "toString" -> "stub UtxoRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the probe called " + method.getName() + "; this stub only serves "
                                    + "findUnspentByOwnerPaymentCredential, so the probe is no longer "
                                    + "reading the local index the way this test assumes");
                });
    }

    private static AddressUtxoEntity rowAtSlot(long slot) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash("aa".repeat(32));
        entity.setOutputIndex(0);
        entity.setSlot(slot);
        return entity;
    }

    private static BlockEventListener listener(boolean syncing) {
        BlockEventListener listener = new BlockEventListener(null);
        listener.getIsSyncing().set(syncing);
        return listener;
    }

    private static DeploymentLivenessProbe probe(UtxoRepository repository, boolean syncing) {
        return new DeploymentLivenessProbe(repository, REGISTRY, listener(syncing), null, MAX_QUIET_SLOTS);
    }

    private static String loanCredential() {
        return REGISTRY.getLoanSpendScriptHash();
    }

    // ---- the two states it exists to separate --------------------------------------------------

    @Test
    void aQuietMarketIsNotAnAlarm() {
        // One hour of silence: quiet, but nothing like a redeploy.
        var repository = index(Map.of(loanCredential(), List.of(rowAtSlot(NOW_SLOT - 3_600))));

        var status = probe(repository, false).status(NOW_SLOT);

        assertFalse(status.suspectedRedeploy(),
                "an hour of quiet must not be reported as a redeploy: " + status.detail());
        assertEquals(3_600L, status.quietSlots());
        assertEquals(NOW_SLOT - 3_600, status.lastActivitySlot());
    }

    @Test
    void aWorldFrozenLongerThanTheThresholdIsAnAlarm() {
        // Three days. A live deployment does not go this quiet; a superseded one goes quiet forever.
        var repository = index(Map.of(loanCredential(), List.of(rowAtSlot(NOW_SLOT - 259_200))));

        var status = probe(repository, false).status(NOW_SLOT);

        assertTrue(status.suspectedRedeploy(), "three days of silence at tip must fire");
        assertEquals(259_200L, status.quietSlots());
        assertTrue(status.detail().contains("72h"),
                "the detail must state how long it has been quiet so a human can judge it, was: "
                        + status.detail());
    }

    /**
     * The end state of a dead pin: the last loans that existed under the old deployment get settled,
     * nothing new is ever created there, and the indexed world empties. Reported as its own case
     * because "no rows" is the strongest form of the signal, not a failed measurement.
     */
    @Test
    void anEmptyIndexedWorldAtTipIsTheStrongestSignal() {
        var status = probe(index(Map.of()), false).status(NOW_SLOT);

        assertTrue(status.suspectedRedeploy());
        assertNull(status.lastActivitySlot(), "there is no activity to report a slot for");
        assertNull(status.quietSlots());
        assertEquals(0, status.indexedUtxos());
        assertTrue(status.detail().contains("NOT ONE unspent utxo"),
                "an empty world must not be described as ordinary quiet, was: " + status.detail());

        // The reading is a DISJUNCTION and the detail must keep saying so. A zero count has four
        // causes and this probe cannot tell them apart; the failure mode being guarded here is a
        // well-meaning edit that trims the list to the likeliest one, which turns an honest
        // "cannot distinguish" into a confident wrong diagnosis. All four have to stay named.
        assertTrue(status.detail().contains("CANNOT distinguish"),
                "the detail must admit it cannot discriminate, was: " + status.detail());
        for (String cause : List.of("superseded", "never been used", "SPENT", "index time")) {
            assertTrue(status.detail().contains(cause),
                    "the '" + cause + "' cause must stay enumerated; a zero count does not choose "
                            + "between them. Detail was: " + status.detail());
        }
    }

    /**
     * The one state in which silence is meaningless. A node part-way through its initial sync has
     * indexed almost nothing yet, so firing here would make the probe scream on every cold start —
     * the fastest way to get an alarm ignored.
     */
    @Test
    void whileSyncingSilenceProvesNothing() {
        var status = probe(index(Map.of()), true).status(NOW_SLOT);

        assertTrue(status.syncing());
        assertFalse(status.suspectedRedeploy(),
                "a syncing node has not yet seen the world, so it cannot conclude the world is gone");
        assertTrue(status.detail().contains("still syncing"), status.detail());
    }

    // ---- the query itself ----------------------------------------------------------------------

    /**
     * Activity at <em>any</em> pinned credential counts, not only the loan one.
     * <p>
     * This matters because the credentials fall silent at different rates: loans are created in
     * bursts, while requests, pool operations and lender-manager activity carry on between them.
     * A probe that watched only {@code loanSpend} would report a redeploy during any gap in
     * borrowing, and the fix for that false alarm would be to raise the threshold until the probe
     * could no longer detect anything.
     */
    @Test
    void activityAtANonLoanCredentialAlsoCountsAsLiveness() {
        String other = REGISTRY.indexedPaymentCredentials().stream()
                .filter(credential -> !credential.equals(loanCredential()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the registry exposes only one indexed credential, so this test no longer "
                                + "proves the probe looks beyond the loan credential"));

        var repository = index(Map.of(other, List.of(rowAtSlot(NOW_SLOT - 60))));

        var status = probe(repository, false).status(NOW_SLOT);

        assertFalse(status.suspectedRedeploy(),
                "recent activity at " + other + " is proof the deployment is alive");
        assertEquals(1, status.indexedUtxos());
    }

    /**
     * The newest row wins across credentials. Without this the probe could be fooled into an alarm
     * by one long-settled credential while another was busy a minute ago.
     */
    @Test
    void theNewestActivityAcrossAllCredentialsIsTheOneReported() {
        String other = REGISTRY.indexedPaymentCredentials().stream()
                .filter(credential -> !credential.equals(loanCredential()))
                .findFirst()
                .orElseThrow();

        var repository = index(Map.of(
                loanCredential(), List.of(rowAtSlot(NOW_SLOT - 900_000)),
                other, List.of(rowAtSlot(NOW_SLOT - 120))));

        var status = probe(repository, false).status(NOW_SLOT);

        assertEquals(120L, status.quietSlots(), "the newest row must win, not the first one found");
        assertFalse(status.suspectedRedeploy());
        assertEquals(2, status.indexedUtxos());
    }
}
