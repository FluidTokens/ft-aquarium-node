package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.BuiltTransaction;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Plan;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.PublishedScript;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.UnspendableDestination;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Validator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes the <b>four compound-path validators</b> as reference scripts on preview.
 *
 * <h2>Why</h2>
 * A compound invokes eleven validators — 22,689 bytes of them — in a 24,878-byte transaction against
 * a 16,384 {@code max_tx_size}. It is a phase-1 {@code MaxTxSizeUTxO} rejection and cannot succeed as
 * built (findings §22.9). Referencing the four largest brings it to ~10,400 bytes.
 *
 * <p><b>Four and not eleven.</b> Each publication locks min-ada permanently, so margin costs money:
 * four buys ~5,958 bytes of headroom for ~65.7 ada, eleven buys 13,799 for ~105.4. Two would fit at
 * 324 bytes of margin, which is about nine extra transaction inputs — a transaction that fits today
 * and not next week.
 *
 * <h2>Destination</h2>
 * The same {@link UnspendableDestination} the liquidation publications used — an always-fails script
 * address. **Coin selection cannot spend it because there is no key to sign with**, which closes CCL
 * trap 9b structurally rather than by a guard someone has to remember. The two existing published
 * scripts already sit there.
 *
 * <h2>⛔ Publishing the wrong script cannot be undone</h2>
 * Every hash is asserted against what the LIVE config datums publish, before anything is built:
 * three from the main config (fields 7, 25, 27) and one from the LM config (field 3). A wrong script
 * is tens of ada locked behind a coordinate no verifier will accept.
 *
 * <p>Quadruple-gated, and additionally honours {@code AQUARIUM_PUBLISH_DRY_RUN=true}, which builds
 * and validates everything against the real chain and withholds only the signature and the
 * submission — so the measured min-ada and fee can be read before committing to a spend.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+",
        disabledReason = "publishing needs the funding wallet")
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "publishing needs a backend")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_PUBLISH_REFERENCE_SCRIPTS", matches = "true",
        disabledReason = "publishing is a deliberate one-off act, never a side effect of a test run")
@EnabledIfEnvironmentVariable(named = "SUBMITTABLE_NETWORK", matches = "preview",
        disabledReason = "preview only")
class CompoundReferenceScriptPublishSubmitTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";

    private static final String CONFIG_POLICY_ID = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    private static final String LM_CONFIG_POLICY_ID = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /** What the LIVE config datums publish. Read 2026-09-02; the acceptance criterion for publishing. */
    private static final Map<Validator, String> ON_CHAIN = new LinkedHashMap<>(Map.of(
            Validator.LM_COMPOUND_ACTION, "dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457",
            Validator.POOL_COMPOUND_ACTION, "33128ca352b5472f593104d5884ced5cba5e980b3177353eb2116c62",
            Validator.ASSET_MANAGER, "19d8576594820b4c94d3cb2c4010ca563c6ef7b2c31e5175d7f8ec6e",
            Validator.POOL_MANAGER, "45ce890c9bcf70f6eed629b5db7c0622e44ca1003e001a2cf951518f"));

    private static Network preview() {
        return Networks.preview();
    }

    /** scriptHash → {@code txHash#index} for everything already published at the destination. */
    private static Map<String, String> publishedAt(BFBackendService backend, String address)
            throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        var result = backend.getUtxoService().getUtxos(address, 100, 1);
        if (result == null || !result.isSuccessful() || result.getValue() == null) {
            return found;
        }
        result.getValue().forEach(utxo -> {
            String hash = utxo.getReferenceScriptHash();
            if (hash != null && !hash.isBlank()) {
                found.put(hash, utxo.getTxHash() + "#" + utxo.getOutputIndex());
            }
        });
        return found;
    }

    /** Poll until the submitted transaction is visible on chain, or give up loudly. */
    private static void awaitConfirmation(BFBackendService backend, String txHash) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            Thread.sleep(10_000L);
            var result = backend.getTransactionService().getTransaction(txHash);
            if (result != null && result.isSuccessful() && result.getValue() != null) {
                log.info("PUBLISH CONFIRMED {} after ~{}s", txHash, (attempt + 1) * 10);
                return;
            }
        }
        throw new AssertionError("submitted " + txHash + " but it never confirmed; do NOT re-run "
                + "blindly — check the chain first, or the next build may double-publish");
    }

    @Test
    void publishesTheFourCompoundValidatorsForReal() throws Exception {
        String mnemonic = System.getenv("WALLET_MNEMONIC");   // never logged, never printed
        Account wallet = new Account(preview(), mnemonic);
        String funder = wallet.baseAddress();

        BFBackendService backend = new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY"));
        UtxoSupplier utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());
        ProtocolParamsSupplier paramsSupplier = new DefaultProtocolParamsSupplier(backend.getEpochService());

        LoansContractRegistry registry = new LoansContractRegistry(
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);

        ReferenceScriptPublisher publisher =
                new ReferenceScriptPublisher(registry, utxoSupplier, paramsSupplier);

        // ⛔ Prove WHICH scripts these are before building anything. A wrong publication is ada
        // locked forever behind a coordinate no verifier will accept.
        ON_CHAIN.forEach((validator, expected) -> assertEquals(expected,
                publisher.scriptHashOf(validator),
                "the derived " + validator.configKey() + " does not match what the live config "
                        + "publishes — wrong blueprint or wrong coordinates; do NOT publish"));

        UnspendableDestination unspendable = UnspendableDestination.forNetwork(preview());
        String destination = unspendable.address();

        log.info("PUBLISH destination (unspendable always-fails, preview): {}", destination);
        log.info("PUBLISH funder + change: {}", funder);

        boolean dryRun = "true".equals(System.getenv("AQUARIUM_PUBLISH_DRY_RUN"));
        long totalLocked = 0;
        long totalFees = 0;

        // ⛔ IDEMPOTENCE. The first run of this published asset-manager and then failed on the next
        // with "All inputs are spent" — Blockfrost had not yet reflected the change output, so the
        // second build selected an input the first had already consumed. A re-run must therefore
        // NOT republish what is already on chain: that would lock a second min-ada behind a
        // duplicate coordinate, and nothing would ever notice. Read the destination first.
        Map<String, String> alreadyPublished = publishedAt(backend, destination);
        alreadyPublished.forEach((hash, coord) ->
                log.info("PUBLISH ALREADY ON CHAIN {} at {}", hash, coord));

        // One script per transaction: a failure then costs one publication, not four, and each
        // coordinate is unambiguous in the log.
        for (Validator validator : ON_CHAIN.keySet()) {
            String existing = alreadyPublished.get(ON_CHAIN.get(validator));
            if (existing != null) {
                log.info("PUBLISH SKIP {} — already published at {}", validator.configKey(), existing);
                log.info("PUBLISH COORDINATE {}={}", validator.configKey(), existing);
                continue;
            }
            List<BuiltTransaction> built = publisher.build(Plan.of(validator), destination, funder);
            assertEquals(1, built.size(), "one script must be one transaction");
            BuiltTransaction tx = built.getFirst();
            PublishedScript published = tx.published().getFirst();

            assertEquals(validator, published.validator());
            assertEquals(ON_CHAIN.get(validator), published.scriptHash());
            assertTrue(tx.sizeBytes() < ReferenceScriptPublisher.MAX_TX_SIZE,
                    "publishing transaction is over maxTxSize: " + tx.sizeBytes());

            log.info("PUBLISH {} -> hash {} ({} body bytes), min-ada {} lovelace, fee {}, size {}",
                    validator.configKey(), published.scriptHash(), published.scriptBodyBytes(),
                    published.lovelace(), tx.feeLovelace(), tx.sizeBytes());
            totalLocked += published.lovelace();
            totalFees += tx.feeLovelace();

            if (dryRun) {
                log.info("PUBLISH DRY RUN — {} built and validated, NOT signed, NOT submitted",
                        validator.configKey());
                continue;
            }

            Transaction signed = wallet.sign(tx.transaction());
            Result<String> result = backend.getTransactionService().submitTransaction(signed.serialize());
            if (!result.isSuccessful()) {
                log.error("PUBLISH SUBMIT FAILED for {}: {}", validator.configKey(), result.getResponse());
                throw new AssertionError("submission rejected for " + validator.configKey()
                        + ": " + result.getResponse());
            }
            log.info("PUBLISH SUBMITTED {} txHash={}", validator.configKey(), result.getValue());
            log.info("PUBLISH COORDINATE {}={}#0", validator.configKey(), result.getValue());

            // ⚠ Wait for CONFIRMATION, not for a duration. A blind sleep is a bet on indexer lag,
            // and the first run lost that bet: the next build selected an input the previous
            // transaction had already spent, and the node answered "All inputs are spent".
            awaitConfirmation(backend, result.getValue());
        }

        log.info("PUBLISH TOTAL locked {} lovelace ({} ada), fees {} lovelace ({} ada)",
                totalLocked, totalLocked / 1_000_000d, totalFees, totalFees / 1_000_000d);
    }
}
