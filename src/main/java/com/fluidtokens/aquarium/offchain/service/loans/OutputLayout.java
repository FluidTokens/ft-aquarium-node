package com.fluidtokens.aquarium.offchain.service.loans;

/**
 * <b>What cardano-client-lib contributes to a transaction's output list, and where.</b>
 *
 * <p>Both liquidation builders compute their redeemers' output indexes from the order they emit
 * outputs in, rather than observing them off a throwaway layout probe (T-051, 2026-08-26). That is
 * sound because <b>outputs are a LIST and the ledger preserves builder order exactly</b> — unlike
 * inputs and reference inputs, which are sets re-sorted lexicographically by
 * {@code (txhash, output index)} before a validator ever sees them. See
 * {@code docs/ledger-index-ordering.md} for the measurement, and note the consequence: sorting is
 * the right rule for inputs, reference inputs, withdrawals and mint policies, and there is nothing
 * to sort for outputs.
 *
 * <p>This class holds the one thing in that layout the builders do <em>not</em> control.
 */
final class OutputLayout {

    private OutputLayout() {
    }

    /**
     * <b>How many outputs cardano-client-lib puts in front of a builder's own.</b>
     *
     * <p>Exactly one: a transaction carrying withdrawals gets a single dummy output prepended at the
     * change address to trigger input selection (v0.7.2 {@code StakeTx:292-295} —
     * {@code if (withdrawalContexts.size() > 0)}, <b>one per transaction, not one per withdrawal</b>).
     * Both builders always withdraw, so this is never zero on either path. Change is appended
     * <em>after</em> the builder's outputs and so does not shift them.
     *
     * <p>Measured on the accepted liquidation {@code 49743a1e…}: 5 withdrawals, 1 dummy at index 0
     * carrying 1,000,000 lovelace at the change address, the builder's three outputs at 1-3, change
     * at 4.
     *
     * <p>⚠ <b>This is the single library-implementation detail the single-pass layout depends on, and
     * it is measured on the plain path only</b> — the convert path has no accepted transaction. If a
     * cardano-client-lib upgrade moves it, every {@code lenderBondOutputIndex} points one slot off,
     * at real money. Two things stand in the way: {@code SinglePassOutputLayoutTest} fails first and
     * names this constant, and V5 then refuses every build with {@code STRUCTURAL_ASSERTION_FAILED}
     * rather than emitting one. Neither is a substitute for re-measuring on an upgrade.
     */
    static final long CCL_PREPENDED_OUTPUTS = 1;
}
