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
     * ⛔ <b>A PAYOUT THE LEDGER CANNOT HOLD IS REFUSED, AND IT IS MOST OF THE BACKLOG.</b>
     *
     * <p>Every Cardano output must carry at least {@code (160 + size) x coinsPerUtxoByte} — about
     * 0.857 ada for a plain ada-only output. Tank
     * {@code a5fb7d8b69d5610785e7a452c8753280affbcb4c89334e40b384b38901595d5f#0} schedules a payout
     * of <b>700,000 lovelace</b>, which no valid transaction can pay to anyone.
     *
     * <p>⚑ Measured on mainnet 2026-09-22: of <b>403</b> due tanks, <b>329</b> are in this state.
     * Only 74 are payable at all. The backlog was never mostly healthy.
     *
     * <p>⚠ <b>It was invisible from the error.</b> cardano-client-lib does not refuse a short output
     * — it tops it up out of change (CCL trap 6). So the transaction builds, reaches the validator,
     * and is rejected for paying an amount the datum never named; the remote evaluator then reports
     * {@code {"ScriptFailures":{}}}, an empty map naming nothing. <b>A library being helpful is why
     * this took a day to find.</b>
     */
    @Test
    void aPayoutBelowTheMinUtxoFloorIsRefusedRatherThanBuilt() {
        var params = new com.bloxbean.cardano.client.api.model.ProtocolParams();
        params.setCoinsPerUtxoSize("4310");

        var payee = com.bloxbean.cardano.client.address.AddressProvider.getBaseAddress(
                com.bloxbean.cardano.client.address.Credential.fromKey(HexUtil.decodeHexString(
                        "1db7e8e3c128c1a3711c7326b232231c98441149041349ee8e0282ec")),
                com.bloxbean.cardano.client.address.Credential.fromKey(HexUtil.decodeHexString(
                        "e4ff47a36e9602fdee192181be146d445591a051d56b5615fe3e7d42")),
                Networks.mainnet());

        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address(payee.getAddress())
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(java.math.BigInteger.valueOf(700_000L)).build())
                .build();

        var floor = new com.bloxbean.cardano.client.common.MinAdaCalculator(params)
                .calculateMinAda(output);

        assertTrue(floor.compareTo(java.math.BigInteger.valueOf(700_000L)) > 0,
                "the real tank's 700,000 lovelace payout must sit BELOW the floor — if this ever "
                        + "stops being true the refusal is over-eager and 329 tanks are being "
                        + "blacklisted for nothing. Floor was " + floor);
    }

    /**
     * ⛔ <b>AND A TOKEN PAYOUT IS NOT "BELOW THE FLOOR" — it declares no ada, which is correct.</b>
     *
     * <p>{@code AssetAmountUtil.toValue} puts the quantity in the multi-asset and leaves {@code coin}
     * at zero for a token payout. A floor check that does not know this sees {@code 0 < 857,690} and
     * refuses every token tank there is.
     *
     * <p>⚠ <b>That is not hypothetical — it happened.</b> The first run of the min-UTxO refusal on
     * mainnet blacklisted <b>43 live, payable tanks</b> alongside the 329 genuinely impossible ones:
     * 402 refused where 359 was right. A token output's min-ada comes from the TRANSACTION, out of
     * the wallet, exactly as it should; the datum is not wrong to omit it.
     *
     * <p>⚑ The shape of this bug is worth more than the fix: <b>a guard derived from one case
     * (ada) and applied to a case it had never seen (tokens) reads as correct in both</b>, because
     * the number it compares is real in both. Only the meaning of zero differs.
     */
    @Test
    void aTokenPayoutIsNotRefusedForDeclaringZeroAda() {
        var tokenPayout = new com.fluidtokens.aquarium.offchain.blueprint.types.general.model.impl
                .CardanoTokenData();
        tokenPayout.setPolicyid(HexUtil.decodeHexString(
                "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e"));
        tokenPayout.setAssetname(HexUtil.decodeHexString("0014df10464c4454"));
        tokenPayout.setAmount(java.math.BigInteger.valueOf(5_000_000L));
        tokenPayout.setDivider(java.math.BigInteger.valueOf(1_000_000L));
        tokenPayout.setOracle(java.util.Optional.empty());

        var value = com.fluidtokens.aquarium.offchain.util.AssetAmountUtil
                .toValue(java.util.List.of(tokenPayout));

        assertTrue(value.getCoin().signum() == 0,
                "fixture check: a token payout really does declare zero ada — that is what makes a "
                        + "naive floor check refuse it");
        assertFalse(value.getMultiAssets().isEmpty(),
                "and it really does carry the asset — which is the signal the guard keys on, so a "
                        + "token payout is never measured against an ada floor it cannot meet");
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
