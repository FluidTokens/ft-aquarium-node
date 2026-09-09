package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * {@code Signature} from {@code lib/fluidtokens/types/oracle.ak}.
 *
 * @param keyPosition index into the oracle validator's {@code verification_keys} parameter — not
 *                    the public key itself. {@code validators/oracle.ak} looks the key up by
 *                    position and {@code expect}s the signature to verify against it, so a wrong
 *                    position fails the whole transaction rather than just dropping one signature.
 */
public record OracleSignature(int keyPosition, String signatureHex) {
}
