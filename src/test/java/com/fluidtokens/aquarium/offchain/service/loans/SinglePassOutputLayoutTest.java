package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.fluidtokens.aquarium.offchain.service.loans.OutputLayout.CCL_PREPENDED_OUTPUTS;
import static com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder.assetOutputIndexesForEquities;
import static com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder.bondOutputIndexes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * T-051 — the output indexes are <b>computed</b> from the emission order rather than observed off a
 * throwaway probe body, so the transaction is built once.
 *
 * <h2>What this test can and cannot reach</h2>
 * It pins the two pieces of positional arithmetic and the one library constant they sit on. It does
 * <b>NOT</b> prove that a real built body agrees with them: the only rigs that drive
 * {@code LiquidateTransactionBuilder.build()} end to end are built on {@code LoanFixtures} and are
 * among the 37 red against a superseded deployment (docs/tests-pinned-to-chain-state.md). <b>That
 * gap is real and is stated rather than papered over.</b>
 *
 * <p>What stands in for it at runtime is V5, which re-derives both index families from the FINISHED
 * body inside the build pipeline and refuses on any disagreement — so a layout mistake is a
 * build-time refusal, loud and free, never a chain failure. The probe was never the guarantee; V5
 * was, and it is untouched.
 */
class SinglePassOutputLayoutTest {

    private static List<BigInteger> equities(long... values) {
        return java.util.Arrays.stream(values).boxed().map(BigInteger::valueOf).toList();
    }

    /**
     * The K=0 case: with no positive equity anywhere, every loan's first-row output IS its collateral
     * output, so the indexes are the identity — which is what made rule R a no-op change for the
     * batches that preceded it.
     */
    @Test
    void noEquityAnywhereGivesTheIdentity() {
        assertEquals(List.of(0L, 1L, 2L, 3L), assetOutputIndexesForEquities(equities(0, 0, 0, 0)));
    }

    /**
     * ⚠ The case the identity would get WRONG. loan_claim_action reads the borrower's compensation
     * output at the bare loan index with no redeemer indirection, so a loan with equity keeps slot i
     * for its compensation and its collateral is displaced into the second row — at L + its rank
     * among the loans that have one.
     */
    @Test
    void positiveEquityDisplacesThatLoansCollateralIntoTheSecondRow() {
        // L = 4. Loans 1 and 3 have equity, so they take second-row slots 4 and 5 in loan order;
        // loans 0 and 2 keep their own slots.
        assertEquals(List.of(0L, 4L, 2L, 5L), assetOutputIndexesForEquities(equities(0, 7, 0, 9)));

        // Every loan displaced: the whole first row is compensation, the whole second row collateral.
        assertEquals(List.of(3L, 4L, 5L), assetOutputIndexesForEquities(equities(1, 2, 3)));

        // Only the last one — the rank is among the displaced, not the loan index.
        assertEquals(List.of(0L, 1L, 3L), assetOutputIndexesForEquities(equities(0, 0, 5)));
    }

    /** A negative equity is not a positive one: only {@code signum() > 0} displaces. */
    @Test
    void onlyPositiveEquityDisplaces() {
        assertEquals(List.of(0L, 1L, 2L), assetOutputIndexesForEquities(equities(0, -5, 0)));
    }

    @Test
    void indexesAreAlwaysUniqueAndInRange() {
        List<BigInteger> mixed = equities(0, 4, 0, 0, 9, 2);
        List<Long> indexes = assetOutputIndexesForEquities(mixed);
        assertEquals(mixed.size(), indexes.size());
        assertEquals(indexes.size(), new java.util.HashSet<>(indexes).size(),
                "a duplicate would be refused on chain by lm_liquidate_action's list.unique conjunct");
        // Three displaced, so the filtered list is 6 + 3 = 9 long.
        indexes.forEach(i -> org.junit.jupiter.api.Assertions.assertTrue(i >= 0 && i < 9,
                "index " + i + " outside the filtered output list"));
    }

    /**
     * The bond echoes are the FIRST outputs this builder emits, in bond-input order, so a loan's echo
     * sits at {@code CCL_PREPENDED_OUTPUTS + } the bond-input index already computed for it. This is
     * an absolute body index — unlike the asset indexes above, which are into a filtered list — which
     * is precisely why it is the one that has to know about the prepended output.
     */
    @Test
    void bondEchoesFollowTheBondInputOrderOffsetByTheDummy() {
        assertEquals(List.of(1L, 2L, 3L), bondOutputIndexes(List.of(0L, 1L, 2L)));
        // Loans need not arrive in bond order: the pairing is carried, not re-derived.
        assertEquals(List.of(3L, 1L, 2L), bondOutputIndexes(List.of(2L, 0L, 1L)));
    }

