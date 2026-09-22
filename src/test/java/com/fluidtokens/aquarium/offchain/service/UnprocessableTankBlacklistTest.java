package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.AddressData;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.InlineData;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A SEMANTICALLY BROKEN TANK MUST FAIL, BE BLACKLISTED, AND STAY BLACKLISTED.</b>
 *
 * <p>The datum that stopped the bot on 2026-09-22 was <b>formally valid CBOR</b> — it decoded
 * perfectly into the blueprint type. What it carried was nonsense: a payment credential of zero
 * bytes. Nothing structural was going to reject it, which is why it reached transaction building
 * and produced an address no node could parse.
 *
 * <p>Giovanni's rule for these: <i>"the loop should eventually discard and ignore these utxos …
 * still needs to result as a failure and add these loans to blacklist until restart"</i>. That is
 * the behaviour the processor already has for any thrown exception, and this test exists so the
 * three things it depends on cannot be changed independently:
 *
 * <ol>
 *   <li>the refusal is <b>unchecked</b>, so it reaches {@code catch (Exception)} rather than
 *       escaping the loop and killing the cycle for every other tank;</li>
 *   <li>the tank is <b>recorded</b> in the unprocessable list and <b>filtered out</b> of subsequent
 *       cycles;</li>
 *   <li>that list is <b>never cleared</b> — so a tank whose datum can never improve is tried once,
 *       not every five minutes forever.</li>
 * </ol>
 *
 * <p>⚠ <b>Point 3 is the one that would rot silently.</b> Adding a {@code clear()} looks like tidy
 * housekeeping and turns a permanent refusal into an infinite retry against data that cannot change
 * — burning a wallet utxo's worth of attention per cycle and refilling the logs with the same
 * failure. A blacklist that forgets is not a blacklist.
 */
class UnprocessableTankBlacklistTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/ScheduledTransactionService.java");

    /**
     * ⚠ Unchecked, deliberately. {@code catch (Exception)} would not catch an {@code Error}, and a
     * checked exception would force a signature change on a path that has no better answer than
     * "skip this tank" — so the refusal must be a {@link RuntimeException} and nothing else.
     */
    @Test
    void theRefusalIsUncheckedSoTheLoopBlacklistsTheTankInsteadOfDying() {
        var broken = new AddressData();
        var emptyPayment =
                new com.fluidtokens.aquarium.offchain.blueprint.model.impl.VerificationKeyData();
        emptyPayment.setVerificationKeyHash(new byte[0]);
        broken.setPaymentCredential(emptyPayment);
        var stake =
                new com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.VerificationKeyData();
        stake.setVerificationKeyHash(HexUtil.decodeHexString(
                "dea1c985a773148f14aa8c4fbdb8070881245abf6d872870e9cdece0"));
        var inline = new InlineData();
        inline.setCredential(stake);
        broken.setStakeCredential(Optional.of(inline));

        var thrown = assertThrows(RuntimeException.class,
                () -> AddressUtil.toAddress(broken, Networks.mainnet()));

        assertTrue(thrown instanceof Exception,
                "the processor catches Exception -- an Error would escape the loop and take every "
                        + "other tank in the cycle down with this one bad datum");
    }

    /**
     * ⛔ The blacklist is <b>write-only by design</b>: added to on failure, read on every cycle,
     * never emptied. Restarting the node is the deliberate way to retry.
     */
    @Test
    void theUnprocessableListIsNeverClearedSoARefusalLastsUntilRestart() throws IOException {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("unprocessableScheduledTransactions.add("),
                "a failing tank must be recorded, or the same bad datum is rebuilt every cycle");
        assertTrue(source.contains("filterUnprocessableScheduledTransactions("),
                "and the record must be consulted, or recording it achieves nothing");

        assertFalse(source.contains("unprocessableScheduledTransactions.clear()"),
                "⛔ the blacklist must NOT be cleared. These datums cannot improve -- the payment "
                        + "credential is written on chain and immutable -- so clearing it converts "
                        + "a one-time refusal into a permanent retry loop against data that will "
                        + "never work. Restart is the intended way to re-try");
    }
}
