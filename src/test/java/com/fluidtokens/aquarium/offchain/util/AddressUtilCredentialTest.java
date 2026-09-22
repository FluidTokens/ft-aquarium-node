package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.AddressData;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.InlineData;
import com.fluidtokens.aquarium.offchain.util.UnusableTankDatumException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>THE DATUM IS UNTRUSTED INPUT, AND ON 2026-09-22 IT STOPPED THE WHOLE BOT.</b>
 *
 * <p>Every scheduled transaction on mainnet was failing with
 * {@code DeserialiseFailure 0 "expected tag"}. The message names CBOR, so the search went to CBOR:
 * Conway set tags, negative values, collateral returns, library versions. <b>None of it was the
 * cause, and the transaction's CBOR was never malformed</b> — 627 of 627 bytes decode cleanly.
 *
 * <p>The cause was one tank's datum
 * ({@code a1e6ca15fc60208250415200895609db78ee0a35441cbcedbb415648a11fd98a#1}):
 *
 * <pre>
 *   destination_address = Address {
 *     payment_credential = VerificationKey(h'')                       &lt;-- ZERO bytes
 *     stake_credential   = Some(Inline(VerificationKey(h'dea1c9..'))) &lt;-- 28 bytes
 *   }
 * </pre>
 *
 * <p>Built without checking, that becomes a <b>29-byte address carrying a 57-byte header</b> —
 * {@code 0x01} announces "payment key hash + stake key hash" and only one hash follows. The ledger
 * cannot read it as Shelley, falls back to Byron, and a Byron address is {@code [tag24(bytes), crc]}
 * — so at <b>offset 0 of the address's own buffer</b> it wants a tag and finds {@code 0x01}. That is
 * the whole error, and it is why "offset 0" pointed at nothing in the transaction.
 *
 * <p>⚠ <b>Nothing downstream could have caught this.</b> No validator ran, because no node could
 * parse the transaction; there was no phase-1 rejection to read, no script to debug, and the
 * evaluator's own error was about the request rather than the contents. <b>A guard here is the only
 * place it can be caught</b> — which is the general rule this test exists to hold: validate where
 * untrusted bytes become a domain object, not where they finally break something.
 */
class AddressUtilCredentialTest {

    /** The real payment credential from that tank's datum: 28 bytes, and the only sound half. */
    private static final byte[] REAL_HASH =
            HexUtil.decodeHexString("dea1c985a773148f14aa8c4fbdb8070881245abf6d872870e9cdece0");
    private static final byte[] OTHER_HASH =
            HexUtil.decodeHexString("682fec1c867c6aa59918ef6f25c6bdd38c1c7d03d71c0b4fa23c5bac");

    // ⚠ PAYMENT and STAKE credentials come from DIFFERENT generated packages with no shared
    // supertype -- blueprint.model.* for payment, blueprint.cardano.address.model.* for the inline
    // stake credential. That split is exactly why AddressUtil ended up with two code paths and a
    // bug in only one of them.
    private static com.fluidtokens.aquarium.offchain.blueprint.model.impl.VerificationKeyData
            paymentKey(byte[] hash) {
        var k = new com.fluidtokens.aquarium.offchain.blueprint.model.impl.VerificationKeyData();
        k.setVerificationKeyHash(hash);
        return k;
    }

    private static com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.VerificationKeyData
            stakeKey(byte[] hash) {
        var k = new com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.VerificationKeyData();
        k.setVerificationKeyHash(hash);
        return k;
    }

    private static com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.ScriptData
            stakeScript(byte[] hash) {
        var s = new com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.ScriptData();
        s.setScriptHash(hash);
        return s;
    }

    private static AddressData address(Object payment, Object stake) {
        var a = new AddressData();
        a.setPaymentCredential((com.fluidtokens.aquarium.offchain.blueprint.model.PaymentCredential) payment);
        if (stake == null) {
            a.setStakeCredential(Optional.empty());
        } else {
            var inline = new InlineData();
            inline.setCredential((com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Credential) stake);
            a.setStakeCredential(Optional.of(inline));
        }
        return a;
    }

    /** ⛔ The exact shape that took the bot down. It must be refused, not rendered. */
    @Test
    void anEmptyPaymentCredentialIsRefusedRatherThanTurnedIntoATwentyNineByteAddress() {
        var broken = address(paymentKey(new byte[0]), stakeKey(REAL_HASH));

        var thrown = assertThrows(UnusableTankDatumException.class,
                () -> AddressUtil.toAddress(broken, Networks.mainnet()),
                "an empty payment credential must be refused here -- unchecked it produces "
                        + "01dea1c9..., a 29-byte address with a 57-byte header, and a transaction "
                        + "that no node on the network can decode");

        assertTrue(thrown.getMessage().contains("payment"),
                "the refusal must say WHICH credential is unusable, or the next operator reading "
                        + "this in a log learns nothing the error code did not already say. Got: "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("0 bytes"),
                "and it must say what was actually wrong with it. Got: " + thrown.getMessage());
    }

    /** ⚠ The same check on the stake side: one guard, both halves, so they cannot drift apart. */
    @Test
    void anEmptyStakeCredentialIsRefusedToo() {
        var broken = address(paymentKey(REAL_HASH), stakeKey(new byte[0]));

        var thrown = assertThrows(UnusableTankDatumException.class,
                () -> AddressUtil.toAddress(broken, Networks.mainnet()));
        assertTrue(thrown.getMessage().contains("stake"), thrown.getMessage());
    }

    /**
     * ⛔ <b>AND A SCRIPT STAKE CREDENTIAL MUST BUILD A SCRIPT CREDENTIAL.</b>
     *
     * <p>This is the bug length-checking cannot find, and it is the more dangerous of the two: until
     * 2026-09-22 the stake branch called {@code Credential.fromKey(script.getScriptHash())}. The
     * address is then the <b>right length</b> with the <b>wrong header type</b> — {@code 0x01} where
     * {@code 0x21} belongs — so it decodes cleanly, submits cleanly, and pays an address the payee
     * does not control. <b>A malformed transaction fails loudly; a well-formed one to the wrong
     * address does not fail at all.</b>
     */
    @Test
    void aScriptStakeCredentialProducesAScriptHeaderNotAKeyHeader() {
        var withScriptStake = AddressUtil.toAddress(
                address(paymentKey(REAL_HASH), stakeScript(OTHER_HASH)), Networks.mainnet());
        var withKeyStake = AddressUtil.toAddress(
                address(paymentKey(REAL_HASH), stakeKey(OTHER_HASH)), Networks.mainnet());

        int scriptHeader = withScriptStake.getBytes()[0] & 0xFF;
        int keyHeader = withKeyStake.getBytes()[0] & 0xFF;

        assertEquals(0x21, scriptHeader,
                "type 2 = payment key hash + stake SCRIPT hash. Building it with fromKey yields "
                        + "0x01, an address of the right length that pays the wrong place");
        assertEquals(0x01, keyHeader, "type 0 = payment key hash + stake key hash");
        assertEquals(57, withScriptStake.getBytes().length, "base addresses are 1 + 28 + 28");
    }

    /** And the sound cases still build, so the guard is not simply "refuse everything". */
    @Test
    void wellFormedCredentialsStillBuildTheAddressesTheyAlwaysDid() {
        var base = AddressUtil.toAddress(address(paymentKey(REAL_HASH), stakeKey(OTHER_HASH)), Networks.mainnet());
        assertEquals(57, base.getBytes().length);
        assertTrue(base.getAddress().startsWith("addr1"), base.getAddress());

        var enterprise = AddressUtil.toAddress(address(paymentKey(REAL_HASH), null), Networks.mainnet());
        assertEquals(29, enterprise.getBytes().length, "enterprise is 1 + 28, and legitimately so");
        assertEquals(0x61, enterprise.getBytes()[0] & 0xFF,
                "⚠ 0x61 -- an enterprise header HONESTLY describes 29 bytes. The broken address "
                        + "was 29 bytes under header 0x01, which claims 57. Length alone is not the "
                        + "defect; length disagreeing with the header is");
    }
}
