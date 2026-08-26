package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Utxo;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <b>Which wallet UTxO to nominate as a transaction's own input — smallest that suffices.</b>
 *
 * <h2>Why an amount has to come in from outside</h2>
 * Giovanni's rule 5: <i>"the amount of ada a liquidator might spend might be little, just tx fee or
 * something, so a 5 ada utxo would be perfect, but if this is a loan whose principal needs to be
 * repaid by the liquidator, then you will need a larger ada amount."</i> The requirement is a
 * property of the <em>transaction</em>, not of the wallet, so this class never tries to infer it —
 * <b>the caller passes it.</b> That also keeps liquidation types out of here entirely.
 *
 * <h2>Why SMALLEST-that-suffices, and not largest</h2>
 * Taking the largest is not wrong, it is wasteful in a way that compounds: the biggest UTxO gets
 * consumed by whichever candidate happens to run first, so a fee-only liquidation can spend the one
 * input a principal-repaying candidate needed. Smallest-that-suffices leaves the large inputs for
 * the transactions that actually require them.
 *
 * <p>⚠ <b>But "suffices" has to be computed conservatively, because under-estimating is the
 * dangerous direction.</b> The nominated UTxO is the only wallet input the builder declares, and
 * cardano-client-lib evaluates script cost <em>before</em> balancing can add another — so a remote
 * evaluator is shown a transaction whose declared inputs must already cover its outputs. Blockfrost
 * refuses such a transaction with {@code EvaluationFailure} and an <b>empty</b> {@code ScriptFailures}
 * map, which reads as "a script said no" rather than "you are short". Measured on preview
 * 2026-08-24: a wallet holding 776 ada across 14 UTxOs had a 5-ada one first in the list, and every
 * convert liquidation failed on it while a 58-ada and a 38-ada UTxO sat unused. Over-estimating
 * costs a slightly larger input; under-estimating costs the whole candidate with an unreadable
 * reason.
 *
 * <h2>Why these four clauses</h2>
 * Ada-only and reference-script-free are {@code ScheduledTransactionService}'s; the two datum
 * clauses are the liquidation loop's. {@code LiquidateTransactionBuilder} rejects a datum-carrying
 * wallet UTxO ({@code WALLET_UTXO_NOT_ADA_ONLY}), and a single-asset ada UTxO with an inline datum
 * is routine in a bot wallet (a DEX order refund, an airdrop claim) — nominating one would refuse
 * every candidate of every cycle for as long as it sat there.
 *
 * <p>⛔ <b>{@code ScheduledTransactionService} deliberately does NOT use this class.</b> The tank
 * processor filters on the first two clauses only, and tightening it to these four would be a
 * behaviour change on the one path operators run on <b>mainnet</b>, where lending is disabled — a
 * decision of its own, not a side effect of a liquidation ticket. The duplication is the smaller
 * cost.
 */
public final class WalletInputSelection {

    private WalletInputSelection() {
    }

    /** Ada only, no reference script, no datum of either kind. */
    public static boolean nominable(Utxo utxo) {
        return utxo.getAmount().size() == 1
                && utxo.getReferenceScriptHash() == null
                && utxo.getInlineDatum() == null
                && utxo.getDataHash() == null;
    }

    /**
     * The smallest nominable UTxO holding at least {@code requiredLovelace}, or empty when none does.
     *
     * @param requiredLovelace what this transaction needs the nominated input to cover on its own —
     *                         a ceiling, not an estimate; see the class javadoc on why
     *                         under-estimating is the dangerous direction
     */
    public static Optional<Utxo> smallestSufficient(List<Utxo> utxos, BigInteger requiredLovelace) {
        return utxos.stream()
                .filter(WalletInputSelection::nominable)
                .filter(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(requiredLovelace) >= 0)
                .min(Comparator.comparing(LedgerCeilings::lovelaceOf));
    }

    /**
     * The largest nominable UTxO — <b>the fallback when the requirement cannot be computed at all.</b>
     * <p>
     * The requirement depends on live protocol parameters, and a parameter fetch can fail. When it
     * does, this is what the loop did before T-052 and it is never <em>wrong</em>, only wasteful:
     * the largest input covers the transaction wherever the wallet can cover it at all. Degrading to
     * it keeps a parameter outage from turning into a liquidation outage — and, specifically, keeps
     * the {@code TX_TOO_LARGE} veto path reachable, which is where an unfetchable {@code maxTxSize}
     * is supposed to be reported.
     */
    public static Optional<Utxo> largest(List<Utxo> utxos) {
        return utxos.stream()
                .filter(WalletInputSelection::nominable)
                .max(Comparator.comparing(LedgerCeilings::lovelaceOf));
    }

    /**
     * The largest nominable holding, for diagnostics. A refusal that says only "nothing qualified"
     * cannot tell an empty wallet from a wallet whose entire contents are ineligible, and those need
     * opposite responses from an operator.
     */
    public static Optional<BigInteger> largestNominable(List<Utxo> utxos) {
        return utxos.stream()
                .filter(WalletInputSelection::nominable)
                .map(LedgerCeilings::lovelaceOf)
                .max(BigInteger::compareTo);
    }
}
