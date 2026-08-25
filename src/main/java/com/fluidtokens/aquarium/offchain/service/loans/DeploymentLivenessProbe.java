package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Answers the one question {@code LoansConfigVerifier} structurally cannot: <b>is the deployment we
 * are pinned to still the one the world is using?</b>
 *
 * <h2>The failure this exists to prevent</h2>
 * A node pinned to a superseded deployment boots clean, verifies clean, and reports zero candidates
 * <em>forever</em>. {@code TankUtxoStorage} keeps only UTxOs at
 * {@link LoansContractRegistry#indexedPaymentCredentials()}, which are derived from the pinned config
 * NFT policy ids, so a redeploy's UTxOs are indexed off the chain and then discarded. From the
 * outside that is <b>indistinguishable from a quiet market</b>. Preview has now been redeployed four
 * times under this project and the trap has caught us three of them.
 *
 * <h2>Why the existing verifier cannot catch it — and why that is structural, not a bug</h2>
 * {@code LoansConfigVerifier} derives script hashes from the pinned blueprint and compares them to
 * the config datum <em>it fetches using the pinned policy id</em>. Both halves come from the pin, so
 * the comparison is self-consistent by construction and stays green forever. It would only fail if
 * the pinned config UTxO changed or vanished — and <b>measured on preview 2026-08-25, it does
 * not</b>: the superseded THIRD deployment's config NFTs are still {@code quantity=1},
 * {@code mint_or_burn_count=1}, still sitting at their addresses, months after being superseded. A
 * redeploy mints new NFTs under a new policy id and simply abandons the old ones.
 * <p>
 * The same shape defeated a manual investigation on 2026-08-24: a 2x2 "which upstream commit is
 * deployed" test was run against a hash table derived from the blueprint under test, so it could
 * only ever confirm that blueprint. It named the wrong commit, and the wrong answer stood in the
 * durable record for a day until it was checked against the chain instead. <b>An apparatus derived
 * from the thing under test cannot test it.</b>
 *
 * <h2>What this probe asks instead</h2>
 * Nothing derived from the blueprint, and nothing self-referential: it asks whether the pinned
 * <em>world</em> is still receiving activity. The chain wrote every {@code slot} on every UTxO we
 * indexed; this reads the most recent of them across all indexed credentials and compares it to the
 * current wall-clock slot.
 * <p>
 * That distinguishes the two states the operator cannot otherwise tell apart:
 * <ul>
 *   <li><b>Quiet market</b> — the gap is bounded. New loans, requests and pool operations still land
 *       at our credentials, so the most recent activity keeps moving forward.</li>
 *   <li><b>Dead pin</b> — the gap grows monotonically and never resets, because every new loan is
 *       being created under a policy id we do not index. Left long enough the indexed set empties
 *       entirely as the last live loans are settled, which is the strongest form of the signal and
 *       is reported as its own case below.</li>
 * </ul>
 *
 * <h2>Why it is scheduled and not a boot check</h2>
 * A fresh node has an empty database and no indexed activity at all, so a boot-time assertion would
 * refuse to start exactly the node that is about to work. The probe therefore runs on a schedule and
 * stays silent at {@code INFO} until the node has both finished syncing and gone quiet for longer
 * than a real market would. It escalates to {@code ERROR} with an actionable message rather than
 * throwing: killing a liquidation bot on a heuristic is worse than the state it is warning about,
 * and the alarm is only ever evidence for an operator to act on.
 *
 * <h2>Reading it honestly</h2>
 * This is a <b>heuristic with a stated false-positive mode</b>: a genuinely dormant market on a
 * genuinely live deployment eventually trips it. That is the deliberate direction — a false alarm
 * costs one check of the config policy id against chain, whereas a false silence has now cost this
 * project three separate investigations. The gap is logged on <em>every</em> run regardless of the
 * threshold, so the evidence to judge a suspicion is already in the log when someone comes looking,
 * and a badly chosen threshold degrades the alarm without destroying the record.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class DeploymentLivenessProbe {

    private final UtxoRepository utxoRepository;
    private final LoansContractRegistry registry;
    private final BlockEventListener blockEventListener;
    private final CardanoConverters converters;

    /**
     * How long the pinned world may be silent before the alarm, in slots (1 slot = 1 second on both
     * preview and mainnet in every era this node supports).
     * <p>
     * The default is deliberately generous — 48 hours. A dead pin is <b>permanent</b>, so its gap
     * grows without bound and will cross any threshold eventually; the only thing a larger threshold
     * costs is time-to-detection, while a smaller one buys false alarms during ordinary lulls.
     */
    private final long maxQuietSlots;

    public DeploymentLivenessProbe(UtxoRepository utxoRepository,
                                   LoansContractRegistry registry,
                                   BlockEventListener blockEventListener,
                                   CardanoConverters converters,
                                   @Value("${loans.deployment-liveness.max-quiet-slots:172800}")
                                   long maxQuietSlots) {
        this.utxoRepository = utxoRepository;
        this.registry = registry;
        this.blockEventListener = blockEventListener;
        this.converters = converters;
        this.maxQuietSlots = maxQuietSlots;
    }

    /**
     * One reading. {@code lastActivitySlot} is null when the indexed world is empty, which is the
     * strongest form of the signal rather than a missing measurement — see {@link #describe}.
     */
    public record Status(boolean syncing,
                         long currentSlot,
                         Long lastActivitySlot,
                         Long quietSlots,
                         int indexedUtxos,
                         boolean suspectedRedeploy,
                         String detail) {
    }

    @Scheduled(timeUnit = TimeUnit.SECONDS,
            fixedDelayString = "${loans.deployment-liveness.delay-seconds:900}",
            initialDelayString = "${loans.deployment-liveness.delay-seconds:900}")
    public void check() {
        Status status;
        try {
            status = status();
        } catch (Exception e) {
            // A probe that takes the scheduler thread down with it is worse than no probe.
            log.warn("deployment liveness probe failed", e);
            return;
        }

        if (status.suspectedRedeploy()) {
            log.error("SUSPECTED REDEPLOY — {}. The bot will report zero candidates indefinitely in "
                            + "this state and it is indistinguishable from a quiet market from the "
                            + "outside. Check whether loans.config.policy-id ({}) is still the "
                            + "deployment in use: decode a recent loan transaction and read which "
                            + "config policy it references. If it has moved, repin config.policy-id, "
                            + "lm-config.policy-id and config.ref-utxo-tx-hash together — they are a "
                            + "set — and expect every published reference-script coordinate to be "
                            + "dead, because the derived hashes move with the policy ids.",
                    status.detail(), registry.getConfigPolicyId());
        } else {
            log.info("deployment liveness: {}", status.detail());
        }
    }

    /** The reading now. Used by the scheduler and by {@code GET /loans/deployment}. */
    public Status status() {
        return status(converters.time().toSlot(LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * The reading at an explicit slot, so it is testable without a clock.
     * <p>
     * Package-private on purpose: the scheduler comes in through {@link #check()}, and the only
     * other caller should be a test.
     */
    Status status(long currentSlot) {
        List<String> credentials = registry.indexedPaymentCredentials();

        // Unpaged is safe here specifically because TankUtxoStorage already filters what is stored
        // down to these credentials, so this is a small set by construction rather than by luck.
        // If that filter is ever widened, this query must be revisited with it.
        List<AddressUtxoEntity> utxos = credentials.stream()
                .map(credential -> utxoRepository
                        .findUnspentByOwnerPaymentCredential(credential, Pageable.unpaged())
                        .stream()
                        .flatMap(Collection::stream)
                        .toList())
                .flatMap(List::stream)
                .toList();

        Optional<Long> newest = utxos.stream()
                .map(AddressUtxoEntity::getSlot)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo);

        boolean syncing = blockEventListener.getIsSyncing().get();
        Long lastActivitySlot = newest.orElse(null);
        Long quietSlots = newest.map(slot -> currentSlot - slot).orElse(null);

        boolean stale = !syncing && (lastActivitySlot == null || quietSlots > maxQuietSlots);

        return new Status(syncing, currentSlot, lastActivitySlot, quietSlots, utxos.size(), stale,
                describe(syncing, currentSlot, lastActivitySlot, quietSlots, utxos.size(), credentials.size()));
    }

    private String describe(boolean syncing, long currentSlot, Long lastActivitySlot, Long quietSlots,
                            int indexedUtxos, int credentials) {
        if (syncing) {
            return "node is still syncing, so silence proves nothing yet (slot %d, %d utxos indexed "
                    .formatted(currentSlot, indexedUtxos)
                    + "across %d pinned credentials)".formatted(credentials);
        }
        if (lastActivitySlot == null) {
            // This IS the signal, in its strongest form — but it is a DISJUNCTION, and the honest
            // thing is to enumerate it rather than name the likeliest cause and let the reader take
            // it as a diagnosis. This probe counts UNSPENT rows in the LOCAL INDEX, and there are
            // four distinct worlds that produce a count of zero. It cannot tell them apart; saying
            // so is what makes it useful, because each has a different fix and three of the four
            // have bitten this project.
            return ("the node is at tip and there is NOT ONE unspent utxo at any of the %d pinned "
                    + "credentials. This reading CANNOT distinguish four causes: (1) the pinned "
                    + "deployment is superseded and the world moved to new policy ids; (2) it has "
                    + "never been used; (3) it was used and every utxo has since been SPENT -- a "
                    + "count of unspent rows says nothing about a fully settled deployment; or "
                    + "(4) the relevant blocks were indexed by a build whose TankUtxoStorage filter "
                    + "did not include these credentials, so the rows were discarded at index time "
                    + "and never written. (4) leaves no trace of any kind and is not fixed by "
                    + "restarting: the cursor is already past those blocks, so it needs a re-index "
                    + "from before them. Check the chain directly before concluding anything from "
                    + "this line.").formatted(credentials);
        }
        return ("last activity at the %d pinned credentials was slot %d, %d slots (~%dh) before the "
                + "current slot %d; %d unspent utxos indexed")
                .formatted(credentials, lastActivitySlot, quietSlots, quietSlots / 3600, currentSlot,
                        indexedUtxos);
    }
}