    /**
     * ⛔ THE LOAD-BEARING CONSTANT, PINNED ON PURPOSE.
     * <p>
     * cardano-client-lib 0.7.2 prepends exactly one dummy output when a transaction carries
     * withdrawals — {@code StakeTx:292-295} is {@code if (withdrawalContexts.size() > 0)}, <b>one per
     * transaction, not one per withdrawal</b>. Measured on the accepted liquidation
     * {@code 49743a1e…}: 5 withdrawals, 1 dummy at index 0, our outputs at 1-3, change at 4.
     * <p>
     * If a library upgrade changes this, the single-pass layout is wrong and every
     * {@code lenderBondOutputIndex} points one slot off — at real money. This test is the first thing
     * that should fail, before V5 starts refusing every build.
     */
    @Test
    void cclPrependsExactlyOneOutputWhenWithdrawing() {
        assertEquals(1L, CCL_PREPENDED_OUTPUTS,
                "cardano-client-lib's prepended-output count changed — re-measure StakeTx and "
                        + "docs/ledger-index-ordering.md before touching this number");
    }

    /** Positive control: the arithmetic is discriminating, not vacuously agreeing with everything. */
    @Test
    void theArithmeticActuallyDiscriminates() {
        assertNotEquals(assetOutputIndexesForEquities(equities(0, 0, 0)),
                assetOutputIndexesForEquities(equities(0, 1, 0)),
                "equity must change the layout; if these agree the rule is not being applied");
        assertNotEquals(bondOutputIndexes(List.of(0L, 1L)), bondOutputIndexes(List.of(1L, 0L)),
                "bond pairing must reach the echo index");
    }

    /**
     * ⚠ EXHAUSTIVE, because every other case here is hand-picked and a hand-picked case can only
     * confirm the shape its author already had in mind.
     *
     * <p>The invariant is <b>tiling</b>, which no single example states: the two rows together must
     * cover {@code [0, n + d)} exactly once, where {@code d} is the number of displaced loans. A
     * non-displaced loan {@code i} holds slot {@code i} with its collateral; a displaced loan holds
     * slot {@code i} with its <em>compensation</em> and takes {@code n + rank} for its collateral. So
     * the collateral indexes and the compensation slots partition the filtered list with no gap and
     * no overlap — and an off-by-one anywhere in the second row breaks the partition even when it
     * leaves uniqueness and the range bound intact.
     *
     * <p>Covers all 364 sign patterns for {@code n = 0..5} over {-1, 0, +1}, so it includes the empty
     * vector and both saturated ends, none of which the examples above reach.
     */
    @Test
    void everyEquityPatternTilesTheFilteredOutputList() {
        for (int n = 0; n <= 5; n++) {
            int patterns = (int) Math.pow(3, n);
            for (int p = 0; p < patterns; p++) {
                long[] values = new long[n];
                int rest = p;
                for (int i = 0; i < n; i++) {
                    values[i] = rest % 3 - 1;   // -1, 0, +1
                    rest /= 3;
                }
                List<BigInteger> equity = equities(values);
                String what = "n=" + n + " equity=" + java.util.Arrays.toString(values);

                List<Long> collateral = assetOutputIndexesForEquities(equity);
                assertEquals(n, collateral.size(), "one collateral index per loan: " + what);

                long displaced = equity.stream().filter(e -> e.signum() > 0).count();
                long filteredSize = n + displaced;

                // The compensation slots: exactly the first-row positions of the displaced loans.
                java.util.Set<Long> slots = new java.util.HashSet<>(collateral);
                for (int i = 0; i < n; i++) {
                    if (equity.get(i).signum() > 0) {
                        assertNotEquals((long) i, collateral.get(i).longValue(),
                                "a displaced loan must not keep its own slot: " + what);
                        org.junit.jupiter.api.Assertions.assertTrue(slots.add((long) i),
                                "compensation slot " + i + " collides with a collateral index: " + what);
                    } else {
                        assertEquals((long) i, collateral.get(i).longValue(),
                                "an undisplaced loan keeps its own slot: " + what);
                    }
                }

                assertEquals(filteredSize, slots.size(),
                        "the two rows must tile [0," + filteredSize + ") with no gap or overlap: " + what);
                assertEquals(java.util.stream.LongStream.range(0, filteredSize).boxed()
                                .collect(java.util.stream.Collectors.toSet()),
                        slots, "the tiled set is not the contiguous range: " + what);
            }
        }
    }
}
