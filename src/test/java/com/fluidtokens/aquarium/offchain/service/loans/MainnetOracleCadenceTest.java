package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>S-12 — is the 30-second refresh cadence actually fresh enough for the windows mainnet serves?</b>
 *
 * <h2>The claim under test, which was a comment and not a measurement</h2>
 * {@code FluidOracleClient.refresh()} carries a javadoc justifying its cadence:
 * <i>"Feeds carry a 50-minute window (10 for the Charli3-backed one) against a 60-minute protocol
 * maximum, so a 30s cadence is fresh enough and polite."</i> <b>Nothing checked it.</b> A cadence
 * justified by a window nobody re-measures is the same shape as a coordinate justified by a hash
 * nobody re-resolves — right when written, and silently wrong afterwards.
 *
 * <h2>What "fresh enough" has to mean, arithmetically</h2>
 * The builder's V3 check is {@code feed.validTo - tx.validTo >= oracle-window-margin-seconds}, and the
 * transaction claims {@code validity-window-seconds} of its own. So of a feed's window, the usable
 * stretch is
 *
 * <pre>usable = window − validity-window(120s) − margin(30s) − worst-case staleness(the 30s cadence)</pre>
 *
 * ⚠ <b>The cadence enters as a subtraction, not as a comparison.</b> The tempting check is
 * <i>"cadence &lt; window"</i>, which is true by a factor of a hundred and proves nothing: what
 * matters is that after paying all three costs there is still a stretch left in which a candidate can
 * be built. A feed whose window is 150s would satisfy "cadence &lt; window" comfortably and leave
 * <b>zero</b> usable seconds.
 *
 * <p>⚠ And this reads the LIVE registry, so it is a check on FluidTokens' current publishing
 * behaviour, not on our arithmetic. A red run here is a finding about their feeds — investigate it,
 * do not relax the bound.
 *
 * <p>Read-only and key-free — it queries {@code api.fluidtokens.com}, not Blockfrost. ⚠ It is gated on
 * {@code BLOCKFROST_KEY} anyway, because that variable is this repo's established switch for "live
 * network checks are wanted in this run"; the gate is a POSTURE, not a dependency. Run with
 * {@code set -a; . ./.env.mainnet; set +a}.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "live check against api.fluidtokens.com; gated on this repo's live-checks switch")
class MainnetOracleCadenceTest {

    /** The production defaults these figures are taken from — application.yaml, not invented here. */
    private static final long VALIDITY_WINDOW_MS = 120_000L;
    private static final long ORACLE_MARGIN_MS = 30_000L;
    private static final long REFRESH_CADENCE_MS = 30_000L;

    /** What all three together cost a candidate, before any of the window is usable. */
    private static final long OVERHEAD_MS = VALIDITY_WINDOW_MS + ORACLE_MARGIN_MS + REFRESH_CADENCE_MS;

    @Test
    void everyUsableMainnetFeedLeavesRoomToBuildAfterTheCadenceIsPaidFor() {
        var client = new FluidOracleClient("https://api.fluidtokens.com/get-oracle-tokens");
        client.refresh();
        long now = System.currentTimeMillis();

        List<OracleEntry> entries = new ArrayList<>(client.entries());
        assertTrue(!entries.isEmpty(), "the registry served nothing, so this run measured nothing");

        Map<com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed.Variant, Integer> byVariant =
                new EnumMap<>(com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed.Variant.class);
        List<String> tooNarrow = new ArrayList<>();
        List<String> report = new ArrayList<>();

        for (OracleEntry entry : entries) {
            var feed = entry.feed();
            byVariant.merge(feed.variant(), 1, Integer::sum);
            long window = feed.validTo() - feed.validFrom();
            long ageAtPublish = now - feed.validFrom();
            long remaining = feed.validTo() - now;
            boolean usable = entry.usableForLiquidation();

            report.add("  %-46s %-18s window=%6ds age=%6ds left=%6ds usable=%s".formatted(
                    entry.token().toUnit().length() > 44
                            ? entry.token().toUnit().substring(0, 44) : entry.token().toUnit(),
                    feed.variant(), window / 1000, ageAtPublish / 1000, remaining / 1000, usable));

            // ⚠ Only feeds this bot could ACTUALLY build against are held to the bound. An unmodelled
            // variant (POOLED, ORCFAX) is refused upstream by usableForLiquidation(), so its window is
            // not a liquidation risk and folding it in here would produce a failure about a feed the
            // bot never reaches.
            if (usable && window <= OVERHEAD_MS) {
                tooNarrow.add(entry.token().toUnit() + " (" + feed.variant() + ") window=" + window
                        + "ms, overhead=" + OVERHEAD_MS + "ms");
            }
        }

        System.out.println("\n==== MAINNET ORACLE CADENCE (live registry, read-only) ====");
        System.out.println("overhead a candidate must pay: validity " + VALIDITY_WINDOW_MS + "ms + margin "
                + ORACLE_MARGIN_MS + "ms + cadence " + REFRESH_CADENCE_MS + "ms = " + OVERHEAD_MS + "ms");
        System.out.println("variants: " + byVariant);
        report.forEach(System.out::println);
        System.out.println("==========================================================\n");

        assertTrue(tooNarrow.isEmpty(),
                "⛔ a feed this bot would build against publishes a window no larger than the overhead "
                        + "a candidate must pay, so there is NO instant at which it is buildable. That "
                        + "is a finding about FluidTokens' publishing, not a bound to relax: " + tooNarrow);
    }

