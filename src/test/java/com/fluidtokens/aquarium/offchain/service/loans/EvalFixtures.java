package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The offline PlutusV3 dry-evaluation rig used by {@link LiquidateDryEvalTest}.
 *
 * <h2>What it is for</h2>
 * {@link LiquidateTransactionBuilderTest} proves the transaction has the shape <em>this repo
 * believes</em> the deployed validators want. That is a claim about our model, and a green suite
 * built only out of it proves nothing about the chain. This rig closes that gap: it runs the real
 * applied {@code loans-v4} scripts — the same ones {@link LoansContractRegistry} derives from the
 * committed blueprint — through the Aiken UPLC machine
 * ({@code aiken-java-binding}'s {@link AikenTransactionEvaluator}) over a fully synthetic preview
 * UTxO set. No network, no key, no wallet: everything the evaluator needs is handed to it.
 *
 * <h2>Why the protocol params here are not {@link LoanFixtures#protocolParams()}</h2>
 * They are the same params with one deliberate change: {@code maxTxSize} is raised far past the
 * real 16384. A {@code Liquidate} that carries all six validators in its witness set is well over
 * the real limit, and on chain it travels by reference script instead. Raising the ceiling is a
 * <b>sanctioned throwaway</b>: it changes nothing the scripts see, because a script's
 * {@code ScriptContext} contains no transaction size.
 * <p>
 * <b>Superseded in part, 2026-08-16 (T-016 S1).</b> This paragraph used to add that reference
 * scripts "would need a published UTxO holding them, which is exactly what FluidTokens has not
 * published" — that is now false, and was quietly falsified some time ago: the preview profile
 * carries six verified coordinates and the reference-script shape both builds and evaluates in this
 * rig. The size question it deferred is answered too — 1,451 bytes ada/ada and 1,986 oracle-bearing
 * against the real 16,384. Those two measurements pin {@code maxTxSize} themselves rather than
 * reading it from these params, so this raised ceiling cannot flatter them.
 * The PlutusV3 cost model is the real one bundled with cardano-client-lib
 * ({@link CostModelUtil#PlutusV3CostModel}), so the ex-units the evaluator reports are real budget
 * numbers rather than made-up ones.
 *
 * <h2>Harness limitations</h2>
 * <ol>
 *   <li><b>Only the first failing redeemer is reported.</b> The evaluator returns a single
 *       {@code RedeemerError { tag, index, .. }} and stops; a transaction with two independent
 *       faults names only the lower-indexed one. So the two directions read differently and are not
 *       symmetric:
 *       <ul>
 *         <li>a <em>successful</em> outcome proves every redeemer passed;</li>
 *         <li>a <em>failed</em> outcome proves the named redeemer refused, and proves nothing at all
 *             about any redeemer <em>after</em> it — those were never run.</li>
 *       </ul>
 *       A test that concludes "validator X accepted this" from a failure elsewhere is only sound
 *       when X sits at a <em>lower</em> redeemer index than the reported one, and should assert that
 *       ordering rather than assume it. Naming the expected refuser is also the minimum bar for any
 *       negative test here: a bare "it failed" is satisfied by a fault anywhere earlier.</li>
 *   <li><b>{@code maxTxSize} is unreal</b>, for the reason above. Nothing else about the rig is.</li>
 *   <li><b>No ledger rules are checked</b> — this is script evaluation only. Value conservation,
 *       fees, min-ada, collateral and witness completeness are the ledger's job and are not
 *       exercised here.</li>
 * </ol>
 */
final class EvalFixtures {

    private EvalFixtures() {
    }

    /**
     * Preview-ish Conway parameters with a {@code maxTxSize} big enough for witness-attached
     * scripts. See the class javadoc for why that is sanctioned here and nowhere else.
     */
    static ProtocolParamsSupplier protocolParams() {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        costModels.put("PlutusV3", Arrays.stream(CostModelUtil.PlutusV3CostModel.getCosts())
                .boxed().toList());

        ProtocolParams params = ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .maxTxSize(1_000_000)
                .maxValSize("5000")
                .coinsPerUtxoSize("4310")
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .minFeeRefScriptCostPerByte(new BigDecimal("15"))
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .costModelsRaw(costModels)
                .build();
        return () -> params;
    }

    /**
     * The seven validators {@link LoansContractRegistry} retains applied code for, keyed by their
     * own hash — the fallback the evaluator uses for a script that is neither in the witness set nor
     * carried by a reference input.
     * <p>
     * <b>Consulted, as of 2026-08-16 (T-016 S1) — this javadoc used to say it never was.</b> Most
     * tests here build with {@code ReferenceScripts.none()} and resolve the six validators from the
     * witness set, but the reference-script test builds with the published preview coordinates and an
     * empty witness set, so every script reaches the evaluator through this supplier. Both claims are
     * mutation-proven rather than asserted: emptying this supplier turns that one test red
     * ({@code RequiredRedeemersMismatch}) and leaves the other five green, so the two resolution paths
     * are genuinely independent. The keying is by {@link PlutusScript#getScriptHash()} rather than by
     * the registry's field names, which is load-bearing: serving the asset-manager script under the
     * loan coordinate fails with {@code RequiredRedeemersMismatch {missing, extra}} instead of
     * silently evaluating the wrong validator.
     */
    static ScriptSupplier scriptSupplier(LoansContractRegistry registry) {
        Map<String, PlutusScript> byHash = new LinkedHashMap<>();
        List<PlutusScript> scripts = List.of(
                registry.getLoanScript(),
                registry.getLoanSpendScript(),
                registry.getLenderManagerScript(),
                registry.getLenderManagerSpendScript(),
                registry.getLoanClaimActionScript(),
                registry.getLmLiquidateActionScript(),
                registry.getAssetManagerScript());
        for (PlutusScript script : scripts) {
            try {
                byHash.put(HexUtil.encodeHexString(script.getScriptHash()), script);
            } catch (Exception e) {
                throw new IllegalStateException("cannot hash an applied loans-v4 script", e);
            }
        }
        return scriptHash -> Optional.ofNullable(byHash.get(scriptHash));
    }

    /**
     * What the UPLC machine said. A rejected transaction and a clean one are both normal outcomes
     * here — the adversarial case needs the rejection — so the evaluator's exception is captured
     * rather than propagated, with its whole message chain kept in {@link #detail}: that text is
     * the only thing that says <em>which</em> validator refused and why.
     */
    record Outcome(boolean successful, String detail, List<EvaluationResult> results) {
    }

    /**
     * Evaluates the transaction against the real scripts and returns one {@link EvaluationResult}
     * per redeemer, or fails the test with whatever the UPLC machine said.
     * <p>
     * {@code universe} is both the {@link UtxoSupplier} the evaluator resolves inputs and reference
     * inputs from and the explicit {@code inputUtxos} set, so nothing is ever looked up remotely.
     */
    static List<EvaluationResult> evaluate(Transaction transaction, List<Utxo> universe,
                                           LoansContractRegistry registry) {
        Outcome outcome = evaluateRaw(transaction, universe, registry);
        if (!outcome.successful()) {
            throw new AssertionError("script evaluation failed: " + outcome.detail());
        }
        return outcome.results();
    }

    /** As above, but handing back the outcome — for the cases that must <em>not</em> pass. */
    static Outcome evaluateRaw(Transaction transaction, List<Utxo> universe,
                               LoansContractRegistry registry) {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(universe);
        AikenTransactionEvaluator evaluator = new AikenTransactionEvaluator(
                utxoSupplier, protocolParams(), scriptSupplier(registry), SlotConfigs.preview());
        Set<Utxo> inputUtxos = new LinkedHashSet<>(universe);
        try {
            Result<List<EvaluationResult>> result = evaluator.evaluateTx(transaction.serialize(), inputUtxos);
            return new Outcome(result.isSuccessful(), String.valueOf(result.getResponse()),
                    result.isSuccessful() ? result.getValue() : List.of());
        } catch (Exception e) {
            return new Outcome(false, causeChain(e), List.of());
        }
    }

    private static String causeChain(Throwable e) {
        StringBuilder detail = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            detail.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            if (t.getCause() == t) {
                break;
            }
        }
        return detail.toString();
    }
}
