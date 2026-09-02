package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.List;

/**
 * The seven withdraw redeemers of a compound transaction, encoded exactly as the validators at
 * {@code e0b818e} destructure them (findings §22.2).
 *
 * <p>Encoding only — no chain access, no builder state — so the shapes can be pinned against the
 * blueprint's alltypes oracle without standing anything up.
 *
 * <p>⛔ Two of these are <b>byte-indistinguishable</b>, and they are the two that matter:
 * {@link #lenderManagerWithdraw} and {@link #poolManagerWithdraw} both encode as
 * {@code d8799f02d87b80ff}, because {@code Compound} is alternative 2 of {@code LenderManagerAction}
 * and {@code CompoundLiquidity} is alternative 2 of {@code PoolManagerAction}. <b>Those are exactly
 * the two whose positions {@link #compoundLiquidity} cites</b> — so anything selecting them by shape
 * picks whichever sorts first and {@code pm_compound_liquidity} validates a tag it was never meant to
 * read. CCL trap 14: identify a redeemer by the position of its script purpose, never by its data.
 *
 * <p>(The trap was distilled from {@code PoolWithdrawRedeemer} vs {@code PoolManagerWithdrawRedeemer};
 * on THIS path those two differ, because the pool's {@code Compound} is alternative 3. The colliding
 * pair moves with the action — which is why {@code CompoundTxEncoderTest} pins it here rather than
 * relying on the general warning.)
 */
final class CompoundTxEncoder {

    private CompoundTxEncoder() {
    }

    /** {@code Action} in {@code types/pool.ak}: Cancel, Borrow, SellLenderPosition, Compound. */
    static final int POOL_ACTION_COMPOUND = 3;

    /** {@code PoolManagerAction}: CancelPoolManager, UpdatePoolManager, CompoundLiquidity. */
    static final int POOL_MANAGER_ACTION_COMPOUND_LIQUIDITY = 2;

    /**
     * {@code LenderManagerAction}: WithdrawBonds, Liquidate, Compound, LiquidateAndPayInAdvance,
     * LiquidateAndConvert, LiquidatePayInAdvanceAndCompound, …
     */
    static final int LENDER_MANAGER_ACTION_COMPOUND = 2;

    /** (a) {@code AssetManagerWithdrawRedeemer{configRefInputIndex}}. */
    static PlutusData assetManagerWithdraw(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex));
    }

    /** (b) {@code LenderManagerWithdrawRedeemer{configRefInputIndex, action: Compound}}. */
    static PlutusData lenderManagerWithdraw(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                constr(LENDER_MANAGER_ACTION_COMPOUND));
    }

    /**
     * (c) {@code LMCompoundWithdrawRedeemer{configRefInputIndex, poolIds, poolInputIndexes,
     * lenderBondInputIndexes}}.
     *
     * <p>⛔ {@code poolInputIndexes} and {@code lenderBondInputIndexes} are positions in the
     * <b>FILTERED</b> input projections {@code get_inputs_from_smart_credential} produces — never
     * positions in {@code tx.inputs} (findings §22.3).
     */
    static PlutusData lmCompound(long configRefInputIndex,
                                 List<String> poolIdsHex,
                                 List<Long> poolInputIndexes,
                                 List<Long> lenderBondInputIndexes) {
        return constr(0,
                BigIntPlutusData.of(configRefInputIndex),
                list(poolIdsHex.stream().map(id -> (PlutusData) bytes(id)).toList()),
                list(poolInputIndexes.stream().map(i -> (PlutusData) BigIntPlutusData.of(i)).toList()),
                list(lenderBondInputIndexes.stream().map(i -> (PlutusData) BigIntPlutusData.of(i)).toList()));
    }

    /** (d) {@code PoolWithdrawRedeemer{configRefInputIndex, action: Compound}}. */
    static PlutusData poolWithdraw(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(POOL_ACTION_COMPOUND));
    }

    /**
     * (e) {@code PoolCompoundActionWithdrawRedeemer{configRefInputIndex, actionsForEachInput}},
     * where each action is {@code CompoundData{poolId}}.
     */
    static PlutusData poolCompoundAction(long configRefInputIndex, List<String> poolIdsHex) {
        return constr(0,
                BigIntPlutusData.of(configRefInputIndex),
                list(poolIdsHex.stream().map(id -> (PlutusData) constr(0, bytes(id))).toList()));
    }

    /** (f) {@code PoolManagerWithdrawRedeemer{configRefInputIndex, action: CompoundLiquidity}}. */
    static PlutusData poolManagerWithdraw(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                constr(POOL_MANAGER_ACTION_COMPOUND_LIQUIDITY));
    }

    /**
     * (g) {@code CompoundLiquidityActionWithdrawRedeemer{poolWithdrawRedeemerIndex,
     * lenderManagerWithdrawRedeemerIndex}}.
     *
     * <p>⛔ Both fields index {@code self.redeemers} — the canonically ordered redeemer list of the
     * FINISHED body, not any list this builder holds. {@code pm_compound_liquidity} authorises
     * nothing of its own and gates purely on the action tags it finds at these two positions
     * (findings D-17, §22.2), so a wrong index here can <b>pass while authorising the wrong
     * sibling</b>. It is the one index in the transaction that can be wrong and green, which is why
     * the builder re-derives both from the built body and refuses on mismatch.
     */
    static PlutusData compoundLiquidity(long poolWithdrawRedeemerIndex,
                                        long lenderManagerWithdrawRedeemerIndex) {
        return constr(0,
                BigIntPlutusData.of(poolWithdrawRedeemerIndex),
                BigIntPlutusData.of(lenderManagerWithdrawRedeemerIndex));
    }

    // ---- primitives -------------------------------------------------------------------------

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.builder().plutusDataList(List.of(fields)).build())
                .build();
    }

    private static ListPlutusData list(List<PlutusData> items) {
        return ListPlutusData.builder().plutusDataList(List.copyOf(items)).build();
    }

    private static BytesPlutusData bytes(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }
}