    /**
     * ⚠ The feed the ONE live mainnet loan actually names (findings §54).
     */
    @Test
    void theFeedTheLiveMainnetLoanNamesIsServedAndPriceable() {
        var client = new FluidOracleClient("https://api.fluidtokens.com/get-oracle-tokens");
        client.refresh();

        AssetType fldt = new AssetType(
                "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
        var entry = client.findEntry(fldt);

        assertTrue(entry.isPresent(),
                "the collateral of the only live mainnet loan has no feed in the registry, so its "
                        + "health is unknowable and no liquidation of it can be built at all");

        var feed = entry.get().feed();
        long window = feed.validTo() - feed.validFrom();
        System.out.println("\nFLDT feed: variant=" + feed.variant() + " window=" + window / 1000 + "s"
                + " usable=" + entry.get().usableForLiquidation()
                + " signatures=" + entry.get().signatures().size() + "/" + entry.get().threshold() + "\n");

        assertTrue(window > OVERHEAD_MS,
                "the mainnet candidate's own feed publishes a window of " + window + "ms against an "
                        + "overhead of " + OVERHEAD_MS + "ms — there would be no instant in which that "
                        + "loan is liquidatable");
    }

    /**
     * ⛔ <b>{@code usableForLiquidation()} is NOT a freshness check, and the live registry proves the
     * distinction matters.</b>
     *
     * <p>Measured 2026-09-03: one AGGREGATED entry in the mainnet registry is <b>36 days past its
     * {@code validTo}</b> and still reports {@code usableForLiquidation() == true}, because that method
     * asks about signatures and reference inputs — <i>can this feed satisfy the validator's shape</i> —
     * and says nothing about <i>when</i>. Freshness is a separate question answered by
     * {@link com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed#usableAt}, which
     * {@code LiquidationCandidateScanner} calls in addition.
     *
     * <p>⚠ <b>The two together are the gate; either alone is a half-truth.</b> This is pinned OFFLINE
     * rather than against the live entry on purpose: asserting on the stale entry would go red the day
     * FluidTokens tidy their registry — a red test for a good change — while the property itself is
     * about our code and does not depend on theirs.
     */
    @Test
    void aSignatureCompleteFeedCanStillBeLongExpired() {
        long now = System.currentTimeMillis();
        // ⚠ NOT ada. AssetType.ada() yields the SYNTHESISED UNIT FEED, which usableOver() short-
        // circuits to true regardless of dates — correctly, because it is not a published feed at all.
        // Reaching for ada here made the probe pass for a reason that had nothing to do with the
        // property, which is the failure mode a probe is most prone to.
        var expired = com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed.aggregated(
                new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e",
                        "0014df10464c4454"),
                java.math.BigInteger.ONE, java.math.BigInteger.ONE,
                // ⚠ A REALISTIC 50-minute window placed 36 days ago, not a 4-day one. usableOver()
                // enforces TWO independent rules — containment AND validTo-validFrom <= 60 minutes —
                // and a wide fabricated window fails the second, which would have proved the max-window
                // rule while claiming to prove staleness.
                now - 36 * 86_400_000L, now - 36 * 86_400_000L + 3_000_000L);

        assertTrue(!expired.usableOver(now, now + VALIDITY_WINDOW_MS),
                "a feed 36 days dead must not be usable at now — this is the check that actually "
                        + "carries freshness, and the one a caller reading usableForLiquidation() "
                        + "alone would skip");
        assertTrue(expired.usableOver(expired.validFrom() + 1, expired.validFrom() + 2),
                "and it is not 'broken' — it was perfectly usable inside its own window, which is "
                        + "exactly why signature-completeness cannot stand in for freshness");
    }
}
