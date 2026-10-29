package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.cip.cip8.COSESign1;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174: the oracle stream's signed query. The text and the query shape are what FluidTokens' authorizer
 * accepted on preview on 2026-10-29 (probe log items 9 and 36): a CIP-30 {@code signData} by the STAKE key over
 * {@code "FluidTokens oracle websocket\nstake: <stake address>\nts: <epoch ms>"}.
 */
class OracleStreamAuthTest {

    private static final URI BASE = URI.create("wss://2tgda3mk4l.execute-api.eu-west-1.amazonaws.com/dev");
    private static final long TS = 1_793_279_099_046L;

    /** The exact bytes, written out here rather than built from production: no trailing newline. */
    @Test
    void theSignedTextIsExactlyTheAuthorizersText() {
        String text = OracleStreamAuth.signedText("stake_test1abc", TS);
        assertEquals("FluidTokens oracle websocket\nstake: stake_test1abc\nts: 1793279099046", text);
        assertFalse(text.endsWith("\n"), "no trailing newline");
        assertArrayEquals(("FluidTokens oracle websocket" + (char) 10 + "stake: stake_test1abc" + (char) 10
                + "ts: 1793279099046").getBytes(UTF_8), text.getBytes(UTF_8));
    }

    /**
     * The query verifies, names the stake address in its COSE header, and signs exactly the text — read back
     * from the COSE_Sign1 payload (reachable on CCL 0.7.2 as {@code COSESign1.deserialize(...).payload()}).
     */
    @Test
    void theSignatureVerifiesOverTheStakeAddressAndTheExactText() {
        Account account = new Account(Networks.preview());
        Map<String, String> query = query(new OracleStreamAuth(account).signedUri(BASE, TS));

        DataSignature signature = new DataSignature(query.get("signature"), query.get("key"));
        assertTrue(CIP30DataSigner.INSTANCE.verify(signature), "the CIP-30 signature must verify");
        assertArrayEquals(new Address(account.stakeAddress()).getBytes(), signature.address(),
                "the COSE address header must be the STAKE address's bytes");
        byte[] payload = COSESign1.deserialize(HexUtil.decodeHexString(query.get("signature"))).payload();
        assertArrayEquals(("FluidTokens oracle websocket\nstake: " + account.stakeAddress() + "\nts: " + TS)
                .getBytes(UTF_8), payload);
        assertEquals(account.stakeAddress(), query.get("stakeAddress"));
        assertEquals(String.valueOf(TS), query.get("ts"));
    }

    @Test
    void theQueryIsStakeAddressThenTsThenKeyThenSignatureOnTheBase() {
        URI signed = new OracleStreamAuth(new Account(Networks.preview())).signedUri(BASE, TS);
        assertEquals(List.of("stakeAddress", "ts", "key", "signature"), new ArrayList<>(query(signed).keySet()));
        assertEquals(BASE.getScheme(), signed.getScheme());
        assertEquals(BASE.getHost(), signed.getHost());
        assertEquals(BASE.getPath(), signed.getPath());
    }

    @Test
    void toStringCarriesNoneOfTheSigningMaterial() {
        Account account = new Account(Networks.preview());
        OracleStreamAuth auth = new OracleStreamAuth(account);
        Map<String, String> query = query(auth.signedUri(BASE, TS));
        String shown = auth.toString();
        for (String secret : List.of(query.get("key"), query.get("signature"), account.stakeAddress(),
                "signature=", "key=", "stakeAddress=")) {
            assertFalse(shown.contains(secret), "toString() carries " + secret);
        }
        assertFalse(new OracleStreamClient(BASE, auth, OracleStreamClient.Timings.PRODUCTION, null, null, null,
                null, null).toString().contains("?"), "the client's toString() carries no query");
    }

    /**
     * {@code wallet.account-index} selects the stake key (AccountConfig derives account N of the mnemonic), and
     * the signature follows it: accounts 0 and 1 sign as two different stake addresses.
     */
    @Test
    void accountsZeroAndOneSignWithDifferentStakeAddresses() {
        String mnemonic = new Account(Networks.preview()).mnemonic();
        Account zero = Account.createFromMnemonic(Networks.preview(), mnemonic, 0, 0);
        Account one = Account.createFromMnemonic(Networks.preview(), mnemonic, 1, 0);
        assertNotEquals(zero.stakeAddress(), one.stakeAddress());

        for (Account account : List.of(zero, one)) {
            Map<String, String> query = query(new OracleStreamAuth(account).signedUri(BASE, TS));
            assertEquals(account.stakeAddress(), query.get("stakeAddress"));
            DataSignature signature = new DataSignature(query.get("signature"), query.get("key"));
            assertTrue(CIP30DataSigner.INSTANCE.verify(signature));
            assertArrayEquals(new Address(account.stakeAddress()).getBytes(), signature.address());
        }
    }

    private static Map<String, String> query(URI signed) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : signed.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), UTF_8));
        }
        return params;
    }
}
