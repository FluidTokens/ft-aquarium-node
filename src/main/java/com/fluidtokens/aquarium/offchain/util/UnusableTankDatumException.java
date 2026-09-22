package com.fluidtokens.aquarium.offchain.util;

/**
 * ⛔ <b>THIS TANK CAN NEVER BE PROCESSED, no matter how many times it is tried.</b>
 *
 * <p>The distinction this type carries is the whole point of it: a tank can fail because
 * <b>its own datum is garbage</b> — a credential that is not 28 bytes, a shape that cannot become
 * an address — or because <b>the world was briefly unavailable</b> — a provider 500, a 429, an
 * evaluator outage, a node still catching up. The first is a property of bytes written on chain and
 * therefore immutable: retrying is guaranteed to fail again. The second says nothing about the tank
 * at all.
 *
 * <p>⚠ <b>Both used to be caught by the same {@code catch (Exception)} and answered the same way:
 * blacklist until restart.</b> That is correct for the first and destructive for the second — a
 * healthy tank discarded because Blockfrost hiccuped is not retried for as long as the node runs,
 * and nothing says so afterwards. It is invisible precisely because a blacklist produces silence,
 * and silence looks like an empty queue.
 *
 * <p>⇒ Only this exception earns a permanent refusal. Everything else is transient by default,
 * because <b>the cost of retrying a genuinely dead tank is one log line per cycle, and the cost of
 * discarding a live one is the payment never happening.</b>
 */
public class UnusableTankDatumException extends RuntimeException {

    public UnusableTankDatumException(String message) {
        super(message);
    }
}
