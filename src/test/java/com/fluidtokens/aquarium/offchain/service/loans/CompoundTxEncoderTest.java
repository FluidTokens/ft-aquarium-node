package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The seven compound withdraw redeemers, pinned against the types at {@code e0b818e}. */
class CompoundTxEncoderTest {

    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";

    private static String hex(com.bloxbean.cardano.client.plutus.spec.PlutusData d) throws Exception {
        return d.serializeToHex();
    }

    @Test
    void theSimpleWithdrawRedeemersEncodeAsTheValidatorsDestructureThem() throws Exception {
        // AssetManagerWithdrawRedeemer{configRefInputIndex} — one field.
        assertEquals("d8799f02ff", hex(CompoundTxEncoder.assetManagerWithdraw(2)));
        // LenderManagerWithdrawRedeemer{configRefInputIndex, action: Compound} — alt 2 of 6.
        assertEquals("d8799f02d87b80ff", hex(CompoundTxEncoder.lenderManagerWithdraw(2)));
        // PoolWithdrawRedeemer{configRefInputIndex, action: Compound} — alt 3 of 4.
        assertEquals("d8799f02d87c80ff", hex(CompoundTxEncoder.poolWithdraw(2)));
        // PoolManagerWithdrawRedeemer{configRefInputIndex, action: CompoundLiquidity} — alt 2 of 3.
        assertEquals("d8799f02d87b80ff", hex(CompoundTxEncoder.poolManagerWithdraw(2)));
    }

    /**
     * ⛔ CCL trap 14, pinned on the pair that actually collides here.
     *
     * <p>The trap was distilled from {@code PoolWithdrawRedeemer} vs {@code PoolManagerWithdrawRedeemer}.
     * On the COMPOUND path those two differ — the pool's {@code Compound} is alternative 3 while the
     * pool manager's {@code CompoundLiquidity} is alternative 2. The collision is between
     * <b>{@code LenderManagerWithdrawRedeemer} and {@code PoolManagerWithdrawRedeemer}</b>: both
     * encode as {@code Constr0[Int, Constr2[]]}, because {@code Compound} is alternative 2 of
     * {@code LenderManagerAction} and {@code CompoundLiquidity} is alternative 2 of
     * {@code PoolManagerAction}.
     *
     * <p>⚑ And these are precisely the two whose positions {@code CompoundLiquidityActionWithdrawRedeemer}
     * cites — so anything selecting them by shape picks whichever sorts first, and
     * {@code pm_compound_liquidity} would validate a tag it was never meant to read. Identify by
     * script purpose, never by data (findings §22.3).
     */
    @Test
    void theLenderManagerAndPoolManagerRedeemersAreByteIndistinguishable() throws Exception {
        assertEquals(hex(CompoundTxEncoder.lenderManagerWithdraw(2)),
                hex(CompoundTxEncoder.poolManagerWithdraw(2)),
                "if these ever differ, the shape-vs-purpose warning in §22.3 needs re-reading, not deleting");

        assertNotEquals(hex(CompoundTxEncoder.poolWithdraw(2)),
                hex(CompoundTxEncoder.poolManagerWithdraw(2)),
                "the pool's Compound is alternative 3, the pool manager's CompoundLiquidity is 2");
    }

    /** ⚠ poolId is 29 bytes, so its CBOR prefix is {@code 581d} — not the 28-byte {@code 581c}
     * every script hash in this codebase uses. A hand-built expectation that assumes 28 is wrong. */
    @Test
    void theListShapedRedeemersCarryTheirIndexesAndPoolIds() throws Exception {
        String lm = hex(CompoundTxEncoder.lmCompound(2, List.of(POOL_ID), List.of(0L), List.of(0L)));
        // Constr0[ 2, [poolId], [0], [0] ]
        assertEquals("d8799f029f581d" + POOL_ID + "ff9f00ff9f00ffff", lm);

        String pca = hex(CompoundTxEncoder.poolCompoundAction(2, List.of(POOL_ID)));
        // Constr0[ 2, [ Constr0[poolId] ] ]
        assertEquals("d8799f029fd8799f581d" + POOL_ID + "ffffff", pca);
    }

    @Test
    void compoundLiquidityCarriesTheTwoRedeemerPositions() throws Exception {
        assertEquals("d8799f0405ff", hex(CompoundTxEncoder.compoundLiquidity(4, 5)));
    }
}
