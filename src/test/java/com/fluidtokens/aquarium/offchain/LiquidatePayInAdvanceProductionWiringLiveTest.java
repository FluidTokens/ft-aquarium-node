package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AccountConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LenderManagerDatumConverter;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanDatumConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>T-039 — the production-wired build.</b> Every other test of the pay-in-advance builder uses the
 * offline constructors and an offline rig. This one uses the <b>production</b> constructor against
 * <b>live preview</b>, because the delta between them cannot be reached offline and that delta is
 * exactly what failed on 2026-08-21:
 * <ul>
 *   <li>{@code new QuickTxBuilder(backendService)} — the backend's own script supplier, which is what
 *       resolves a validator that exists on chain only as a <b>reference script</b>. The offline rigs
 *       instead take the {@code withScriptSupplier(empty)} branch and are <em>handed</em> the scripts
 *       through {@code withReferenceScripts}, so they prove the validators and say nothing about
 *       resolution;</li>
 *   <li>Blockfrost's {@code /utils/txs/evaluate} as the evaluator, whose protocol parameters and cost
 *       models are the chain's by construction (CCL trap 7);</li>
 *   <li>the real ledger fee, including the reference-script surcharge, which no offline rig computes
 *       (CCL trap 11).</li>
 * </ul>
 *
 * <h2>This test cannot submit, and that is structural rather than a promise</h2>
 * It builds and asserts; it never signs and never transmits. The claim rests on three facts a reader
 * can check rather than on this paragraph:
 * <ol>
 *   <li>{@link LiquidatePayInAdvanceTransactionBuilder#build} returns an <b>unsigned</b>
 *       {@link Transaction}. No signing key is constructed here — the {@link Account} below is
 *       derived only to learn the bot's <em>address</em>, and its {@code sign} is never called;</li>
 *   <li>the only code in this repository that transmits a liquidation is
 *       {@code LiquidationExecutor.submit}, reached only through {@code LiquidationExecutor.cycle}.
 *       This test constructs no executor;</li>
 *   <li>{@code BFBackendService} <em>can</em> submit — that is unavoidable, it is the same object the
 *       builder needs for its script supplier — so the honest statement is that <b>nothing here calls
 *       it</b>, and the surrounding facts are what make that checkable.</li>
 * </ol>
 * A grep for {@code submit} in this file returning nothing but this javadoc is the check.
 *
 * <h2>Skipping is a real outcome, not a failure</h2>
 * Needs {@code BLOCKFROST_KEY} and {@code WALLET_MNEMONIC} ({@code set -a; . ./.env.preview; set +a}),
 * and needs the f855 loan still unspent and the bot's wallet holding a spendable ada-only UTxO. When a
 * precondition is absent the test <b>aborts with the reason</b> rather than passing quietly: a green
 * that exercised nothing is the failure mode this suite exists to avoid.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "live preview check: run with `set -a; . ./.env.preview; set +a`")
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+",
        disabledReason = "needs the bot's mnemonic to resolve its operational address the way "
                + "AccountConfig does — see botAccount()")
