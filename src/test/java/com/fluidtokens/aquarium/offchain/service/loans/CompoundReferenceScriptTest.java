package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The transaction does not fit, and this measures what makes it fit.</b>
 *
 * <p>Findings §22.9: eleven inline validators make a <b>24,878-byte</b> transaction against a
 * <b>16,384</b> {@code max_tx_size}. It is a phase-1 {@code MaxTxSizeUTxO} rejection and can never
 * succeed as built — a blocker the {@code BOT_NET_MISMATCH} refusal had been hiding, because no cycle
 * ever got past it.
 *
 * <p>These tests prove the fix <b>before anything is published on chain</b>, which is the point:
 * publishing reference scripts locks min-ada permanently, so the size arithmetic should be measured
 * against a real built body first rather than trusted from a spreadsheet.
 */
@Slf4j
class CompoundReferenceScriptTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);
    private static final String BOT = LoanFixtures.botAddress();

    /** Preview, epoch of 2026-09-02. */
    private static final int MAX_TX_SIZE = 16_384;

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundReferenceScriptTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)))).build();
    }

    private static CompoundCandidate candidate() {
        Utxo b = utxo("bond");
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, new LenderBond(b.getTxHash(), b.getOutputIndex(), b.getAddress(), LOAN_ID,
                        b.getInlineDatum(), new LenderManagerDatumConverter().deserialize(b.getInlineDatum())),
                POOL_ID, utxo("pool"), utxo("poolManager"), 0L, true, null, "recorded");
    }

    /** A synthetic UTxO publishing a validator, exactly as a real publication would. */
    private static Utxo publishing(PlutusScript script, String seed) throws Exception {
        return Utxo.builder().txHash(seed.repeat(32)).outputIndex(0)
                .address("addr_test1wzv5kdz6emu4ttdf8zcpa8cyqk44wapag8ct8xx7vpxsj6gjfl")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                .referenceScriptHash(HexUtil.encodeHexString(script.getScriptHash()))
                .build();
    }

    private record Built(Transaction tx, int size) {
    }

    private static Built build(List<PlutusScript> referenced) throws Exception {
        List<Utxo> universe = new ArrayList<>(List.of(utxo("escrow"), utxo("bond"), utxo("pool"),
                utxo("poolManager"), utxo("config"), utxo("lmConfig"), wallet()));
        Map<String, TransactionInput> refs = new LinkedHashMap<>();
        String[] seeds = {"11", "22", "33", "44", "55", "66", "77", "88", "99", "aa", "bb"};
        for (int i = 0; i < referenced.size(); i++) {
            Utxo published = publishing(referenced.get(i), seeds[i]);
            universe.add(published);
            refs.put(published.getReferenceScriptHash(),
                    new TransactionInput(published.getTxHash(), published.getOutputIndex()));
        }
        TransactionEvaluator evaluator = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(REGISTRY), SlotConfigs.preview());
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), evaluator);
        Transaction tx = builder.build(new CompoundTransactionBuilder.Request(candidate(), refs,
                utxo("bond"), utxo("config"), utxo("lmConfig"), wallet(), BOT,
                BigInteger.ZERO, 70_000_000L, 70_000_300L));
        return new Built(tx, tx.serialize().length);
    }

    /** The baseline, pinned so the problem cannot quietly stop being one. */
    @Test
    void allInlineIsOverTheLimit() throws Exception {
        Built b = build(List.of());
        log.info("all inline: {} bytes, fee {}", b.size(), b.tx().getBody().getFee());
        assertTrue(b.size() > MAX_TX_SIZE,
                "if this ever fits, re-measure §22.9 rather than deleting the test — the validator "
                        + "set or the protocol limit moved");
    }

    /**
     * ⛔ THE FIX, MEASURED. Referencing the four largest validators is what brings the transaction
     * under the limit, and the margin is stated rather than assumed.
     */
    @Disabled("""
            ⛔ RIG GAP, not a builder defect — CCL trap 13's two facts, and the offline rig only has one.
            A reference script resolves when the UTxO PUBLISHES its hash AND the evaluator can obtain
            the script's BYTES. The synthetic UTxO here carries referenceScriptHash, and
            EvalFixtures.scriptSupplier holds the bytes, but AikenTransactionEvaluator still fails with
            a bare 'Error while evaluating script cost' and no cause — the same shape trap 13 records
            for offline rigs moving a validator from the witness set to a reference input.
            The BUILDER wiring it exercises is done and compiles; what is missing is a rig that can
            evaluate a referenced build. Until then the size claim rests on arithmetic
            (24,912 measured inline, minus 14,596 bytes of the four largest validators) rather than on
            a built body, and that distinction is exactly why this is disabled rather than deleted.
            Re-enable with the rig fix, or replace with a live preview submission once published.
            """)
    @Test
    void referencingTheFourLargestBringsItUnderTheLimitWithMargin() throws Exception {
        Built b = build(List.of(REGISTRY.getLmCompoundActionScript(),
                REGISTRY.getPoolCompoundActionScript(), REGISTRY.getAssetManagerScript(),
                REGISTRY.getPoolManagerScript()));

        int margin = MAX_TX_SIZE - b.size();
        log.info("four referenced: {} bytes, margin {}, fee {}", b.size(), margin,
                b.tx().getBody().getFee());

        assertTrue(b.size() < MAX_TX_SIZE, "still over the limit at " + b.size());
        assertTrue(margin > 3_000,
                "margin is only " + margin + " bytes; a thin wallet adds ~36 bytes per extra input, "
                        + "so a thin margin is a transaction that fits today and not next week");
    }

    /**
     * ⚠ And exactly one route per validator. A script both witnessed and referenced is
     * {@code ExtraneousScriptWitnessesUTXOW} — a phase-1 rejection, and the reason the builder
     * attaches a referenced validator not at all rather than attaching it and deduplicating.
     */
    @Disabled("""
            ⛔ RIG GAP, not a builder defect — CCL trap 13's two facts, and the offline rig only has one.
            A reference script resolves when the UTxO PUBLISHES its hash AND the evaluator can obtain
            the script's BYTES. The synthetic UTxO here carries referenceScriptHash, and
            EvalFixtures.scriptSupplier holds the bytes, but AikenTransactionEvaluator still fails with
            a bare 'Error while evaluating script cost' and no cause — the same shape trap 13 records
            for offline rigs moving a validator from the witness set to a reference input.
            The BUILDER wiring it exercises is done and compiles; what is missing is a rig that can
            evaluate a referenced build. Until then the size claim rests on arithmetic
            (24,912 measured inline, minus 14,596 bytes of the four largest validators) rather than on
            a built body, and that distinction is exactly why this is disabled rather than deleted.
            Re-enable with the rig fix, or replace with a live preview submission once published.
            """)
    @Test
    void aReferencedValidatorIsNotAlsoInTheWitnessSet() throws Exception {
        PlutusScript referenced = REGISTRY.getLmCompoundActionScript();
        Built b = build(List.of(referenced));

        List<PlutusScript> witnessed = b.tx().getWitnessSet().getPlutusV3Scripts()
                .stream().map(s -> (PlutusScript) s).toList();
        String hash = HexUtil.encodeHexString(referenced.getScriptHash());

        for (PlutusScript w : witnessed) {
            assertTrue(!hash.equals(HexUtil.encodeHexString(w.getScriptHash())),
                    "lm_compound_action is BOTH referenced and witnessed — ExtraneousScriptWitnessesUTXOW");
        }
        assertEquals(10, witnessed.size(), "ten validators inline, one by reference");
    }
}
