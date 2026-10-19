package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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

    // ---- token-principal nomination (ADDITIVE — Part 2 of the token-principals slice) -------------
    //
    // A pay-in-advance liquidation on a TOKEN principal must front that token, not ada, so the utxo
    // that funds it must carry the principal asset alongside enough ada for the fee ceiling and the
    // min-ada rider on the lender's output. `nominable` above requires `getAmount().size() == 1` —
    // ADA ONLY — so it filters out exactly the multi-asset (principal + min-ada) utxo this shape
    // needs; that rule is untouched here, because the 2026-08-24 incident it defends against is about
    // the plain SPEND/collateral input, which stays ada-only on every path. This is a SEPARATE,
    // ADDITIVE rule for a SEPARATE purpose.

    /**
     * Carries at least one unit of {@code principal} (a non-ada token), no reference script, no datum
     * of either kind — the same datum/reference-script exclusions {@link #nominable} applies, for the
     * same reason (a datum-bearing or reference-script-carrying utxo is not a plain spendable input).
     * <b>Does not require ada-only</b> — the whole point is a utxo carrying the token AND its min-ada
     * rider together, exactly the shape a real funding transaction produces.
     */
    public static boolean nominableForToken(Utxo utxo, AssetType principal) {
        if (utxo.getReferenceScriptHash() != null || utxo.getInlineDatum() != null
                || utxo.getDataHash() != null || utxo.getAmount() == null) {
            return false;
        }
        String unit = principal.toUnit();
        return utxo.getAmount().stream().anyMatch(a -> unit.equalsIgnoreCase(a.getUnit()));
    }

    /** The quantity of {@code asset} an amount list holds. Zero if it holds none. */
    public static BigInteger tokenQuantityOf(Utxo utxo, AssetType asset) {
        if (utxo.getAmount() == null) {
            return BigInteger.ZERO;
        }
        String unit = asset.toUnit();
        return utxo.getAmount().stream()
                .filter(a -> unit.equalsIgnoreCase(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * The smallest {@link #nominableForToken} UTxO holding at least {@code requiredPrincipal} of
     * {@code principal} AND at least {@code requiredAda} lovelace alongside it, or empty when none
     * does. Ordered by the PRINCIPAL quantity — the scarcer resource on this path — the same
     * smallest-that-suffices rule {@link #smallestSufficient} applies to ada.
     *
     * @param requiredAda a CEILING (fee ceiling plus the min-ada rider), never an estimate — see the
     *                    class javadoc on why under-estimating is the dangerous direction
     */
    public static Optional<Utxo> smallestSufficientToken(List<Utxo> utxos, AssetType principal,
                                                          BigInteger requiredPrincipal,
                                                          BigInteger requiredAda) {
        return utxos.stream()
                .filter(u -> nominableForToken(u, principal))
                .filter(u -> tokenQuantityOf(u, principal).compareTo(requiredPrincipal) >= 0)
                .filter(u -> LedgerCeilings.lovelaceOf(u).compareTo(requiredAda) >= 0)
                .min(Comparator.comparing(u -> tokenQuantityOf(u, principal)));
    }

    /**
     * ⚠ A conservative CEILING for the min-ada rider a token-principal lender output needs, sized for
     * wallet-selection headroom rather than computed from live protocol parameters — {@code
     * LedgerCeilings} has no min-ada helper and this class does not derive one (out of this slice's
     * file allowlist). Over-estimating is the safe direction here ({@code LiquidationExecutor}'s own
     * javadoc, and this class's): a single-asset min-ada output on {@code coinsPerUtxoSize} 4310
     * measures well under 1.5 ada in every fixture seen so far, so 2 ada leaves real headroom rather
     * than sitting on the edge.
     */
    public static final BigInteger TOKEN_PRINCIPAL_MIN_ADA_CEILING = BigInteger.valueOf(2_000_000L);

    /**
     * The ada a token-principal funding UTxO must carry beside the token: the fee ceiling plus
     * {@link #TOKEN_PRINCIPAL_MIN_ADA_CEILING}. One formula for the selector ({@code nominate()}) and
     * for the balance the market gate is shown, so the two can never describe different wallets. An
     * unavailable fee ceiling contributes zero — the floor then understates, which
     * {@link TokenHolding#adaFloorNote} says out loud.
     */
    public static BigInteger tokenPrincipalRequiredAda(Optional<BigInteger> feeCeiling) {
        return feeCeiling.orElse(BigInteger.ZERO).add(TOKEN_PRINCIPAL_MIN_ADA_CEILING);
    }

    /**
     * FAB-128 — how much of a token the wallet holds, split by whether a token-principal selection
     * could actually spend it.
     *
     * @param total         every unit of the token in the wallet, whatever UTxO it sits in
     * @param spendable     in {@link #nominableForToken} UTxOs carrying at least {@code requiredAda} —
     *                      the only figure the market gate may be shown
     * @param belowAdaFloor in {@link #nominableForToken} UTxOs carrying less than {@code requiredAda}:
     *                      the token a consolidation would make spendable. A datum- or
     *                      reference-script-carrying UTxO is in neither, only in {@code total}.
     * @param requiredAda   the floor the split was made at
     */
    public record TokenHolding(BigInteger total, BigInteger spendable, BigInteger belowAdaFloor,
                               BigInteger requiredAda) {

        /**
         * Why a refusal that says "holds 0" is not the whole story — or {@code null} when it is: when
         * no token sits behind the ada floor, or the spendable balance already covers {@code required}.
         * The remedy is a claim and must be sufficient: consolidation is offered alone only when the
         * excluded token would cover the requirement, and otherwise the note names what to add.
         *
         * @param amount          renders a quantity of the token, unit included
         * @param required        what must be fronted, in the token's base units
         * @param feeCeilingKnown false when {@code requiredAda} was computed without a fee ceiling
         */
        public String adaFloorNote(Function<BigInteger, String> amount, BigInteger required,
                                   boolean feeCeilingKnown) {
            if (belowAdaFloor.signum() == 0 || spendable.compareTo(required) >= 0) {
                return null;
            }
            String adaFloorInAda = new BigDecimal(requiredAda).movePointLeft(6).stripTrailingZeros()
                    .toPlainString();
            String why = ("the wallet has %s in total, but %s of it sits in UTxOs carrying less than %s "
                    + "lovelace (%s ADA) each — the ada a build needs beside the token, the fee ceiling plus "
                    + "a 2 ADA min-ada rider%s — so the bot cannot spend it and does not count it.").formatted(
                    amount.apply(total), amount.apply(belowAdaFloor), requiredAda, adaFloorInAda,
                    feeCeilingKnown ? "" : " (the fee ceiling was unavailable this cycle, so this floor "
                            + "leaves it out and the real one is higher)");
            BigInteger counted = spendable.add(belowAdaFloor);
            if (counted.compareTo(required) >= 0) {
                return why + (" Consolidate the token into one UTxO carrying at least %s lovelace; the "
                        + "wallet rebalancer does this automatically when it is enabled and this market is "
                        + "action: ANTICIPATE.").formatted(requiredAda);
            }
            return why + (" Even counted, %s would be short of the %s required: consolidate the token "
                    + "into one UTxO carrying at least %s lovelace and add at least %s; the wallet "
                    + "rebalancer consolidates automatically when it is enabled and this market is action: "
                    + "ANTICIPATE, but it cannot add funds.").formatted(amount.apply(counted),
                    amount.apply(required), requiredAda, amount.apply(required.subtract(counted)));
        }
    }

    /**
     * Split the wallet's holding of {@code token} at {@code requiredAda}. {@code spendable} is
     * byte-for-byte the balance the executor showed the market gate before FAB-128: {@link
     * #nominableForToken} AND {@code lovelaceOf >= requiredAda} — count ONLY UTxOs the selector could
     * actually pick (F7, round 2: a 2,000 USDM + 1.4 ADA UTxO passes {@code nominableForToken} alone
     * and would inflate the balance by a candidate the wallet could never fund — M13).
     */
    public static TokenHolding tokenHolding(List<Utxo> utxos, AssetType token, BigInteger requiredAda) {
        BigInteger total = BigInteger.ZERO;
        BigInteger spendable = BigInteger.ZERO;
        BigInteger belowAdaFloor = BigInteger.ZERO;
        for (Utxo utxo : utxos) {
            BigInteger quantity = tokenQuantityOf(utxo, token);
            total = total.add(quantity);
            if (!nominableForToken(utxo, token)) {
                continue;
            }
            if (LedgerCeilings.lovelaceOf(utxo).compareTo(requiredAda) >= 0) {
                spendable = spendable.add(quantity);
            } else {
                belowAdaFloor = belowAdaFloor.add(quantity);
            }
        }
        return new TokenHolding(total, spendable, belowAdaFloor, requiredAda);
    }

    /** The largest nominable-for-token holding of {@code principal}, for diagnostics. */
    public static Optional<BigInteger> largestNominableToken(List<Utxo> utxos, AssetType principal) {
        return utxos.stream()
                .filter(u -> nominableForToken(u, principal))
                .map(u -> tokenQuantityOf(u, principal))
                .max(BigInteger::compareTo);
    }
}
