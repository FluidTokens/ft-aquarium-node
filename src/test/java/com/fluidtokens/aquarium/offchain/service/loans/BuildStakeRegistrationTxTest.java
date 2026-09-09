package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚙ Builds the UNSIGNED stake-registration transaction for the convert action's reward account.
 *
 * <p>A withdraw-zero invocation is only valid from a reward account that exists. {@code dc715410…}
 * was never registered — FluidTokens registered 25 accounts in the deployment transaction
 * {@code 7b9f20db…} and the convert family postdates that batch — so every convert is rejected at
 * submit with {@code ConwayWithdrawalsMissingAccounts}. That is a ledger rule, invisible to both the
 * offline evaluator and Blockfrost's script evaluation (CCL trap 11).
 *
 * <p>⛔ <b>BUILDS ONLY. It never signs and never submits</b>, and it cannot: no key or mnemonic is
 * read anywhere in this class. The output is CBOR for a human to sign with the bot's key.
 *
 * <p>⚠ The fee is budgeted for ONE additional vkey witness — the signature the operator will add.
 * Building unsigned and signing later otherwise under-pays by the witness's own bytes.
 *
 * <p>Disabled unless {@code BUILD_STAKE_REG} names an output file.
 */
@EnabledIfEnvironmentVariable(named = "BUILD_STAKE_REG", matches = ".+")
class BuildStakeRegistrationTxTest {

    /**
     * The convert action's reward account. Defaults to the CURRENT deployment (`c3f51e55…`, published
     * at `8ab0c6d1…#0` after FluidTokens merged the batchability fix); override both with
     * {@code REWARD_ADDRESS} / {@code SCRIPT_HASH} for a future redeploy.
     *
     * <p>⚠ <b>Every redeploy of this validator needs this again.</b> A new script hash is a new reward
     * account, and a withdraw-zero is only valid from an account that exists — so
     * {@code ConwayWithdrawalsMissingAccounts} recurs on the first convert otherwise. It has already
     * happened once, on {@code dc715410…} (findings §57.8).
     */
    private static final String REWARD_ADDRESS = System.getenv().getOrDefault("REWARD_ADDRESS",
            "stake178pl28j4m52k5npf5swlp43skrv0rjt089h45vth3z55kuqnzlvkh");
    private static final String CONVERT_ACTION_HASH = System.getenv().getOrDefault("SCRIPT_HASH",
            "c3f51e55dd156a4c29a41df0d630b0d8f1c96f396f5a317788a94b70");
    private static final String BOT_WALLET =
            "addr1q8kfqpcpm3c77sstcf9a5mzgfa0eya5rd2h8838hpd75fymg9lkpepnud2jejx80dujud0wn3sw86q7hrs95lg3utwkqvd5zcd";

    @Test
    void buildTheUnsignedRegistration() throws Exception {
        // ⛔ The address must carry the SCRIPT credential we mean to register, not a lookalike.
        Address reward = new Address(REWARD_ADDRESS);
        assertTrue(reward.isScriptHashInDelegationPart(),
                "the reward address does not carry a SCRIPT credential; registering a key credential "
                        + "here would spend the deposit on the wrong account");
        assertEquals(CONVERT_ACTION_HASH,
                HexUtil.encodeHexString(reward.getDelegationCredentialHash().orElseThrow()),
                "the reward address is not the convert action's");

        BFBackendService backend = new BFBackendService(
                "https://cardano-mainnet.blockfrost.io/api/v0/", System.getenv("BLOCKFROST_MAINNET_KEY"));

        List<Utxo> wallet = backend.getUtxoService().getUtxos(BOT_WALLET, 100, 1).getValue();
        assertFalse(wallet.isEmpty(), "the bot wallet holds no UTxOs to pay the deposit and fee from");

        Transaction tx = new QuickTxBuilder(new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()), null)
                .compose(new Tx().registerStakeAddress(reward).from(BOT_WALLET))
                .feePayer(BOT_WALLET)
                .additionalSignersCount(1)          // the operator's signature, not yet present
                .build();                            // ⛔ build(), never buildAndSign()

        assertNotNull(tx.getBody().getCerts(), "no certificate was produced");
        assertEquals(1, tx.getBody().getCerts().size(), "expected exactly one registration certificate");
        assertTrue(tx.getWitnessSet() == null
                        || tx.getWitnessSet().getVkeyWitnesses() == null
                        || tx.getWitnessSet().getVkeyWitnesses().isEmpty(),
                "this transaction carries a signature — it must be handed over UNSIGNED");

        String cbor = tx.serializeToHex();
        Files.writeString(Path.of(System.getenv("BUILD_STAKE_REG")), cbor);
        System.out.println("UNSIGNED-REGISTRATION-CBOR " + cbor);
        System.out.println("UNSIGNED-REGISTRATION-FEE " + tx.getBody().getFee());
        System.out.println("UNSIGNED-REGISTRATION-SIZE " + tx.serialize().length);
    }
}
