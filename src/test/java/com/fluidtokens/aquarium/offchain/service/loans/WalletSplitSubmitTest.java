package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splits the bot's own preview wallet into nominable outputs — <b>for real</b>.
 *
 * <h2>Why this exists</h2>
 * On 2026-08-25 a successful liquidation returned the bot's change as a single output carrying both
 * the ada and the collateral tokens it had just received. {@code WalletInputSelection.nominable()}
 * correctly refuses it (route 5: a token-bearing spend input puts tokens in change, which disables
 * the wallet on the next cycle) — so <b>9,964.99 ADA has been stranded behind 5,000,000 tFLDT for
 * two days.</b> The bot was never short of ada; it was blocked.
 * <p>
 * T-056 fixed the CAUSE — the liquidation fee is now paid out by name, so change comes back ada-only
 * by construction. <b>This clears the wreckage the fix could not reach backwards.</b>
 *
 * <h2>⚠ What makes this succeed or waste a fee</h2>
 * {@code mergeOutputs(false)} is load-bearing: cardano-client-lib merges outputs to the same address
 * by default, and two 5-ada payments to one address would come back as a single 10-ada output. That
 * would still be nominable, but it would not be the two inputs the ask was for.
 * <p>
 * <b>And the outputs must be ada-only, datum-free and reference-script-free</b>, or
 * {@code nominable()} refuses them and the fee bought nothing. This asserts that <b>from the chain</b>
 * after submission, using the very predicate the bot uses — never a hand-written equivalent, which is
 * exactly where the two would silently diverge.
 *
 * <h2>Not at risk</h2>
 * The wallet holds no reference-script UTxO: the published {@code loan_claim_action} lives at an
 * always-fails enterprise address by construction, and the 2026-08-17 publication that <em>was</em>
 * here was consumed on 2026-08-25. Verified field-by-field before this ran, not assumed.
 *
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   AQUARIUM_SPLIT_WALLET=true ./gradlew cleanTest test --tests '*WalletSplitSubmitTest*'
 * }</pre>
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+", disabledReason = "no wallet")
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+", disabledReason = "no key")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_SPLIT_WALLET", matches = "true",
        disabledReason = "spends real preview ada; opt-in only")
class WalletSplitSubmitTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";

    /** Two of these: the spend gate needs 2,607,027 and the collateral gate 3,910,541. */
    private static final BigInteger FIVE_ADA = BigInteger.valueOf(5_000_000L);

    @Test
    void splitsTheWalletIntoTwoNominableOutputs() throws Exception {
        Account account = new Account(preview(), System.getenv("WALLET_MNEMONIC"));  // never logged
        String self = account.baseAddress();
        BFBackendService backend = new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY"));
        var utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());

        List<Utxo> before = utxoSupplier.getAll(self);
        log.info("SPLIT wallet {} holds {} utxo(s) before", self, before.size());
        for (Utxo u : before) {
            log.info("SPLIT   before {}#{} assets={} refScript={} inlineDatum={} dataHash={}",
                    u.getTxHash(), u.getOutputIndex(), u.getAmount().size(),
                    u.getReferenceScriptHash(), u.getInlineDatum() != null, u.getDataHash());
            assertEquals(null, u.getReferenceScriptHash(),
                    "REFUSING TO PROCEED: a wallet utxo carries a reference script. Spending it would "
                            + "destroy the script, because script_ref is NOT carried into change.");
        }
        long nominableBefore = before.stream().filter(WalletInputSelection::nominable).count();
        log.info("SPLIT nominable before: {}", nominableBefore);

        Tx tx = new Tx()
                .payToAddress(self, List.of(Amount.lovelace(FIVE_ADA)))
                .payToAddress(self, List.of(Amount.lovelace(FIVE_ADA)))
                .from(self);

        Result<String> result = new QuickTxBuilder(backend)
                .compose(tx)
                // ⛔ Load-bearing: without it the two payments merge into one 10-ada output.
                .mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(account))
                .completeAndWait(msg -> log.info("SPLIT {}", msg));

        assertTrue(result.isSuccessful(), "submission rejected: " + result.getResponse());
        String txHash = result.getValue();
        log.info("SPLIT SUBMITTED txHash={}", txHash);

        List<Utxo> after = utxoSupplier.getAll(self);
        log.info("SPLIT wallet holds {} utxo(s) after", after.size());
        for (Utxo u : after) {
            log.info("SPLIT   after {}#{} lovelace={} assets={} nominable={}",
                    u.getTxHash(), u.getOutputIndex(),
                    u.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit()))
                            .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO),
                    u.getAmount().size(), WalletInputSelection.nominable(u));
        }

        // THE POINT OF THE EXERCISE, ASSERTED FROM THE CHAIN AND WITH THE BOT'S OWN PREDICATE.
        List<Utxo> nominable = after.stream().filter(WalletInputSelection::nominable).toList();
        assertTrue(nominable.size() >= 2,
                "the split must leave at least two NOMINABLE utxos, else the fee bought nothing; got "
                        + nominable.size());
        assertTrue(nominable.stream().filter(u -> u.getAmount().getFirst().getQuantity()
                        .compareTo(BigInteger.valueOf(3_910_541L)) >= 0).count() >= 2,
                "both new outputs must clear the COLLATERAL ceiling (3,910,541), which is the larger "
                        + "of the two gates — clearing only the fee ceiling is the mistake T-061 exists "
                        + "to stop us making");
    }
}
