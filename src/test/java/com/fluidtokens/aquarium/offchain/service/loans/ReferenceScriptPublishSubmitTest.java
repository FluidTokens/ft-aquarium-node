package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.BuiltTransaction;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Plan;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.PublishedScript;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.UnspendableDestination;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Validator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes the {@code loan_claim_action} reference script to preview — <b>for real</b>.
 *
 * <h2>Why this exists as a separate runner</h2>
 * {@code ReferenceScriptPublishRunnerTest} builds and prints and deliberately cannot submit: it holds
 * synthetic UTxOs, fixture protocol params, a fixture registry and no signer. That is the right shape
 * for proving the builder. It is the wrong shape for actually publishing, and the gap is not a
 * formality — it is a real registry, a real UTxO set, real protocol params, a signature and a
 * submission.
 *
 * <h2>Why one script and not the shipped minimum split</h2>
 * The plan here is {@code of(LOAN_CLAIM_ACTION)}, not {@code minimumSplit()}. Measured on the fourth
 * deployment, shedding this one 8,662-byte validator is sufficient on both liquidation paths — plain
 * Liquidate 20,342 → 11,713 bytes and the convert path 23,459 → 14,794, against a 16,384 limit. The
 * other validator the convert path references, the oracle at {@code 402c984d…}, is <b>already
 * published by FluidTokens</b> and appears as a reference input in their own borrow transactions, so
 * it is not ours to publish. Publishing more than this would lock ada for nothing.
 *
 * <h2>⚠ The change address is the FUNDER, deliberately, and this differs from the print-only runner</h2>
 * That runner sends change to wallet index 1 to keep it away from the destination. Here the funder is
 * also the running bot's wallet, and its <em>only</em> clean ada-only UTxO is the one this transaction
 * spends. Sending the change elsewhere would leave the bot holding nothing but the dead 08-17
 * reference-script output — which {@code adaOnlyWalletUtxo()} correctly refuses — and the bot would go
 * back to reporting "no ada-only wallet utxo" immediately after we fixed the reason it could not
 * build. Change returns to index 0 so the bot keeps a spendable input.
 *
 * <h2>⚠ What this spends is not recoverable</h2>
 * The reference-script output is paid to the enterprise address of an always-fails PlutusV3 script.
 * Nobody can ever spend it, by construction — that is the point, since the coordinate must outlive
 * whoever holds this wallet's key. The locked min-ada is gone permanently. Preview only; the
 * destination refuses to construct itself on mainnet.
 *
 * <p>Triple-gated, and every gate must be set deliberately:
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   AQUARIUM_PUBLISH_REFERENCE_SCRIPTS=true SUBMITTABLE_NETWORK=preview \
 *     ./gradlew cleanTest test --tests '*ReferenceScriptPublishSubmitTest*'
 * }</pre>
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+",
        disabledReason = "no wallet")
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "no blockfrost key")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_PUBLISH_REFERENCE_SCRIPTS", matches = "true",
        disabledReason = "publishing is opt-in and spends real preview ada, permanently")
@EnabledIfEnvironmentVariable(named = "SUBMITTABLE_NETWORK", matches = "preview",
        disabledReason = "submission is preview-only")
class ReferenceScriptPublishSubmitTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";

    /** The FOURTH deployment. Same values application.yaml ships; not a fixture. */
    private static final String CONFIG_POLICY_ID = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    private static final String LM_CONFIG_POLICY_ID = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /**
     * What the live ConfigDatum[11] reports for this deployment's {@code loan_claim_action}. Asserting
     * it before submitting is the difference between publishing the right script and locking ada
     * behind the wrong one for ever — the old {@code 9ae63b26…} is exactly such a mistake, already
     * made once by a redeploy.
     */
    private static final String EXPECTED_LOAN_CLAIM_ACTION_HASH =
            "c6e0c4395cf22e08f918ca996d7db49faba793dbd6b647160168ff39";

    @Test
    void publishesLoanClaimActionForReal() throws Exception {
        String mnemonic = System.getenv("WALLET_MNEMONIC");   // never logged, never printed
        Account walletA = new Account(preview(), mnemonic);
        String funder = walletA.baseAddress();

        BFBackendService backend = new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY"));
        UtxoSupplier utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());
        ProtocolParamsSupplier paramsSupplier = new DefaultProtocolParamsSupplier(backend.getEpochService());

        LoansContractRegistry registry = new LoansContractRegistry(
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);

        // Publishing the wrong script is the one mistake that cannot be undone here, so prove which
        // script this is BEFORE anything is built, against the value read off the chain.
        assertEquals(EXPECTED_LOAN_CLAIM_ACTION_HASH, registry.getLoanClaimActionScriptHash(),
                "the derived loan_claim_action does not match the live ConfigDatum — wrong blueprint "
                        + "or wrong coordinates; do NOT publish");

        UnspendableDestination unspendable = UnspendableDestination.forNetwork(preview());
        String destination = unspendable.address();

        // Change to the FUNDER: see the class javadoc. The bot needs a clean ada-only utxo after this.
        ReferenceScriptPublisher publisher =
                new ReferenceScriptPublisher(registry, utxoSupplier, paramsSupplier);
        List<BuiltTransaction> built = publisher.build(Plan.of(Validator.LOAN_CLAIM_ACTION),
                destination, funder);

        assertEquals(1, built.size(), "one script must be one transaction");
        BuiltTransaction tx = built.getFirst();
        assertEquals(1, tx.published().size(), "exactly one script may be published here");
        PublishedScript published = tx.published().getFirst();
        assertEquals(Validator.LOAN_CLAIM_ACTION, published.validator());
        assertEquals(EXPECTED_LOAN_CLAIM_ACTION_HASH, published.scriptHash());
        assertTrue(tx.sizeBytes() < ReferenceScriptPublisher.MAX_TX_SIZE,
                "publishing transaction is over maxTxSize: " + tx.sizeBytes());

        log.info("PUBLISH destination (unspendable always-fails, preview): {}", destination);
        log.info("PUBLISH destination script hash: {}", unspendable.scriptHash());
        log.info("PUBLISH funder + change (wallet A index 0): {}", funder);
        log.info("PUBLISH script {} -> hash {} ({} body bytes)", published.validator().configKey(),
                published.scriptHash(), published.scriptBodyBytes());
        log.info("PUBLISH MEASURED min-ada (MinAdaCalculator, live params): {} lovelace ({} ada)",
                published.lovelace(), published.lovelace() / 1_000_000d);
        log.info("PUBLISH fee: {} lovelace ({} ada)", tx.feeLovelace(), tx.feeLovelace() / 1_000_000d);
        log.info("PUBLISH tx size: {} bytes", tx.sizeBytes());

        if ("true".equals(System.getenv("AQUARIUM_PUBLISH_DRY_RUN"))) {
            // Everything above is the real build against the real chain; only the signature and the
            // submission are withheld. The point is to read the MEASURED min-ada and fee before
            // committing to a spend that cannot be undone.
            log.info("PUBLISH DRY RUN — built and validated, NOT signed, NOT submitted");
            return;
        }

        Transaction signed = walletA.sign(tx.transaction());
        byte[] signedBytes = signed.serialize();
        log.info("PUBLISH signed size: {} bytes", signedBytes.length);

        Result<String> result = backend.getTransactionService().submitTransaction(signedBytes);
        if (!result.isSuccessful()) {
            log.error("PUBLISH SUBMIT FAILED: {}", result.getResponse());
            throw new AssertionError("submission rejected: " + result.getResponse());
        }
        String txHash = result.getValue();
        log.info("PUBLISH SUBMITTED txHash={}", txHash);
        log.info("PUBLISH COORDINATE for AQUARIUM_LIQUIDATION_REF_LOAN_CLAIM_ACTION = {}#0", txHash);
        log.info("PUBLISH the locked {} lovelace is at an always-fails address and is NOT recoverable",
                published.lovelace());
    }
}
