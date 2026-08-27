package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.quicktx.Verifier;
import com.bloxbean.cardano.client.quicktx.VerifierException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.List;

/**
 * T-059 — <b>the tank asserts the structure of the transaction it built, before that transaction
 * reaches the wire.</b>
 *
 * <h2>Why this exists, and why it is late</h2>
 * Every guarantee the lending-v4 review added — V5, the change-output enumeration, the derived
 * collateral ceiling — lives on the <b>preview</b> paths. {@code ScheduledTransactionService} is the
 * <b>only path that runs on mainnet</b> and had <b>no structural assertion of any kind</b>. We
 * hardened the paths that cannot lose real money and left the one that can undefended.
 *
 * <h2>⚑ And the API is finally the one its name promises</h2>
 * The tank submits through {@code completeAndWait()}, so {@code withVerifier} — consulted inside
 * {@code complete()} — is <b>genuinely reached here.</b> After a week of finding APIs that could not
 * do the job their names describe ({@code removeDuplicateScriptWitnesses} too late in the pipeline,
 * auto-collateral unguardable, {@code withVerifier} itself dead on the two {@code build()}-only
 * liquidation builders), <b>this is the one place the obvious tool is the right one.</b>
 * <p>
 * ⚠ Note it <b>composes</b> ({@code QuickTxBuilder:863-868} uses {@code andThen}) rather than
 * replacing, unlike {@code preBalanceTx}, which is a setter whose second call silently discards the
 * first. Two hooks on the same builder, opposite semantics.
 *
 * <h2>⛔ One check is DELIBERATELY OBSERVE-ONLY, and that is the whole design</h2>
 * {@code inputTankIndex} is hardcoded to zero while the ledger re-sorts inputs lexicographically —
 * T-066. <b>Asserting it hard would embed the very hypothesis under test:</b> if the on-chain
 * validator does <em>not</em> read that field as an index into the sorted inputs, a hard assertion
 * would refuse the half of transactions that currently succeed, turning a suspected 50% failure into
 * a certain one. <b>So it logs and does not throw</b> — and in doing so it becomes the instrument
 * that settles T-066 from the inside, reporting on every real transaction whether index zero was in
 * fact the tank input.
 */
@Slf4j
public final class TankStructureVerifier {

    private TankStructureVerifier() {
    }

    /**
     * @param tankUtxo         the tank UTxO being spent
     * @param paramsRefInput   the parameters reference input
     * @param stakerRefInput   the staker reference input
     * @param paramsIndex      what the redeemer claims for the parameters reference input
     * @param stakingIndex     what the redeemer claims for the staker reference input
     * @param inputTankIndex   what the redeemer claims for the tank input — <b>checked but not
     *                         enforced</b>, see the class javadoc
     * @param payeeAddress     the datum's destination
     * @param payeeLovelace    lovelace the payee output must carry at least
     * @param rewardsAddress   the parameters' rewards address
     * @param rewardsLovelace  lovelace the rewards output must carry at least
     */
    public static Verifier of(Utxo tankUtxo,
                              TransactionInput paramsRefInput,
                              TransactionInput stakerRefInput,
                              BigInteger paramsIndex,
                              BigInteger stakingIndex,
                              BigInteger inputTankIndex,
                              String payeeAddress,
                              BigInteger payeeLovelace,
                              String rewardsAddress,
                              BigInteger rewardsLovelace) {
        return txn -> {
            var body = txn.getBody();

            // ⛔ PINS CCL_PREPENDED_OUTPUTS = 0 FOR THIS PATH. cardano-client-lib prepends a dummy
            // output whenever a transaction carries withdrawals (StakeTx:292-295). The tank has none
            // today, so output positions are plain emission order — and that is exactly the kind of
            // "true today" that stops being true the moment someone adds a withdrawal here.
            if (body.getWithdrawals() != null && !body.getWithdrawals().isEmpty()) {
                throw new VerifierException("the tank transaction now carries withdrawals, so "
                        + "cardano-client-lib prepends a dummy output and every output position in "
                        + "this verifier is off by one. Re-derive them before removing this check.");
            }

            List<TransactionInput> sortedInputs = body.getInputs().stream()
                    .sorted(new TransactionInputComparator()).toList();
            List<TransactionInput> sortedRefInputs = body.getReferenceInputs().stream()
                    .sorted(new TransactionInputComparator()).toList();

            requirePointsAt(sortedRefInputs, paramsIndex, paramsRefInput, "referenceParamsIndex");
            requirePointsAt(sortedRefInputs, stakingIndex, stakerRefInput, "referenceStakingIndex");

            TransactionInput tankInput = new TransactionInput(tankUtxo.getTxHash(), tankUtxo.getOutputIndex());
            if (!sortedInputs.contains(tankInput)) {
                throw new VerifierException("the tank utxo " + tankUtxo.getTxHash() + "#"
                        + tankUtxo.getOutputIndex() + " is not among the transaction's inputs");
            }

            requireOutput(body.getOutputs(), payeeAddress, payeeLovelace, "payee");
            requireOutput(body.getOutputs(), rewardsAddress, rewardsLovelace, "rewards");

            // ⛔ OBSERVE ONLY — T-066. See the class javadoc: enforcing this would assume the answer.
            int actual = sortedInputs.indexOf(tankInput);
            if (actual != inputTankIndex.intValueExact()) {
                log.warn("T-066 OBSERVATION: inputTankIndex claims {} but the tank input is at {} in "
                                + "the CANONICALLY SORTED inputs (tank {}#{}, {} inputs total). The "
                                + "redeemer field is hardcoded; if the validator reads it as a sorted "
                                + "index, this transaction should fail on chain. NOT enforced here — "
                                + "recording it is how we find out.",
                        inputTankIndex, actual, tankUtxo.getTxHash(), tankUtxo.getOutputIndex(),
                        sortedInputs.size());
            } else {
                log.debug("T-066 OBSERVATION: inputTankIndex {} matches the sorted position", actual);
            }
        };
    }

    private static void requirePointsAt(List<TransactionInput> sorted, BigInteger claimed,
                                        TransactionInput expected, String field) {
        int index = claimed.intValueExact();
        if (index < 0 || index >= sorted.size()) {
            throw new VerifierException(field + " = " + index + " is outside the "
                    + sorted.size() + " reference inputs");
        }
        if (!sorted.get(index).equals(expected)) {
            throw new VerifierException(field + " = " + index + " points at "
                    + sorted.get(index).getTransactionId() + "#" + sorted.get(index).getIndex()
                    + ", not at " + expected.getTransactionId() + "#" + expected.getIndex());
        }
    }

    /**
     * At least, not exactly: cardano-client-lib tops an output up to the min-ada floor, so an equality
     * check here would fail on a correct transaction. The failure this catches is paying the wrong
     * address or paying too little — never paying slightly more because the ledger demanded it.
     */
    private static void requireOutput(List<TransactionOutput> outputs, String address,
                                      BigInteger atLeastLovelace, String which) {
        boolean found = outputs.stream()
                .filter(o -> address.equals(o.getAddress()))
                .anyMatch(o -> o.getValue().getCoin().compareTo(atLeastLovelace) >= 0);
        if (!found) {
            throw new VerifierException("no " + which + " output at " + address
                    + " carrying at least " + atLeastLovelace + " lovelace");
        }
    }
}
