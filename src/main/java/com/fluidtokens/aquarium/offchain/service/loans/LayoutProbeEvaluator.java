package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;

import java.util.List;

/**
 * The evaluator handed to the <b>layout-probe</b> assembly, which prices nothing and is discarded.
 *
 * <h2>Why a probe cannot have a real evaluator</h2>
 * Both liquidation builders assemble twice (CCL trap 1): once with placeholder output indexes purely
 * to read the finished layout off the body, then again with the observed indexes. The probe's claim
 * redeemers name output indexes <b>no validator accepts</b>, so running a real evaluator against it
 * would fail by construction and refuse every batch. That is why the probe pass has always passed a
 * {@code null} evaluator.
 *
 * <h2>Why null is nevertheless not usable</h2>
 * Verified against the pinned cardano-client-lib <b>v0.7.2</b> source:
 * {@code ScriptBalanceTxProviders.balanceTx} re-runs script-cost evaluation whenever balancing
 * <b>added inputs</b> ({@code newInputSize != inputSize}), and on that branch it throws
 * {@code "Transaction evaluator is not set"} <b>unconditionally</b> —
 * {@code ignoreScriptCostEvaluationError} does not guard it, because that flag is consulted at the
 * other call site. So the probe survives only while the nominated wallet UTxO covers the whole
 * transaction on its own. The moment it does not, the build dies in the probe with an error about
 * evaluators that has nothing to do with the real problem.
 *
 * <h2>Why returning nothing is safe here, and only here</h2>
 * This evaluator reports success and costs <b>no</b> redeemer, so every redeemer on the probe keeps
 * cardano-client-lib's placeholder ex-units. On any transaction that could be submitted that would be
 * CCL trap 8 — the defect that cost a production incident on 2026-08-21. It is sound here for one
 * reason, and the reason is structural rather than a promise: <b>the probe body never leaves
 * {@code build()}</b>. It is a local, read only by the two {@code locate…} calls that observe output
 * positions, and then dropped; the returned transaction is always the second, priced assembly, whose
 * ex-units are measured and then asserted off the deserialised body. Should that ever stop being
 * true, this class becomes a live instance of trap 8 — so it is deliberately package-private and
 * named for the one assembly it belongs to.
 */
final class LayoutProbeEvaluator {

    private LayoutProbeEvaluator() {
    }

    /**
     * Satisfies cardano-client-lib's demand for an evaluator without pricing anything. Never hand
     * this to an assembly whose transaction is returned to a caller.
     */
    static final TransactionEvaluator INSTANCE = (cbor, inputUtxos) ->
            Result.success("layout probe: not priced, and this body is discarded")
                    .withValue(List.<EvaluationResult>of());
}