class LiquidatePayInAdvanceProductionWiringLiveTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";
    private static final String ORACLE_URL = "https://testapi.fluidtokens.com/get-oracle-tokens";

    // THIRD preview deployment — the same coordinates application.yaml's preview profile pins.
    private static final String CONFIG_POLICY_ID = "c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9";
    private static final String LM_CONFIG_POLICY_ID = "de1b8b40536f96c1084d73f838ebac6b228d891902d6234afc731484";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /** The convert loan FT opened: loan at #1, its lender bond at #3. */
    private static final String LOAN_TX =
            "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
    private static final int LOAN_INDEX = 1;
    private static final int BOND_INDEX = 3;
    private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";
    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");

    /**
     * The third preview deployment's script addresses. Held as constants rather than derived because
     * the registry exposes scripts, not addresses; a mismatch against what the chain holds means FT
     * has redeployed again, and the aborts below say so rather than passing quietly.
     */
    private static final String LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";
    private static final String BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";

    /** The one reference script the preview profile publishes, and the utxo carrying it. */
    private static final String REF_LOAN_CLAIM_ACTION_TX =
            "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd";
    private static final int REF_LOAN_CLAIM_ACTION_INDEX = 0;

    /**
     * Aborts LOUDLY. Gradle's XML carries no message for an aborted test and its console line reads
     * only "SKIPPED", so an abort alone is indistinguishable from a test that quietly did nothing —
     * the exact shape this suite spends its effort removing. Printing first means the reason is in
     * the build output whether or not anyone opens the report.
     */
    private static <T> T skip(String reason) {
        System.out.println("[T-039 SKIPPED] " + reason);
        return abort("[T-039] " + reason);
    }

    /**
     * The bot's account, resolved through <b>the production bean</b> rather than re-derived here.
     * <p>
     * Not fussiness. On 2026-08-24 two places derived "the bot's wallet" independently and disagreed:
     * this test read {@code .env.preview}'s mnemonic and reported {@code addr_test1qztwnc4…} as the
     * bot's address, while the running node was on {@code addr_test1qz3vp0h9…}. A whole diagnosis was
     * built on the wrong wallet. <b>Where a test and production must agree about identity, they share
     * the resolution rather than duplicating it</b> — so if {@link AccountConfig} ever changes network
     * mapping or derivation, this follows automatically.
     * <p>
     * What sharing the code path CANNOT fix is a different {@code WALLET_MNEMONIC} in the shell than
     * the node runs with. That is why every abort below names the address it resolved: a mismatch has
     * to be visible in the output, not inferred.
     */
    private static Account botAccount() {
        String mnemonic = System.getenv("WALLET_MNEMONIC");
        if (mnemonic == null || mnemonic.isBlank()) {
            skip("WALLET_MNEMONIC is not set — run with `set -a; . ./.env.preview; set +a`");
        }
        return new AccountConfig().account(mnemonic, "preview");
    }

    private static BFBackendService backend() {
        return new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY"));
    }

    /** Exactly {@code YaciConfig}'s lambda — the evaluator the production bean wires. */
    private static TransactionEvaluator productionEvaluator(BFBackendService backend) {
        return (cbor, inputUtxos) -> backend.getTransactionService().evaluateTx(cbor);
    }

    private static LoansContractRegistry registry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /**
     * A utxo that is <b>still unspent</b>, not merely one that once existed. {@code getTxOutput}
     * answers from the creating transaction and stays true forever after the output is spent (CCL
     * trap 12), so the live utxo set at the address is what is asked.
     */
    private static Optional<Utxo> liveUtxo(BFBackendService backend, String txHash, int index,
                                           String address) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null) {
            return Optional.empty();
        }
        return page.getValue().stream()
                .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == index)
                .findFirst();
    }

    /**
     * PART 1 — the live preconditions, which need no funded wallet and therefore run today.
     * <p>
     * Its value is diagnostic: when part 2 skips or fails, this says whether the cause is the chain
     * (a spent loan, a stale deployment, a dead oracle) or us. Each assertion names a coordinate a
     * reader can check against a block explorer.
     */
    @Test
    void thePreconditionsForAConvertLiquidationAreLiveOnPreview() throws Exception {
        BFBackendService backend = backend();
        LoansContractRegistry registry = registry();

        // The loan and its bond, still unspent.
        Utxo loanUtxo = liveUtxo(backend, LOAN_TX, LOAN_INDEX, LOAN_ADDRESS)
                .orElseGet(() -> skip("loan " + LOAN_TX + "#" + LOAN_INDEX + " is no longer unspent "
                        + "at the loan address — the fixture loan has been liquidated or repaid"));
        Utxo bondUtxo = liveUtxo(backend, LOAN_TX, BOND_INDEX, BOND_ADDRESS)
                .orElseGet(() -> skip("lender bond " + LOAN_TX + "#" + BOND_INDEX
                        + " is no longer unspent at the lender-manager address"));

        // The datums decode with the PRODUCTION converters, not fixtures.
        var loanDatum = new LoanDatumConverter().deserialize(loanUtxo.getInlineDatum());
        var bondDatum = new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum());
        assertNotNull(loanDatum, "the live loan datum must decode with the production converter");
        assertTrue(bondDatum.shouldLiquidationConvertToPrincipal(),
                "this must still be a CONVERT bond, or it is not the pay-in-advance path at all");

        // The published reference script is still there. This is what keeps the convert tx under
        // maxTxSize; without it the transaction is ~23,462 bytes against a 16,384 limit.
        Utxo refScript = liveUtxo(backend, REF_LOAN_CLAIM_ACTION_TX, REF_LOAN_CLAIM_ACTION_INDEX,
                botAccount().baseAddress())
                .orElseGet(() -> skip("the published loan_claim_action reference script "
                        + REF_LOAN_CLAIM_ACTION_TX + "#" + REF_LOAN_CLAIM_ACTION_INDEX
                        + " is gone — every convert liquidation is now TX_TOO_LARGE"));
        assertNotNull(refScript.getReferenceScriptHash(),
                "the coordinate must actually carry a reference script");

        // The oracle. Only the three Charli3 feeds are live on preview and there is a real ~60-80s
        // blackout every five minutes upstream of us, so an absent feed is a skip, not a failure.
        FluidOracleClient oracle = new FluidOracleClient(ORACLE_URL);
        oracle.refresh();
        OracleEntry entry = oracle.findEntry(COLLATERAL)
                .orElseGet(() -> skip("no live oracle entry for the collateral leg right now — "
                        + "preview's Charli3 feeds have a ~60-80s blackout every 5 minutes "
                        + "(design 6.7-6.8); re-run shortly"));
        assertNotNull(entry.feed(), "the live entry must carry a feed");
    }

    /**
     * PART 2 — the build itself, through the production constructor.
     * <p>
     * Skips while the bot's wallet has no spendable utxo, which is the state as of 2026-08-24: index
     * 0 holds only the published reference script, and {@code adaOnlyWalletUtxo()} correctly refuses
     * it. The abort message says exactly that, because "no valid utxos" without the reason is what
     * cost a full diagnosis round on the first shadow run.
     */
    @Test
    void theProductionWiringBuildsTheConvertLiquidation() throws Exception {
        BFBackendService backend = backend();
        LoansContractRegistry registry = registry();
        Account bot = botAccount();

        var walletPage = backend.getUtxoService().getUtxos(bot.baseAddress(), 100, 1);
        assumeTrue(walletPage.isSuccessful() && walletPage.getValue() != null,
                "could not read the bot's wallet utxos from Blockfrost");
        // The production filter, verbatim: ada only, no datum, no reference script.
        Utxo walletUtxo = walletPage.getValue().stream()
                .filter(u -> u.getAmount().size() == 1
                        && u.getReferenceScriptHash() == null
                        && u.getInlineDatum() == null
                        && u.getDataHash() == null)
                .findFirst()
                .orElseGet(() -> skip("no spendable utxo at " + bot.baseAddress() + " — "
                        + walletPage.getValue().size() + " present, none of them ada-only AND "
                        + "datum-free AND reference-script-free. TWO different causes look identical "
                        + "here, so check the address above FIRST: (a) if it is NOT the address your "
                        + "node logs at startup, the WALLET_MNEMONIC in this shell is a different "
                        + "wallet from the node's and nothing about funding is wrong; (b) if it IS "
                        + "the node's address, it needs one ada-only utxo comfortably covering the "
                        + "whole transaction on its own."));

        // Everything else the request needs, live.
        Utxo loanUtxo = liveUtxo(backend, LOAN_TX, LOAN_INDEX, LOAN_ADDRESS)
                .orElseGet(() -> skip("the fixture loan is no longer unspent"));
        Utxo bondUtxo = liveUtxo(backend, LOAN_TX, BOND_INDEX, BOND_ADDRESS)
                .orElseGet(() -> skip("the fixture lender bond is no longer unspent"));
        FluidOracleClient oracle = new FluidOracleClient(ORACLE_URL);
        oracle.refresh();
        OracleEntry oracleEntry = oracle.findEntry(COLLATERAL)
                .orElseGet(() -> skip("no live oracle entry for the collateral leg right now"));

        Utxo configUtxo = configUtxo(backend, CONFIG_POLICY_ID)
                .orElseGet(() -> skip("the main config utxo could not be located live under policy "
                        + CONFIG_POLICY_ID + " — suspect a redeploy"));
        Utxo lmConfigUtxo = configUtxo(backend, LM_CONFIG_POLICY_ID)
                .orElseGet(() -> skip("the lm config utxo could not be located live under policy "
                        + LM_CONFIG_POLICY_ID + " — suspect a redeploy"));

        Loan loan = new Loan(LOAN_TX, LOAN_INDEX, loanUtxo.getAddress(), LOAN_ID,
                BigInteger.valueOf(loanUtxo.getAmount().stream()
                        .filter(a -> !a.getUnit().equals("lovelace"))
                        .findFirst().orElseThrow().getQuantity().longValue()),
                BigInteger.valueOf(loanUtxo.getAmount().stream()
                        .filter(a -> a.getUnit().equals("lovelace"))
                        .findFirst().orElseThrow().getQuantity().longValue()),
                new LoanDatumConverter().deserialize(loanUtxo.getInlineDatum()));
        LenderBond bond = new LenderBond(LOAN_TX, BOND_INDEX, bondUtxo.getAddress(), LOAN_ID,
                bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));

        long now = System.currentTimeMillis();
        var request = new LiquidatePayInAdvanceTransactionBuilder.Request(loan, loanUtxo, bond,
                bondUtxo, walletUtxo, configUtxo, lmConfigUtxo, oracleEntry, now,
                slotOf(now - 60_000L), slotOf(now + 120_000L), bot.baseAddress(),
                referenceScripts());

        // THE POINT OF THE TEST: the production constructor. Backend-supplied script supplier,
        // Blockfrost evaluator, real ledger fee.
        Transaction built = new LiquidatePayInAdvanceTransactionBuilder(registry, Networks.preview(),
                backend, productionEvaluator(backend)).build(request);

        assertNotNull(built, "the production wiring must produce a transaction");

        // Ex-units read off the BUILT, DESERIALISED transaction — never off an EvaluationResult. A
        // rig-supplied evaluator makes the report look right while production has none (CCL trap 8).
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertNotNull(redeemers, "a script transaction must carry redeemers");
        assertFalse(redeemers.isEmpty(), "and at least one of them");
        for (Redeemer redeemer : redeemers) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(BigInteger.valueOf(10_000L)) > 0,
                    "redeemer " + redeemer.getTag() + "#" + redeemer.getIndex() + " still carries "
                            + "cardano-client-lib's PLACEHOLDER mem — the evaluator never priced it: "
                            + redeemer.getExUnits());
            assertTrue(redeemer.getExUnits().getSteps().compareTo(BigInteger.valueOf(10_000L)) > 0,
                    "redeemer " + redeemer.getTag() + "#" + redeemer.getIndex() + " still carries "
                            + "placeholder steps: " + redeemer.getExUnits());
        }

        // The reference script must be READ, never consumed — not as an input and not as collateral.
        String refCoordinate = REF_LOAN_CLAIM_ACTION_TX + "#" + REF_LOAN_CLAIM_ACTION_INDEX;
        assertFalse(coordinates(reread.getBody().getInputs()).contains(refCoordinate),
                "the published reference script must never be SPENT: "
                        + coordinates(reread.getBody().getInputs()));
        assertFalse(coordinates(reread.getBody().getCollateral()).contains(refCoordinate),
                "nor pledged as COLLATERAL, which a phase-2 failure would consume: "
                        + coordinates(reread.getBody().getCollateral()));
        assertTrue(coordinates(reread.getBody().getReferenceInputs()).contains(refCoordinate),
                "it must be present as a REFERENCE INPUT, or the transaction cannot fit under "
                        + "maxTxSize: " + coordinates(reread.getBody().getReferenceInputs()));

        // The real ledger fee, which no offline rig computes.
        assertTrue(reread.getBody().getFee().signum() > 0, "the built transaction must carry a fee");

        // And it fits. 16,384 is the live parameter's value; S5 reads it from the chain at run time.
        assertTrue(built.serialize().length <= 16_384,
                "the convert transaction must fit under maxTxSize: " + built.serialize().length);
    }

    private static List<String> coordinates(List<TransactionInput> inputs) {
        return inputs == null ? List.of()
                : inputs.stream().map(in -> in.getTransactionId() + "#" + in.getIndex()).toList();
    }

    private static LiquidateTransactionBuilder.ReferenceScripts referenceScripts() {
        // Exactly what application.yaml's preview profile publishes: loan_claim_action only.
        return new LiquidateTransactionBuilder.ReferenceScripts(null, null, null, null,
                new TransactionInput(REF_LOAN_CLAIM_ACTION_TX, REF_LOAN_CLAIM_ACTION_INDEX),
                null, null);
    }

    /** The same conversion the builders use, through the production {@code CardanoConverters}. */
    private static long slotOf(long millis) {
        return converters().time().toSlot(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC));
    }

    private static org.cardanofoundation.conversions.CardanoConverters converters() {
        return org.cardanofoundation.conversions.ClasspathConversionsFactory
                .createConverters(org.cardanofoundation.conversions.domain.NetworkType.PREVIEW);
    }

    /**
     * The config utxo, located the way the node does: by the policy id it is PINNED to. A redeploy
     * mints new NFTs under a NEW policy id and leaves the old ones unburnt, so a pinned coordinate
     * keeps verifying cleanly forever (findings 12) — which is why an absence here is reported as a
     * suspected redeploy rather than a transient miss.
     */
    private static Optional<Utxo> configUtxo(BFBackendService backend, String policyId)
            throws Exception {
        var addresses = backend.getAssetService()
                .getAllAssetAddresses(policyId + CONFIG_ASSET_NAME);
        if (!addresses.isSuccessful() || addresses.getValue() == null
                || addresses.getValue().isEmpty()) {
            return Optional.empty();
        }
        String holder = addresses.getValue().getFirst().getAddress();
        var page = backend.getUtxoService().getUtxos(holder, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null) {
            return Optional.empty();
        }
        return page.getValue().stream()
                .filter(u -> u.getAmount().stream()
                        .anyMatch(a -> a.getUnit().equals(policyId + CONFIG_ASSET_NAME)))
                .findFirst();
    }
}
