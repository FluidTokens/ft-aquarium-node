package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Upper bounds <b>derived from the protocol parameters</b>, for the decisions that must be taken
 * before a transaction exists.
 *
 * <h2>Why this class is not a constant</h2>
 * Fee-dependent requirements — how much collateral to pledge, how large a wallet input must be —
 * have to be settled <em>before</em> balancing, and the fee is not known until <em>after</em> it.
 * The tempting answer is a plausible number. <b>cardano-client-lib took it:
 * {@code DEFAULT_COLLATERAL_AMT = Amount.ada(5.0)}, hardcoded at {@code QuickTxBuilder:65}, is how it
 * decides how much collateral to select.</b> A hardcoded value that happens to be generous is still
 * not an answer, and it does not move when the chain does.
 *
 * <p>The ledger supplies a real bound instead: a transaction cannot exceed {@code maxTxSize} bytes,
 * nor {@code maxTxExMem}/{@code maxTxExSteps} of execution budget, so the fee it would pay at all
 * three limits at once is a ceiling on any fee the chain will accept.
 *
 * <p>⚠ <b>Ceilings, not estimates.</b> Nothing here predicts what a transaction will cost — each value
 * is a bound no accepted transaction can exceed, so a decision taken against it is safe by
 * construction and stays safe when the parameters change.
 */
public final class LedgerCeilings {

    private LedgerCeilings() {
    }

    /** The lovelace an amount list holds. Zero if it holds none. */
    public static BigInteger lovelaceOf(com.bloxbean.cardano.client.api.model.Utxo utxo) {
        return utxo.getAmount() == null ? BigInteger.ZERO : utxo.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(com.bloxbean.cardano.client.api.model.Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * The largest fee this ledger would accept from any transaction: the size fee at {@code maxTxSize},
     * plus the constant, plus the execution budget priced at both maxima.
     *
     * <p>On preview, 2026-08: ≈ 2,405,077 lovelace.
     */
    public static BigInteger maxPossibleFee(ProtocolParams params) {
        return BigInteger.valueOf((long) params.getMinFeeA() * params.getMaxTxSize())
                .add(BigInteger.valueOf(params.getMinFeeB()))
                .add(params.getPriceMem().multiply(new BigDecimal(params.getMaxTxExMem()))
                        .setScale(0, RoundingMode.CEILING).toBigInteger())
                .add(params.getPriceStep().multiply(new BigDecimal(params.getMaxTxExSteps()))
                        .setScale(0, RoundingMode.CEILING).toBigInteger());
    }

    /**
     * The most collateral any acceptable transaction could require: {@link #maxPossibleFee} ×
     * {@code collateral_percent}, rounded up.
     *
     * <p>⚠ The ceiling is <b>CEILING</b> because the ledger's requirement is one — CIP-0040 gives it as
     * {@code quot(txfee × collateralPercent, 100)} under a {@code ≥} rule, and cardano-client-lib
     * declares {@code RoundingMode.CEILING}. Rounding down would understate it by a lovelace, which is
     * exactly enough to produce a transaction no node can parse.
     *
     * <p>On preview, 2026-08: ≈ 3,607,616 lovelace — <b>below</b> cardano-client-lib's hardcoded 5 ADA.
     */
    public static BigInteger maxPossibleCollateral(ProtocolParams params) {
        return maxPossibleFee(params).multiply(params.getCollateralPercent().toBigInteger())
                .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));
    }
}
