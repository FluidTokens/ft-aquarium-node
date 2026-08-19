package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.BuiltTransaction;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Plan;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.PublishedScript;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Manual runner: builds the two reference-script publishing transactions for the wallet behind
 * {@code WALLET_MNEMONIC} and <b>prints them</b>. In the shape of this repo's other manual deploy
 * scripts ({@code PreviewTankTest}, {@code PreviewParametersTest}), which are likewise ordinary
 * JUnit classes gated on an environment variable and skipped in an ordinary build.
 *
 * <h2>It does not submit, and it cannot</h2>
 * {@link ReferenceScriptPublisher} holds a null {@code TransactionProcessor}, this class holds no
 * {@code BackendService} and never signs anything, and the transactions printed below are
 * unsigned. <b>Submission is a separate, later, deliberate change, made with Giovanni present</b>
 * — it is out of scope for this slice on purpose: the transactions lock ~86.8 ada and their
 * outputs are what every future liquidation depends on, so publishing them is a human act, taken
 * once, with the coordinates recorded.
 *
 * <h2>Two gates, not one</h2>
 * {@code WALLET_MNEMONIC} alone is not enough: {@code .env.preview} sets it for every live test in
 * this repo, and merely having sourced that file must never put a wallet on the path of an
 * 86-ada transaction. {@code AQUARIUM_PUBLISH_REFERENCE_SCRIPTS=true} is the second, explicit
 * opt-in.
 *
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   AQUARIUM_PUBLISH_REFERENCE_SCRIPTS=true \
 *     ./gradlew cleanTest test --tests '*ReferenceScriptPublishRunnerTest' -i
 * }</pre>
 *
 * <h2>What the inputs will be, and what they will not</h2>
 * The funding UTxOs here are <b>synthetic</b> — this class has no chain access by design, so it
 * cannot resolve wallet A's real UTxO set. Every number that depends only on the outputs (each
 * script's bytes, each output's min-ada, the total to fund) is exact. The two transaction
 * <em>sizes</em> and <em>fees</em> depend on how many real inputs coin selection ends up
 * consuming and will move by a few hundred bytes and a few thousand lovelace once real UTxOs are
 * resolved. Resolving them belongs to the submission change.
 *
 * <h2>The locked ada is now permanent, by design</h2>
 * The reference-script outputs are paid to an <b>unspendable</b> destination — the enterprise
 * address of an always-fails PlutusV3 script ({@code ReferenceScriptPublisher.UnspendableDestination}) —
 * so the ~85 ada they lock is <b>not recoverable</b>. That is the point: the coordinates outlive this
 * project and their permanence rests on the ledger rather than on custody of a wallet key. A reference
 * input is never spent, so locking the ada permanently costs nothing the liquidation path needs.
 *
 * <h2>Measured 2026-08-17, three synthetic 60-ada inputs (earlier BASE-address destination)</h2>
 * <pre>
 *   TX1  loanClaimAction + lmLiquidateAction    13_164 bytes  fee 743_705  locks 57_603_150
 *   TX2  the other four                          6_262 bytes  fee 440_017  locks 29_234_730
 *   total locked 86_837_880 lovelace (86.83788 ada)
 * </pre>
 * These figures predate repointing the destination to the 29-byte enterprise address; that smaller
 * output lowers each script's min-ada, so the enterprise-destination totals are slightly less. The
 * exact per-output figures are pinned in {@code ReferenceScriptPublisherTest} against the live params;
 * the runner logs its own at build time.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+",
        disabledReason = "manual publishing script: needs wallet A's mnemonic, "
                + "run with `set -a; . ./.env.preview; set +a`")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_PUBLISH_REFERENCE_SCRIPTS", matches = "true",
        disabledReason = "second, explicit opt-in: having a mnemonic set must never on its own "
                + "put a wallet on the path of an ~86 ada transaction")
public class ReferenceScriptPublishRunnerTest {

    /** Enough synthetic ada to cover the largest transaction; see the class javadoc. */
    private static final long SYNTHETIC_UTXO_LOVELACE = 60_000_000L;

