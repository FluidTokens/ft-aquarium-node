package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Reshapes the bot's wallet so it can build again.</b>
 *
 * <h2>Why this is needed</h2>
 * After publishing the four compound reference scripts (§22.10) the wallet held exactly one UTxO:
 * 9,898,050,234 lovelace <b>and</b> 20,000,000 tFLDT. Nine thousand ada, and not a lovelace of it
 * usable — Plutus collateral must be pure ada, so {@code WalletInputSelection.nominable} correctly
 * refused it and every compound was skipped. CCL trap 17: a successful transaction's change output
 * disables the builder that made it (§22.13).
 *
 * <h2>The shape, and why the token output is the durable part</h2>
 * While the tFLDT rides the wallet's only large UTxO, <b>every</b> transaction needing a large input
 * drags it into change and re-creates the trapped state; the ada-only outputs get consumed and never
 * regenerate. Isolating the token once means change comes back ada-only <b>by construction</b>, which
 * is what the liquidation path already relies on (`docs/change-output-enumeration.md`).
 *
 * <p>One self-send producing: one output carrying all the tFLDT at its exact
 * {@link MinAdaCalculator} minimum, five ada-only outputs of 20 ada, and ada-only change.
 *
 * <p>Authorised first-hand by Giovanni ("do the self send"). Gated exactly as the publication runner
 * is, and honours {@code AQUARIUM_PUBLISH_DRY_RUN=true}.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+", disabledReason = "needs the wallet")
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+", disabledReason = "needs a backend")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_RESHAPE_WALLET", matches = "true",
        disabledReason = "reshaping is a deliberate one-off act, never a side effect of a test run")
@EnabledIfEnvironmentVariable(named = "SUBMITTABLE_NETWORK", matches = "preview", disabledReason = "preview only")
class WalletReshapeSubmitTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";
    private static final String TFLDT =
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f620014df1074464c4454";

    private static final int ADA_ONLY_OUTPUTS = 5;
    private static final long ADA_ONLY_LOVELACE = 20_000_000L;

    @Test
    void reshapesTheWalletSoCollateralIsAvailableAgain() throws Exception {
        Account wallet = new Account(Networks.preview(), System.getenv("WALLET_MNEMONIC"));
        String bot = wallet.baseAddress();

        BFBackendService backend = new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY"));
        UtxoSupplier utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());
        ProtocolParamsSupplier paramsSupplier = new DefaultProtocolParamsSupplier(backend.getEpochService());

        List<Utxo> before = backend.getUtxoService().getUtxos(bot, 100, 1).getValue();
        long tokenHeld = before.stream().flatMap(u -> u.getAmount().stream())
                .filter(a -> TFLDT.equals(a.getUnit()))
                .mapToLong(a -> a.getQuantity().longValue()).sum();
        long adaOnlyBefore = before.stream().filter(u -> u.getAmount().size() == 1).count();

        log.info("RESHAPE before: {} utxo(s), {} ada-only, {} tFLDT held",
                before.size(), adaOnlyBefore, tokenHeld);
        assertTrue(tokenHeld > 0, "no tFLDT in the wallet — re-read the state before reshaping");

        // The token output is sized by the ledger's own calculator, never guessed: too little is
        // rejected, and too much is ada parked where it cannot serve as collateral.
        var params = paramsSupplier.getProtocolParams();
        TransactionOutput tokenOutput = TransactionOutput.builder()
                .address(bot)
                .value(Value.builder().coin(BigInteger.ZERO)
                        .multiAssets(List.of(com.bloxbean.cardano.client.transaction.spec.MultiAsset.builder()
                                .policyId(TFLDT.substring(0, 56))
                                .assets(List.of(com.bloxbean.cardano.client.transaction.spec.Asset.builder()
                                        .name("0x" + TFLDT.substring(56))
                                        .value(BigInteger.valueOf(tokenHeld)).build()))
                                .build()))
                        .build())
                .build();
        BigInteger tokenMinAda = new MinAdaCalculator(params).calculateMinAda(tokenOutput);
        log.info("RESHAPE token output min-ada: {} lovelace", tokenMinAda);

        Tx tx = new Tx();
        // ⚠ TWO-ARG Amount.asset: the three-arg overload hex-encodes the asset name (CCL trap 3),
        // and this name is ALREADY hex. Using it would send a differently-named, non-existent asset.
        tx.payToAddress(bot, List.of(Amount.lovelace(tokenMinAda), Amount.asset(TFLDT,
                BigInteger.valueOf(tokenHeld))));
        for (int i = 0; i < ADA_ONLY_OUTPUTS; i++) {
            tx.payToAddress(bot, List.of(Amount.lovelace(BigInteger.valueOf(ADA_ONLY_LOVELACE))));
        }
        tx.from(bot);

        Transaction built = new QuickTxBuilder(utxoSupplier, paramsSupplier, null)
                .compose(tx)
                .feePayer(bot)
                // ⛔ WITHOUT THIS THE WHOLE RESHAPE IS A NO-OP. mergeOutputs defaults to TRUE, so
                // six outputs to one address collapse into one — measured: the first dry run built a
                // single token-bearing output and zero ada-only ones, which is precisely the state
                // being repaired. The transaction would have been valid, cheap, and useless.
                .mergeOutputs(false)
                .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                .preBalanceTx((ctx, txn) ->
                        ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)))
                .build();

        // Verify the SHAPE on the built body, not on what was asked for.
        long adaOnlyOut = built.getBody().getOutputs().stream()
                .filter(o -> bot.equals(o.getAddress()))
                .filter(o -> o.getValue().getMultiAssets() == null
                        || o.getValue().getMultiAssets().isEmpty())
                .count();
        long tokenOuts = built.getBody().getOutputs().stream()
                .filter(o -> o.getValue().getMultiAssets() != null
                        && !o.getValue().getMultiAssets().isEmpty())
                .count();

        log.info("RESHAPE built: {} outputs, {} ada-only, {} token-bearing, fee {}, size {}",
                built.getBody().getOutputs().size(), adaOnlyOut, tokenOuts,
                built.getBody().getFee(), built.serialize().length);

        assertEquals(1, tokenOuts, "exactly ONE output may carry the token — that is the whole point");
        assertTrue(adaOnlyOut >= ADA_ONLY_OUTPUTS,
                "expected at least " + ADA_ONLY_OUTPUTS + " ada-only outputs, got " + adaOnlyOut);

        if ("true".equals(System.getenv("AQUARIUM_PUBLISH_DRY_RUN"))) {
            log.info("RESHAPE DRY RUN — built and validated, NOT signed, NOT submitted");
            return;
        }

        Result<String> result = backend.getTransactionService()
                .submitTransaction(wallet.sign(built).serialize());
        if (!result.isSuccessful()) {
            log.error("RESHAPE SUBMIT FAILED: {}", result.getResponse());
            throw new AssertionError("submission rejected: " + result.getResponse());
        }
        log.info("RESHAPE SUBMITTED txHash={}", result.getValue());
    }
}
