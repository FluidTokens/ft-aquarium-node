package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumTankConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.ScheduledTransactionData;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import com.fluidtokens.aquarium.offchain.util.AssetAmountUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ⛔ <b>THE TANK TRANSACTION, EVALUATED AGAINST THE REAL VALIDATOR, WITH NO NETWORK AND NO KEYS.</b>
 *
 * <p>The mainnet processor spent 2026-09-22 failing at a remote evaluator that would only say
 * {@code {"ScriptFailures":{}}} — an empty map, naming nothing. Every diagnosis attempted from that
 * string was wrong, in sequence: Conway set tags, a negative collateral return, a fee-versus-
 * collateral ceiling, PR #22. <b>A remote evaluator reports that something failed; a local one
 * reports what.</b>
 *
 * <p>So this runs the actual {@code tank.tank.spend} PlutusV3 validator over the actual datum of an
 * actual mainnet tank, using {@code aiken-java-binding}. It needs no mnemonic — <b>evaluation is not
 * signing</b> — and no provider.
 *
 * <h2>What makes it honest</h2>
 * <ul>
 *   <li><b>It calls {@link ScheduledTransactionService#tankScriptTx} — the production constructor.</b>
 *       A rig that rebuilds the shape by hand proves the rig's shape. This repo has already paid for
 *       that lesson once (the null-evaluator incident of 2026-08-21, collateral forfeit).</li>
 *   <li><b>Protocol parameters and the cost model are the CHAIN'S, recorded from Koios</b>, not
 *       {@code CostModelUtil.PlutusV3CostModel}. Measured at epoch 657: the chain publishes
 *       <b>350</b> PlutusV3 entries and protocol major <b>11</b>; the library constant carries 251
 *       and major 10. Pinning the library's would under-cost, which is the direction that passes
 *       offline and fails in phase 2 (CCL trap 7).</li>
 *   <li><b>The fixture is real</b>: a live tank, its datum, and the three reference inputs the
 *       processor reads, fetched from chain and committed so the test is deterministic.</li>
 * </ul>
 *
 * <p>⚠ <b>What it cannot settle</b>: it prices scripts, not ledger fees — reference-script fees in
 * particular need a backend's script supplier (CCL trap 9). A green run here means the validator
 * accepts this transaction, not that the network will.
 */
class TankTransactionDryEvalTest {

    /** Operator identity, read off the mainnet transaction the node actually built. */
    private static final String OPERATOR_PAYMENT = "ec900701dc71ef420bc24bda6c484f5f9276836aae73c4f70b7d4493";
    private static final String OPERATOR_STAKE = "682fec1c867c6aa59918ef6f25c6bdd38c1c7d03d71c0b4fa23c5bac";
    private static final String TANK_REF_TX = "354ffe7958d62a8a2bf0b0bd97a06694d59dc49b6d02f1ab40165a3955257168";
    private static final String PARAMS_REF_TX = "b79f33b820dd572394cf93e8a4ad1a67ee2d46dbf69ac6ceca33de0d6ff56476";
    private static final String STAKER_REF_TX = "3d640e597bd7470abf9efd15ed45c60f5e1d71dc038a5ffa7795ecfe97786dc7";

    private static JsonNode fixture() throws Exception {
        return fixture("/tank-eval-fixture.json");
    }

    private static JsonNode fixture(String resource) throws Exception {
        try (InputStream in = TankTransactionDryEvalTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " missing from test resources");
            return new ObjectMapper().readTree(in);
        }
    }

    private static String paramsDatumHex(JsonNode fx) {
        for (JsonNode r : fx.withArray("refs")) {
            if (PARAMS_REF_TX.equals(r.get("tx_hash").asText())) {
                return r.get("inline_datum").asText();
            }
        }
        return fail("parameters reference input missing from the fixture");
    }

    private static Utxo utxo(JsonNode n) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(new BigInteger(n.get("value").asText())));
        for (JsonNode a : n.withArray("asset_list")) {
            amounts.add(Amount.asset(a.get("policy_id").asText() + a.get("asset_name").asText(),
                    new BigInteger(a.get("quantity").asText())));
        }
        var b = Utxo.builder()
                .txHash(n.get("tx_hash").asText())
                .outputIndex(n.get("tx_index").asInt())
                .address(n.get("address").asText())
                .amount(amounts);
        if (n.hasNonNull("inline_datum")) {
            b.inlineDatum(n.get("inline_datum").asText());
        }
        if (n.hasNonNull("reference_script_hash")) {
            b.referenceScriptHash(n.get("reference_script_hash").asText());
        }
        return b.build();
    }

    private static ProtocolParams protocolParams(JsonNode p) {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        List<Long> v3 = new ArrayList<>();
        p.withArray("plutus_v3_cost_model").forEach(x -> v3.add(x.asLong()));
        costModels.put("PlutusV3", v3);
        return ProtocolParams.builder()
                .minFeeA(p.get("min_fee_a").asInt())
                .minFeeB(p.get("min_fee_b").asInt())
                // ⚠ The CHAIN's 16,384 would be the honest figure, but a reference-script build can
                // exceed it here for reasons that are the rig's, not the transaction's. Raised on
                // purpose so a size ceiling cannot masquerade as a script failure — the question this
                // test answers is whether the VALIDATOR accepts, and nothing else.
                .maxTxSize(1_000_000)
                .maxValSize(String.valueOf(p.get("max_val_size").asInt()))
                .coinsPerUtxoSize(p.get("coins_per_utxo_size").asText())
                .priceMem(new BigDecimal(p.get("price_mem").asText()))
                .priceStep(new BigDecimal(p.get("price_step").asText()))
                .maxTxExMem(p.get("max_tx_ex_mem").asText())
                .maxTxExSteps(p.get("max_tx_ex_steps").asText())
                .collateralPercent(new BigDecimal(p.get("collateral_percent").asText()))
                .maxCollateralInputs(p.get("max_collateral_inputs").asInt())
                .minFeeRefScriptCostPerByte(new BigDecimal(p.get("min_fee_ref_script_cost_per_byte").asText()))
                .protocolMajorVer(p.get("protocol_major").asInt())
                .protocolMinorVer(p.get("protocol_minor").asInt())
                .costModelsRaw(costModels)
                .build();
    }

    private static UtxoSupplier supplier(List<Utxo> universe) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return page != null && page > 0 ? List.of()
                        : universe.stream().filter(u -> u.getAddress().equals(address)).toList();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return universe.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
    }

    /** The tank validator, taken from the blueprint — its hash equals the published reference script. */
    private static PlutusV3Script tankValidator() throws Exception {
        JsonNode blueprint = new ObjectMapper().readTree(
                TankTransactionDryEvalTest.class.getResourceAsStream("/plutus.json"));
        for (JsonNode v : blueprint.withArray("validators")) {
            if ("tank.tank.spend".equals(v.get("title").asText())) {
                return (PlutusV3Script) com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil
                        .getPlutusScriptFromCompiledCode(v.get("compiledCode").asText(),
                                com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion.v3);
            }
        }
        return fail("tank.tank.spend not found in plutus.json");
    }

    @Test
    void theTankTransactionEvaluatesAgainstTheRealValidator() throws Exception {
        JsonNode fx = fixture();

        Utxo tank = utxo(fx.get("tank"));
        List<Utxo> refs = new ArrayList<>();
        fx.withArray("refs").forEach(r -> refs.add(utxo(r)));

        // ⚠ Synthetic ONLY for collateral: it is never evaluated and never spent on a success, so
        // inventing one changes nothing the validator can see. Everything the script reads is real.
        String operator = AddressProvider.getBaseAddress(
                Credential.fromKey(HexUtil.decodeHexString(OPERATOR_PAYMENT)),
                Credential.fromKey(HexUtil.decodeHexString(OPERATOR_STAKE)),
                Networks.mainnet()).getAddress();
        Utxo collateral = Utxo.builder()
                .txHash("00".repeat(32)).outputIndex(0).address(operator)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(10_000_000L)))).build();

        // ⛔ A SPENDABLE WALLET UTXO, AND IT IS NOT OPTIONAL. This is the finding that corrected the
        // design: the tank CANNOT fund itself. The fixture tank holds 101.34 ada and owes 101.00, so
        // the remainder is 0.34 -- below the min-UTxO floor for the change output, before any fee.
        // CCL then reaches for the fee payer's wallet, and without this it fails with
        // "Not enough funds for [{lovelace=2958283}]" during ChangeOutputAdjustments.
        Utxo walletInput = Utxo.builder()
                .txHash("11".repeat(32)).outputIndex(0).address(operator)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();

        List<Utxo> universe = new ArrayList<>(refs);
        universe.add(tank);
        universe.add(collateral);
        universe.add(walletInput);

        ProtocolParams params = protocolParams(fx.get("protocol_params"));
        ProtocolParamsSupplier paramsSupplier = () -> params;
        UtxoSupplier utxoSupplier = supplier(universe);

        var tankDatum = new DatumTankConverter().deserialize(fx.get("tank").get("inline_datum").asText());

        TransactionInput paramsRef = TransactionInput.builder().transactionId(PARAMS_REF_TX).index(0).build();
        TransactionInput stakerRef = TransactionInput.builder().transactionId(STAKER_REF_TX).index(0).build();
        TransactionInput tankRef = TransactionInput.builder().transactionId(TANK_REF_TX).index(0).build();

        // ⚠ The redeemer names reference inputs by their position in the LEDGER's ordering, which is
        // sorted by (txid, index) — never the order they were added. Same derivation as production.
        List<TransactionInput> sorted = Stream.of(paramsRef, stakerRef, tankRef)
                .sorted(new TransactionInputComparator()).toList();

        var redeemerData = new ScheduledTransactionData();
        redeemerData.setInputtankindex(BigInteger.ZERO);
        redeemerData.setBatcher(AddressUtil.toOnchainAddress(
                new com.bloxbean.cardano.client.address.Address(operator)));
        redeemerData.setReferenceParamsIndex(BigInteger.valueOf(sorted.indexOf(paramsRef)));
        redeemerData.setReferenceStakingIndex(BigInteger.valueOf(sorted.indexOf(stakerRef)));
        redeemerData.setWhitelistIndex(BigInteger.ZERO);

        // ⛔ THE REWARD GOES WHERE THE PARAMETERS DATUM SAYS, NOT WHERE THE TANK SAYS.
        //
        // An earlier version of this test passed the tank's destination address for both outputs.
        // The validator rejected it — correctly — and the failure looked exactly like the production
        // one, which is the trap: a rig that builds the wrong transaction reproduces "it fails"
        // without reproducing the reason, and the resemblance is itself misleading.
        var parameters = new com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter
                .DatumParametersConverter().deserialize(
                        paramsDatumHex(fx));

        var tx = ScheduledTransactionService.tankScriptTx(
                walletInput, tank, redeemerData.toPlutusData(),
                AddressUtil.toAddress(tankDatum.getDestionationaaddress(), Networks.mainnet()).getAddress(),
                AssetAmountUtil.toValue(List.of(tankDatum.getScheduledamount())),
                AddressUtil.toAddress(parameters.getAddressRewards(), Networks.mainnet()).getAddress(),
                AssetAmountUtil.toValue(List.of(tankDatum.getReward())),
                operator, paramsRef, stakerRef, tankRef);

        final var validator = tankValidator();

        // ⛔ SCALUS' PlutusVM, NOT aiken-java-binding.
        //
        // A second evaluator of INDEPENDENT PROVENANCE is the whole point (CCL trap 7): when two
        // implementations disagree, the disagreement localises the fault, and when a remote one
        // answers {"ScriptFailures":{}} — an empty map naming nothing — a local one that names the
        // failure is the only way forward.
        // ⚠ SlotConfig moved to scalus.cardano.ledger in 1.x — the scalus.bloxbean one the older
        // rigs in this workspace use (0.8.5 / 0.10.4) no longer exists.
        var evaluator = new scalus.bloxbean.ScalusTransactionEvaluator(
                scalus.cardano.ledger.SlotConfig$.MODULE$.mainnet(), params, utxoSupplier,
                (scalus.bloxbean.ScriptSupplier) scriptHash -> validator,
                scalus.bloxbean.EvaluatorMode.EVALUATE_AND_COMPUTE_COST, false);

        long slot = SlotConfigs.mainnet().getZeroSlot()
                + (System.currentTimeMillis() - SlotConfigs.mainnet().getZeroTime()) / 1000;

        // ⚠ FOUR-ARG, with a ScriptSupplier. The three-arg form NPEs at build() whenever the
        // transaction carries reference inputs: ReferenceScriptResolver walks them and calls
        // getScript() on a field that constructor never sets (CCL trap 2). This transaction reads
        // three reference inputs, so it hits that every time.
        // ⛔ THE PRODUCTION CONFIGURATION, NOT A REBUILD OF IT.
        //
        // An audit on 2026-09-23 diffed the knobs this test set for itself against the ones the
        // service sets, and found it missing withUtxoSelectionStrategy and preBalanceTx — so the
        // reference-script guard and the pre-evaluation fee were both unexercised, the fee having
        // shipped hours earlier. balanceTankTx() is now the single source of both.
        //
        // ⚠ What this test still supplies for itself is exactly what a rig is allowed to supply:
        // the evaluator, and NO SIGNERS AT ALL. Evaluation is not signing, so no mnemonic is
        // needed — and a rig that cannot sign cannot accidentally submit.
        var built = ScheduledTransactionService.balanceTankTx(
                        new QuickTxBuilder(utxoSupplier, paramsSupplier,
                                (com.bloxbean.cardano.client.api.ScriptSupplier) sh -> Optional.of(validator),
                                (com.bloxbean.cardano.client.api.TransactionProcessor) null)
                                .compose(tx),
                        tank, collateral, operator, utxoSupplier, slot)
                .withTxEvaluator(evaluator)
                // ⛔ FALSE. Left true — its default — a failed evaluation is swallowed and the build
                // ships PLACEHOLDER ex-units, so this test would go green on exactly the defect it
                // exists to catch (CCL trap 8).
                .ignoreScriptCostEvaluationError(false)
                .build();

        // ⛔ THE SPEND REDEEMER MUST NAME THE INPUT THE TANK ACTUALLY OCCUPIES.
        //
        // A spend redeemer addresses its input by position in the ledger-sorted input list, so
        // dropping an input in postBalanceTx can move the tank out from under it. Before the
        // reshape CCL had placed this redeemer at index 1; after it, the tank is the only input and
        // the index must be 0. Nothing else in the build recomputes that.
        var inputs = new ArrayList<>(built.getBody().getInputs());
        inputs.sort(new com.fluidtokens.aquarium.offchain.service.TransactionInputComparator());
        int tankLedgerPosition = -1;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).getTransactionId().equals(tank.getTxHash())
                    && inputs.get(i).getIndex() == tank.getOutputIndex()) {
                tankLedgerPosition = i;
            }
        }
        var spend = built.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend)
                .findFirst().orElseThrow();
        assertEquals(tankLedgerPosition, spend.getIndex().intValue(),
                "the spend redeemer points at input " + spend.getIndex() + " but the tank sits at "
                        + tankLedgerPosition + ". Reshaping the body without moving the redeemer "
                        + "leaves it naming an input that is no longer there.");

        // ⚑ SEPARATELY, the datum's own inputtankindex is hardcoded to ZERO, and it is CORRECT --
        // T-066, settled by probe against the real validator on the earlier two-input shape, where
        // the tank genuinely sat at ledger position 1:
        //
        //     inputtankindex = 0  ->  evaluates, 317,812 mem
        //     inputtankindex = 1  ->  RedeemerError { tag: "Spend", index: 0,
        //                              err: Machine(EvaluationFailure, ...) }
        //
        // So it indexes the TANK inputs, of which there is exactly one -- not the body's inputs.
        // Both readings build a transaction, so only the validator could tell them apart.

        // ⛔ EVALUATE THE FINAL BYTES, NOT THE ONES CCL EVALUATED.
        //
        // QuickTxBuilder evaluates BEFORE balancing, and postBalanceTx reshapes the body afterwards
        // -- so the transaction the library priced is not the transaction it returns. Asserting on
        // the earlier one would prove nothing about what gets submitted; this re-runs the real
        // validator over the finished article.
        // ⚠ Scalus' evaluateTx takes the bytes alone — it resolves inputs through the UtxoSupplier
        // it was constructed with, which is the fixture universe.
        var finalEval = evaluator.evaluateTx(built.serialize());
        assertTrue(finalEval.isSuccessful(),
                "the RESHAPED transaction must still satisfy the validator: " + finalEval.getResponse());
        System.out.println("final shape evaluated: " + finalEval.getValue());

        // ⛔⛔ THE DECLARED EX-UNITS MUST COVER WHAT THE BUILT BODY ACTUALLY COSTS.
        //
        // Under-declaring is not rejected at the mempool: the transaction is accepted, the script
        // exhausts its budget on chain, and it fails in PHASE 2 with the collateral gone (CCL trap
        // 8). So this compares what the transaction DECLARES against what a second, independent
        // evaluator measures on the finished bytes.
        var measured = finalEval.getValue().stream()
                .filter(r -> r.getRedeemerTag() == com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend)
                .findFirst().orElseThrow().getExUnits();
        assertTrue(spend.getExUnits().getMem().compareTo(measured.getMem()) >= 0
                        && spend.getExUnits().getSteps().compareTo(measured.getSteps()) >= 0,
                "the transaction declares mem=" + spend.getExUnits().getMem() + " steps="
                        + spend.getExUnits().getSteps() + " but the built body costs mem="
                        + measured.getMem() + " steps=" + measured.getSteps()
                        + ". Shipping that is a phase-2 failure and forfeited collateral.");

        // ⛔ THE VANILLA SHAPE: CCL's own, unaltered after balancing.
        //
        // ⚠ This asserted 1 input / 2 outputs until 2026-09-23, for a self-funding transaction that
        // BOTH local evaluators accept and Blockfrost refuses with an empty ScriptFailures map. We
        // reverted to the shape mainnet has actually executed (tank 64470b26...#0) rather than keep
        // shipping one we can only validate offline. The change output is the operator's, and the
        // operator funds the fee — a few thousand lovelace per payment, knowingly paid.
        assertEquals(2, built.getBody().getInputs().size(),
                "vanilla CCL spends the tank AND a wallet utxo. Inputs were "
                        + built.getBody().getInputs());
        assertEquals(3, built.getBody().getOutputs().size(),
                "payee, operator reward, and the operator's change. Outputs were "
                        + built.getBody().getOutputs().size());

        var inputTotal = new BigInteger(fx.get("tank").get("value").asText())
                .add(BigInteger.valueOf(20_000_000L));
        var paidOut = built.getBody().getOutputs().stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(inputTotal, paidOut.add(built.getBody().getFee()),
                "inputs must equal outputs plus fee — CCL balanced it and nothing touched it after");
        System.out.println("vanilla shape OK: " + built.getBody().getInputs().size() + " inputs, "
                + built.getBody().getOutputs().size() + " outputs, fee " + built.getBody().getFee());

        // ⚠ No tank-affordability assertion here any more. In the vanilla shape the operator's
        // wallet input covers the fee, so "can the tank pay for itself" is no longer the question —
        // it was the question only while the tank was the sole input.

        // ⛔ STRUCTURAL PARITY WITH PRODUCTION: reference script, no witness copy.
        //
        // ⚠ Added after a harder lesson about what this rig is worth. Both local evaluators accepted
        // a transaction shape that mainnet refused four times out of four, so "Scalus and Aiken
        // agree" is NOT evidence the transaction is correct — it means two evaluators share a blind
        // spot. A rig that cannot reproduce a known production failure is not confirming the code;
        // it is failing to reproduce.
        //
        // ⇒ What a rig CAN honestly assert is that the artefact it built has the same structure as
        // the one production ships. If the script ever arrives as a witness copy instead of through
        // a reference input, the rig is pricing a different transaction — bigger, differently
        // witnessed, and differently costed (CCL trap 9).
        assertEquals(0, built.getWitnessSet().getPlutusV3Scripts() == null ? 0
                        : built.getWitnessSet().getPlutusV3Scripts().size(),
                "the validator must come from a reference input, never a witness copy — production "
                        + "spends through the published reference script");
        assertEquals(3, built.getBody().getReferenceInputs().size(),
                "parameters, staker and the tank validator, exactly as the service reads them");
        assertNotNull(built.getBody().getScriptDataHash(),
                "a transaction carrying redeemers must carry a script data hash, or the ledger "
                        + "cannot check that what ran is what was declared");

        var redeemers = built.getWitnessSet().getRedeemers();
        assertFalse(redeemers.isEmpty(), "the tank spend must carry a redeemer");

        // ⛔ Asserted off the BUILT transaction, never off the evaluator's report. A rig-supplied
        // evaluator makes the report look right while the shipped bytes carry placeholders.
        var exUnits = redeemers.getFirst().getExUnits();
        assertTrue(exUnits.getMem().compareTo(BigInteger.valueOf(10_000L)) > 0
                        && exUnits.getSteps().compareTo(BigInteger.valueOf(10_000L)) > 0,
                "ex-units are still cardano-client-lib's placeholders (10000/10000 or 10000/1000), "
                        + "which means evaluation never actually priced this script. Got mem="
                        + exUnits.getMem() + " steps=" + exUnits.getSteps());
    }
}