    /**
     * Wallet A's next external address. The change goes here rather than to the destination — see
     * the change-address warning on {@code ReferenceScriptPublisher.build(Plan, String, String,
     * String)}. Same account, same mnemonic, so the change stays in wallet A and any BIP-44 wallet
     * shows it.
     */
    private static final int CHANGE_ADDRESS_INDEX = 1;

    @Test
    public void printThePublishingTransactions() {
        // Wallet A. The mnemonic itself is never logged, printed or otherwise emitted — only the
        // address it derives, which is public and is the coordinate everything else will refer to.
        String mnemonic = System.getenv("WALLET_MNEMONIC");
        Account walletA = new Account(preview(), mnemonic);
        String funder = walletA.baseAddress();
        // The reference-script outputs are paid to a destination we PROVABLY do not control — the
        // enterprise address of an always-fails PlutusV3 script — rather than back to wallet A. The
        // coordinates outlive this project and their permanence should rest on the ledger, not on
        // whoever holds wallet A's key later. Fail-closed on mainnet; this runner is preview only.
        ReferenceScriptPublisher.UnspendableDestination unspendable =
                ReferenceScriptPublisher.UnspendableDestination.forNetwork(preview());
        String destination = unspendable.address();
        // The change must not land at the destination: cardano-client-lib would then take the fee
        // out of the largest output there — a reference-script output — and top it back up with a
        // whole extra UTxO. The next address on wallet A's own derivation path is the same
        // account, well inside any wallet's address gap limit, and is not the destination.
        String change = new Account(preview(), mnemonic, CHANGE_ADDRESS_INDEX).baseAddress();

        // Synthetic funding UTxOs sit at the FUNDER (wallet A): we cannot fund from the destination,
        // whose key nobody holds. See the class javadoc — the real UTxOs are resolved at submission.
        List<Utxo> synthetic = List.of(
                LoanFixtures.adaUtxo("aa".repeat(32), 0, funder, SYNTHETIC_UTXO_LOVELACE),
                LoanFixtures.adaUtxo("bb".repeat(32), 0, funder, SYNTHETIC_UTXO_LOVELACE),
                LoanFixtures.adaUtxo("cc".repeat(32), 0, funder, SYNTHETIC_UTXO_LOVELACE));

        ReferenceScriptPublisher publisher = new ReferenceScriptPublisher(
                LoanFixtures.registry(), LoanFixtures.utxoSupplier(synthetic),
                LoanFixtures.protocolParams());

        List<BuiltTransaction> built =
                publisher.build(Plan.minimumSplit(), destination, funder, change);
        assertEquals(2, built.size(), "the minimum split is two transactions");

        log.info("destination address (unspendable always-fails script, preview): {}", destination);
        log.info("destination script hash (always-fails; the locked ada is NOT recoverable): {}",
                unspendable.scriptHash());
        log.info("funder address (wallet A index 0, preview): {}", funder);
        log.info("change address (wallet A index {}, preview): {}", CHANGE_ADDRESS_INDEX, change);

        long totalMinAda = 0L;
        long totalFee = 0L;
        for (int i = 0; i < built.size(); i++) {
            BuiltTransaction tx = built.get(i);
            totalMinAda += tx.lockedLovelace();
            totalFee += tx.feeLovelace();

            log.info("TX{}  {} bytes, fee {} lovelace, locks {} lovelace",
                    i + 1, tx.sizeBytes(), tx.feeLovelace(), tx.lockedLovelace());
            for (PublishedScript published : tx.published()) {
                log.info("   {} -> script hash {} ({} body bytes, {} lovelace)",
                        published.validator().configKey(), published.scriptHash(),
                        published.scriptBodyBytes(), published.lovelace());
            }
            log.info("TX{} unsigned cbor: {}", i + 1, HexUtil.encodeHexString(tx.cbor()));
        }

        log.info("TOTAL locked {} lovelace ({} ada) + fees {} lovelace; the locked ada sits at an "
                        + "unspendable always-fails address and is NOT recoverable — permanent by design",
                totalMinAda, totalMinAda / 1_000_000d, totalFee);
        log.info("NOTHING WAS SUBMITTED. This runner has no backend and no signer; the "
                + "coordinates for application.yaml come from a real publication, later.");
    }
}
