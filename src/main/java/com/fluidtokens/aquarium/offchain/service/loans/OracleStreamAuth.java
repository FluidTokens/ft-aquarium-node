package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignError;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;

import java.net.URI;
import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Signs the oracle stream's connection query: a CIP-30 {@code signData} over a fixed text, by the node
 * wallet's own STAKE key, which FluidTokens' authorizer checks against an Aquarium staking bond named after
 * that key (FAB-173, probe log items 9 and 36).
 *
 * <p>⛔ <b>Built from the {@link Account} bean only — never re-derived from the mnemonic.</b>
 * {@code AccountConfig} selects the CIP-1852 account with {@code wallet.account-index}, and with it the stake
 * key; deriving again here could sign with a different key than the one the node stakes with.
 *
 * <p>⛔ <b>The signed query is a credential for two minutes.</b> {@link #toString()} carries none of it, and
 * no caller logs the URI this returns.
 */
public final class OracleStreamAuth {

    private final Account account;

    public OracleStreamAuth(Account account) {
        this.account = account;
    }

    /** Exactly the text the authorizer verifies — no trailing newline. {@code ts} is epoch milliseconds. */
    static String signedText(String stakeAddress, long ts) {
        return "FluidTokens oracle websocket\nstake: " + stakeAddress + "\nts: " + ts;
    }

    /**
     * {@code base} with {@code ?stakeAddress=<urlenc>&ts=<ts>&key=<COSE_Key hex>&signature=<COSE_Sign1 hex>}
     * appended, signed now for {@code ts}. {@code base} must carry no query or fragment (the launcher refuses
     * such a URL before a client exists).
     *
     * @throws IllegalStateException when signing fails; its message names only the failure's class
     */
    URI signedUri(URI base, long ts) {
        String stakeAddress = account.stakeAddress();
        HdKeyPair stakeKey = account.stakeHdKeyPair();
        DataSignature signature;
        try {
            signature = CIP30DataSigner.INSTANCE.signData(new Address(stakeAddress).getBytes(),
                    signedText(stakeAddress, ts).getBytes(UTF_8),
                    stakeKey.getPrivateKey().getKeyData(), stakeKey.getPublicKey().getKeyData());
        } catch (DataSignError e) {
            throw new IllegalStateException("could not sign the oracle stream query: " + OracleStreamLog.simpleName(e));
        }
        return URI.create(base.toString()
                + "?stakeAddress=" + URLEncoder.encode(stakeAddress, UTF_8)
                + "&ts=" + ts
                + "&key=" + signature.key()
                + "&signature=" + signature.signature());
    }

    /** ⛔ Never the stake address, the key, the signature or a query. */
    @Override
    public String toString() {
        return "OracleStreamAuth[node wallet stake key]";
    }
}
