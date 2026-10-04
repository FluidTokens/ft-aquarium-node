package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-139: the MAINNET sync start is a PAIR — a slot and the hash of the block at that slot — and it sits
 * at or before Aquarium genesis.
 *
 * <h2>Why a pin</h2>
 * The sync start is a LOWER BOUND: indexing runs forward from it, so every object the node must read from
 * its index has to be created at or after it ({@code officina:yaci-store-index-scoping} §0/§3). A start
 * moved later makes the node blind to everything before it — the Aquarium parameters and staker UTxOs, a
 * wallet's older outputs — and nothing fails: the index answers "empty", which reads as "nothing there".
 * The slot and the hash move ONLY TOGETHER: Yaci Store starts from the block the pair names, and a slot
 * with another block's hash names no block at all.
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li>{@value #SYNC_START_SLOT} is 2025-05-06T17:00:52Z by Shelley arithmetic
 *       ({@code slot − 4,492,800 + 1,596,059,091}): the "2025-05-06" every operator message cites as the
 *       wallet precondition (no wallet UTxO before it).</li>
 *   <li>{@value #AQUARIUM_GENESIS_SLOT} is the slot of the Aquarium genesis transaction <b>per FAB-139
 *       ruling</b> — NOT observed by this ticket's owner. ⚠ The ruling names that transaction
 *       {@code d35f81f6bc88babe5dcf088e3a800ecbb4d75373df6b144f7561d393cb5d9b2f}, which is the PREVIEW
 *       document's {@code aquarium.genesis.tx-hash}; the mainnet base document's is
 *       {@code 45f379b3436263146ab3a5423506ce11555113384d45655b50c77dab8a3473ff}. Which transaction the slot
 *       belongs to is for a read-only chain query to settle, not this test.</li>
 * </ul>
 * Nothing here says anything about the preview document (not ruled).
 */
class SyncStartPinTest {

    private static final long SYNC_START_SLOT = 154_984_561L;

    private static final String SYNC_START_BLOCKHASH =
            "586ead1770fc2a59021b824bc0d65bf1d6060585384f257971204d5925f054c2";

    /** Per FAB-139 ruling (see the class javadoc): unverified by the owner. */
    private static final long AQUARIUM_GENESIS_SLOT = 154_984_582L;

    private static StandardEnvironment baseDocument() throws Exception {
        List<PropertySource<?>> docs = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        var env = new StandardEnvironment();
        // Hermetic: the developer's own environment must not decide this test.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(docs.get(0));
        return env;
    }

    @Test
    void theMainnetSyncStartIsThePinnedSlotAndHashPair() throws Exception {
        var env = baseDocument();
        Long slot = env.getProperty("store.cardano.sync-start-slot", Long.class);
        String hash = env.getProperty("store.cardano.sync-start-blockhash");

        String pair = "sync-start-slot=" + slot + ", sync-start-blockhash=" + hash;
        String pinned = "sync-start-slot=" + SYNC_START_SLOT + ", sync-start-blockhash=" + SYNC_START_BLOCKHASH;
        assertEquals(pinned, pair, "the mainnet sync start changed: it is a PAIR — the slot and the hash of the "
                + "block at that slot — and the two move only together, re-pinned here with both values and a "
                + "re-check that the slot is still at or before Aquarium genesis");
    }

    @Test
    void theMainnetSyncStartIsAtOrBeforeAquariumGenesis() throws Exception {
        Long slot = baseDocument().getProperty("store.cardano.sync-start-slot", Long.class);

        assertTrue(slot != null && slot <= AQUARIUM_GENESIS_SLOT,
                "the mainnet sync-start-slot " + slot + " is after Aquarium genesis (slot " + AQUARIUM_GENESIS_SLOT
                        + ", per FAB-139 ruling): every object created before it is invisible to the index, and "
                        + "an empty index answer reads as \"nothing there\"");
    }
}
