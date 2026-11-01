package com.fluidtokens.aquarium.offchain.service;

/**
 * Whether a stage may still transmit. Consulted before each candidate and immediately before every
 * transmission; a stage that reads {@code false} transmits nothing and ends.
 */
@FunctionalInterface
public interface SubmitFence {

    /**
     * @return {@code false} when the chain rolled back or the pass deadline passed, and nothing may
     *         be transmitted
     */
    boolean mayProceed();

    /** Never closes: the timers' fence until the pipeline drives the stages (FAB-183-8). */
    SubmitFence OPEN = () -> true;
}
