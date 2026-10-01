package com.fluidtokens.aquarium.offchain.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ⛔ <b>Whether this node may build Lending v4 transactions, as decided by {@link LoansConfigVerifier}
 * at startup.</b>
 *
 * <p>A Lending v4 config problem used to throw out of the verifier's {@code @PostConstruct}, and a
 * throwing bean takes the whole application context with it — including the scheduled-transaction
 * processor, which uses nothing from Lending v4. That grounded mainnet twice: 2026-09-19 (three
 * pool-side hashes changed in place) and 2026-10-01 (FluidTokens re-pointed the claim, change-collateral
 * and borrow actions at one unpublished script, 2026-09-30 00:50Z).
 *
 * <p>So a config problem now <b>closes this gate</b> instead of stopping the node. Every Lending v4
 * transaction path — liquidation in every mode including shadow, convert, compound — checks it and
 * refuses with the reason below; nothing else on the node is affected. A closed gate stays closed
 * for the life of the process: the config is verified once, at boot, so re-opening it is a restart
 * after the coordinates are fixed.
 */
@Component
public class LendingConfigGate {

    /** The refusal name lending paths log, so a closed gate is greppable. */
    public static final String REFUSAL = "LENDING_CONFIG_MISMATCH";

    /** Said when a caller closes the gate without a reason: closed is what matters, not the text. */
    static final String NO_REASON_GIVEN = "Lending v4 config could not be verified (no reason was given)";

    // ⛔ "Closed" is its own flag, not "a reason is present". It used to be the latter, which made
    // block(null) a silent no-op that left the gate OPEN -- fail-open on exactly the path meant to fail
    // closed. No caller passed null, but a caller passing a bare e.getMessage() one day would have.
    private volatile boolean blocked;
    private volatile String blockedReason;

    /** Closes the gate. The first reason wins: it is the one the startup log explains. */
    public synchronized void block(String reason) {
        if (!blocked) {
            blockedReason = reason == null || reason.isBlank() ? NO_REASON_GIVEN : reason;
            blocked = true;
        }
    }

    /** Why Lending v4 transactions are refused, or empty when they may be built. */
    public Optional<String> blockedReason() {
        return blocked ? Optional.of(blockedReason) : Optional.empty();
    }

    public boolean isBlocked() {
        return blocked;
    }
}
