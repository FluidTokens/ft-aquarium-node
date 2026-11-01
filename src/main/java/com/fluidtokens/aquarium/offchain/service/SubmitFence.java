package com.fluidtokens.aquarium.offchain.service;

/**
 * Whether a stage may still transmit. Consulted before each candidate and immediately before every
 * transmission; a stage that reads {@code false} transmits nothing and ends.
 */
@FunctionalInterface
public interface SubmitFence {

    /**
     * @return {@code false} when the chain rolled back, the pass deadline passed, the engine is closing, or
     *         the pass that handed out this fence is no longer the one running; nothing may then be
     *         transmitted
     */
    boolean mayProceed();

    /**
     * Never closes: the fence for callers outside the wallet pipeline (tests, and the {@code cycle()}
     * conveniences of the liquidation and compound executors).
     */
    SubmitFence OPEN = () -> true;
}
