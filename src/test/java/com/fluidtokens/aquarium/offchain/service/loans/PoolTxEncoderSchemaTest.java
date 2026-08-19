package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks {@link PoolTxEncoder} against the contract's own schema, exactly the way
 * {@link RequestTxEncoderSchemaTest} pins {@link RequestTxEncoder} — same fixture, same helpers.
 * <p>
 * The pool types ({@code PoolDatum}, {@code PoolMintRedeemer}, {@code PoolWithdrawRedeemer} and its
 * {@code Action}, {@code BorrowData}, {@code PoolBorrowActionWithdrawRedeemer}, {@code CancelData},
 * {@code PoolCancelActionWithdrawRedeemer}, {@code BondRedeemer}) and the pool-manager types
 * ({@code PoolManagerDatum}, {@code PoolManagerMintRedeemer}, {@code PoolManagerWithdrawRedeemer} and
 * its {@code PoolManagerAction}, {@code CancelPoolManagerActionWithdrawRedeemer}) are all
 * <b>present</b> in the
 * {@code aiken build --include-all-types} oracle {@code /loans-v4/loans-v4-alltypes.plutus.json} at
 * pin {@code ff005fb} — the fixture is not stale for any of them, so it is the source of truth for
 * their constructor indices and field order, and nothing here falls back to the deployed blueprint.
 *
 * <h2>What this test does and does not cover</h2>
 * The same limitation {@link RequestTxEncoderSchemaTest} states applies verbatim: what follows pins
 * the <em>contract's</em> field order — the constructor indices and field titles in the schema
 * oracle, and that the record types this repo hands the encoder declare their components in that
 * same order. It reads not a single byte the encoder produces. That the encoder <em>writes</em> each
 * field into the matching slot is pinned only by the field-index sentinel goldens in
 * {@link PoolTxEncoderTest}.
 */
class PoolTxEncoderSchemaTest {

