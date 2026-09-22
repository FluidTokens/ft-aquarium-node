package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Script;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.VerificationKey;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.AddressData;
import com.fluidtokens.aquarium.offchain.blueprint.model.Inline;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.InlineData;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.VerificationKeyData;

import java.util.Optional;

public class AddressUtil {

    public static com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Address toOnchainAddress(Address address) {
        AddressData addressData = new AddressData();
        //payment credential
        VerificationKeyData paymentKey = new VerificationKeyData();
        paymentKey.setVerificationKeyHash(address.getPaymentCredentialHash().get());
        addressData.setPaymentCredential(paymentKey);

        // stake credentials
        var stakingKey = new com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.VerificationKeyData();
        stakingKey.setVerificationKeyHash(address.getDelegationCredentialHash().get());
        var inlineData = new InlineData();
        inlineData.setCredential(stakingKey);
        addressData.setStakeCredential(Optional.of(inlineData));

        return addressData;
    }

    /** A Cardano credential is a blake2b-224 digest: 28 bytes, always. */
    private static final int CREDENTIAL_HASH_BYTES = 28;

    /**
     * ⛔ <b>A DATUM IS UNTRUSTED INPUT. Anyone can write one, and this one is written by the user
     * whose tank we are about to pay.</b>
     *
     * <p>Measured on mainnet 2026-09-22. Tank
     * {@code a1e6ca15fc60208250415200895609db78ee0a35441cbcedbb415648a11fd98a#1} carries a
     * destination address whose <b>payment credential is a ZERO-LENGTH bytestring</b>:
     *
     * <pre>
     *   Address {
     *     payment_credential = VerificationKey(h'')                       &lt;-- 0 bytes
     *     stake_credential   = Some(Inline(VerificationKey(h'dea1c9..'))) &lt;-- 28 bytes
     *   }
     * </pre>
     *
     * <p>Unvalidated, this became {@code AddressProvider.getBaseAddress(fromKey(h''), fromKey(..))}
     * — and neither CCL's {@code Credential} nor {@code AddressProvider} checks a hash length. The
     * result was a <b>29-byte address carrying a 57-byte header</b> ({@code 0x01} = base, payment
     * key + stake key), emitted into a transaction output:
     *
     * <pre>
     *   output[0]: bytes[29] 01dea1c985a773148f14aa8c4fbdb8070881245abf6d872870e9cdece0
     *                        ^^ says "two 28-byte hashes follow". One 28-byte hash follows.
     * </pre>
     *
     * <p>⚑ <b>And this is what produced {@code DeserialiseFailure 0 "expected tag"}</b>, the error
     * that had every tank transaction rejected. An address is decoded as its <i>own</i> buffer, which
     * is why the offset is 0 rather than somewhere inside the transaction: the ledger cannot read it
     * as Shelley, falls back to Byron, and a Byron address is {@code [tag24(bytes), crc]} — so at
     * offset 0 it wants a <b>tag</b> and finds {@code 0x01}. The message names CBOR and the cause is
     * an address; that is why reading it literally led nowhere.
     *
     * <p>⚠ <b>The failure mode is the expensive part: we built the bad transaction rather than
     * refusing the bad datum.</b> Nothing downstream could have caught it — the CBOR is well-formed
     * (627 of 627 bytes decode cleanly), so it is not a serialisation bug, and no validator ever ran
     * because no node could parse the transaction. <b>Validate at the boundary where untrusted bytes
     * become a domain object</b>; refusing here turns an unexplainable outage into one skipped tank.
     */
    /**
     * ⚠ Takes {@code Object} because the generated blueprint gives payment and stake credentials
     * <b>three unrelated interfaces</b> ({@code model.PaymentCredential}, {@code model.StakeCredential},
     * {@code cardano.address.model.Credential}) with no common supertype — so one typed helper cannot
     * serve both sides, and two near-identical helpers is how the two sides drift apart. They already
     * had: only the stake side carried the {@code fromKey}-for-a-script bug.
     */
    private static Credential credentialOf(Object credential, String role) {
        return switch (credential) {
            case com.fluidtokens.aquarium.offchain.blueprint.model.VerificationKey key ->
                    Credential.fromKey(checkedHash(key.getVerificationKeyHash(), role, "key"));
            case VerificationKey key ->
                    Credential.fromKey(checkedHash(key.getVerificationKeyHash(), role, "key"));
            case com.fluidtokens.aquarium.offchain.blueprint.model.Script script ->
                    Credential.fromScript(checkedHash(script.getScriptHash(), role, "script"));
            // ⚠ fromSCRIPT. This read fromKey until 2026-09-22 — a quieter bug than the empty hash:
            // a script stake credential built as a key credential yields an address of the RIGHT
            // LENGTH and the WRONG HEADER (0x01 where 0x21 belongs), so it decodes, it submits, and
            // it pays an address nobody can spend from. Length validation cannot catch that one.
            case Script script ->
                    Credential.fromScript(checkedHash(script.getScriptHash(), role, "script"));
            case null -> throw new IllegalArgumentException(
                    "datum carries no " + role + " credential at all");
            default -> throw new IllegalArgumentException(
                    "Unexpected " + role + " credential type: " + credential);
        };
    }

    /** ⚠ Length is the whole check: 28 bytes or the address is not an address. */
    private static byte[] checkedHash(byte[] hash, String role, String kind) {
        if (hash == null || hash.length != CREDENTIAL_HASH_BYTES) {
            throw new IllegalArgumentException(
                    "datum carries an unusable " + role + " " + kind + " credential: expected a "
                            + CREDENTIAL_HASH_BYTES + "-byte blake2b-224 hash but got "
                            + (hash == null ? "null" : hash.length + " bytes")
                            + ". Refusing to build an address from it — an out-of-spec credential "
                            + "produces a transaction no node can decode.");
        }
        return hash;
    }

    public static Address toAddress(
            com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Address addressData,
            Network network) {

        var paymentCredential = credentialOf(addressData.getPaymentCredential(), "payment");

        var stakeCredentialOpt = addressData.getStakeCredential()
                .flatMap(stakeCredential ->
                        switch (stakeCredential) {
                            case Inline inline -> Optional.of(inline.getCredential());
                            // ⚠ A POINTER stake credential is dropped, deliberately and as before:
                            // it degrades to an enterprise address at the same payment credential,
                            // which is a real address the payee controls. Not a silent loss worth
                            // refusing over.
                            default -> Optional.empty();
                        }
                )
                .map(credential -> credentialOf(credential, "stake"));

        return stakeCredentialOpt
                .map(stake -> AddressProvider.getBaseAddress(paymentCredential, stake, network))
                .orElseGet(() -> AddressProvider.getEntAddress(paymentCredential, network));
    }

}