    private static JsonNode definitions() {
        try (InputStream is = PoolTxEncoderSchemaTest.class
                .getResourceAsStream("/loans-v4/loans-v4-alltypes.plutus.json")) {
            return new ObjectMapper().readTree(Objects.requireNonNull(is)).get("definitions");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode constructor(String definition, int index) {
        for (JsonNode c : definitions().get(definition).get("anyOf")) {
            if (c.get("index").asInt() == index) {
                return c;
            }
        }
        throw new AssertionError("no constructor " + index + " on " + definition);
    }

    private static List<String> fieldTitles(JsonNode constructor) {
        var titles = new ArrayList<String>();
        constructor.get("fields").forEach(f -> titles.add(f.path("title").asText(null)));
        return titles;
    }

    @Test
    void poolDatumFieldOrderMatchesTheContract() {
        var poolDatum = constructor("fluidtokens/types/pool/PoolDatum", 0);
        var expected = List.of("permissionedConditionScriptHash", "extraData", "commonData",
                "lenderAuth", "lenderBondAddress", "lenderBondInlineDatumHash", "collateralOptions",
                "minCollateral", "minCollateralDivider", "dynamicCollateralPrice");
        assertEquals(expected, fieldTitles(poolDatum));
        assertEquals(expected.size(), PoolTxEncoder.PoolDatum.class.getRecordComponents().length);
    }

    @Test
    void poolMintRedeemerFieldOrderMatchesTheContract() {
        var mint = constructor("fluidtokens/types/pool/PoolMintRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "inputRef"), fieldTitles(mint));
    }

    @Test
    void poolWithdrawRedeemerFieldOrderMatchesTheContract() {
        var withdraw = constructor("fluidtokens/types/pool/PoolWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "action"), fieldTitles(withdraw));
    }

    /**
     * The {@code Action} enum's constructor indices — the pins behind {@link PoolTxEncoder}'s
     * {@code ACTION_*} constants. All four are fieldless, so nothing in the encoded bytes tells them
     * apart but the constructor tag; hence the explicit per-index title assertions.
     */
    @Test
    void poolActionConstructorIndicesMatchTheContract() {
        assertEquals("Cancel", constructor("fluidtokens/types/pool/Action",
                PoolTxEncoder.ACTION_CANCEL).get("title").asText());
        assertEquals("Borrow", constructor("fluidtokens/types/pool/Action",
                PoolTxEncoder.ACTION_BORROW).get("title").asText());
        assertEquals("SellLenderPosition", constructor("fluidtokens/types/pool/Action",
                PoolTxEncoder.ACTION_SELL_LENDER_POSITION).get("title").asText());
        assertEquals("Compound", constructor("fluidtokens/types/pool/Action",
                PoolTxEncoder.ACTION_COMPOUND).get("title").asText());
    }

    @Test
    void borrowDataFieldOrderMatchesTheContract() {
        var borrowData = constructor("fluidtokens/types/pool/BorrowData", 0);
        var expected = List.of("borrowerAddress", "outputWithLenderTokenIndex",
                "outputWithBorrowerTokenIndex", "principalOracleRefInputIndex", "chosenCollateralIndex",
                "chosenCollateralOracleRefInputIndex", "wantedPrincipalAmount", "poolId",
                "permissionedConditionWithdrawIndex");
        assertEquals(expected, fieldTitles(borrowData));
        assertEquals(expected.size(), PoolTxEncoder.BorrowData.class.getRecordComponents().length);
    }

    @Test
    void poolBorrowActionWithdrawRedeemerFieldOrderMatchesTheContract() {
        var redeemer = constructor("fluidtokens/types/pool/PoolBorrowActionWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "actionsForEachInput"), fieldTitles(redeemer));
    }

    @Test
    void cancelDataFieldOrderMatchesTheContract() {
        var cancelData = constructor("fluidtokens/types/pool/CancelData", 0);
        assertEquals(List.of("poolId"), fieldTitles(cancelData));
    }

    @Test
    void poolCancelActionWithdrawRedeemerFieldOrderMatchesTheContract() {
        var redeemer = constructor("fluidtokens/types/pool/PoolCancelActionWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "actionsForEachInput"), fieldTitles(redeemer));
    }

    // ---- the pool-manager types (T-024) ----------------------------------------------------------

    @Test
    void poolManagerDatumFieldOrderMatchesTheContract() {
        var datum = constructor("fluidtokens/types/pool_manager/PoolManagerDatum", 0);
        var expected = List.of("poolOwnerAuth", "compoudingFeePerMille");
        assertEquals(expected, fieldTitles(datum),
                "the upstream typo 'compoudingFeePerMille' is the contract's spelling, not ours");
        assertEquals(expected.size(), PoolTxEncoder.PoolManagerDatum.class.getRecordComponents().length);
    }

    @Test
    void poolManagerMintRedeemerFieldOrderMatchesTheContract() {
        var mint = constructor("fluidtokens/types/pool_manager/PoolManagerMintRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "poolWithdrawRedeemerIndex"), fieldTitles(mint));
    }

    @Test
    void poolManagerWithdrawRedeemerFieldOrderMatchesTheContract() {
        var withdraw = constructor("fluidtokens/types/pool_manager/PoolManagerWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "action"), fieldTitles(withdraw));
    }

    /**
     * The {@code PoolManagerAction} enum's constructor indices — the pins behind {@link PoolTxEncoder}'s
     * {@code PM_ACTION_*} constants. All three are fieldless, so nothing in the encoded bytes tells them
     * apart but the constructor tag. It is a <b>different enum from {@code pool.ak}'s {@code Action}</b>
     * with different numbering, which is exactly why each index is asserted against its own title: 0
     * means {@code Cancel} on one and {@code CancelPoolManager} on the other, and 2 means
     * {@code SellLenderPosition} on one and {@code CompoundLiquidity} on the other.
     */
    @Test
    void poolManagerActionConstructorIndicesMatchTheContract() {
        assertEquals("CancelPoolManager", constructor("fluidtokens/types/pool_manager/PoolManagerAction",
                PoolTxEncoder.PM_ACTION_CANCEL_POOL_MANAGER).get("title").asText());
        assertEquals("UpdatePoolManager", constructor("fluidtokens/types/pool_manager/PoolManagerAction",
                PoolTxEncoder.PM_ACTION_UPDATE_POOL_MANAGER).get("title").asText());
        assertEquals("CompoundLiquidity", constructor("fluidtokens/types/pool_manager/PoolManagerAction",
                PoolTxEncoder.PM_ACTION_COMPOUND_LIQUIDITY).get("title").asText());
    }

    @Test
    void cancelPoolManagerActionWithdrawRedeemerFieldOrderMatchesTheContract() {
        var redeemer = constructor(
                "fluidtokens/types/pool_manager/CancelPoolManagerActionWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "poolWithdrawRedeemerIndex",
                "poolManagerNFTAssetNames"), fieldTitles(redeemer));
    }

    @Test
    void bondRedeemerFieldOrderMatchesTheContract() {
        var bondRedeemer = constructor("bond/BondRedeemer", 0);
        assertEquals(List.of("inputRefs"), fieldTitles(bondRedeemer));
    }
}
