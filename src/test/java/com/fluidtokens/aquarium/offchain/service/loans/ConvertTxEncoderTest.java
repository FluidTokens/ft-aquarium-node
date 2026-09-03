package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConvertTxEncoder}, pinned against three independent oracles rather than against a reading:
 * the <b>live mainnet chain</b> (the LP token, a real order datum), the <b>deployed blueprint</b>
 * (the redeemer's field order), and the <b>upstream Minswap type declarations</b> (the constructor
 * index of a variant no ordinary swap uses).
 *
 * <p>⚠ Every constant here fails <b>only on chain</b> if it is wrong: a bad hash function, a
 * swapped pair of redeemer fields or an off-by-one constructor index all produce a well-formed datum
 * that the validator rejects after the fee is spent.
 */
class ConvertTxEncoderTest {

    /** The live mainnet ADA/FLDT pair — the pool this project's real candidate would convert through. */
    private static final AssetType ADA = AssetType.ada();
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    private static final String MINSWAP_POOL_POLICY =
            "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

    /** The lender bond of the real candidate, `d832b78e…#3`. */
    private static final AssetType LENDER_BOND = new AssetType(
            "bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b",
            "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d");

    // ---- the lp asset name, proved against the live LP token -------------------------------------

    /**
     * ⛔ <b>THE ONE THAT PAYS FOR THIS FILE.</b> {@code compute_lp_asset_name} is SHA3-256 applied
     * twice. blake2b — the hash used everywhere else in this codebase, and the obvious guess — gives
     * {@code b3675eb2…}, which mainnet returns 404 for. SHA3 gives the asset below, which is the
     * <b>live LP token of the ADA/FLDT pool</b>.
     *
     * <p>A wrong hash here makes {@code lp_asset} wrong in every order datum, which fails
     * {@code equals_data} in phase 2 — after the fee is spent, with nothing in the error to point at
     * a hash function.
     */
    @Test
    void theLpAssetNameIsTheLiveMainnetOne() {
        assertEquals("bc53f5c2a8cf3ef64081d2ec8c74333d567fc7ef271c1b97d21fdd53a2c5c889",
                ConvertTxEncoder.computeLpAssetName(ADA, FLDT),
                "this is the asset name mainnet serves under the Minswap V2 pool policy for ADA/FLDT");

        assertNotEquals("b3675eb2c4bb1ae485cf78204d58d71882c876dc5c083b5cbce4e61a00e66b38",
                ConvertTxEncoder.computeLpAssetName(ADA, FLDT),
                "that is the blake2b answer, and mainnet has no such asset — if this ever matches, the "
                        + "hash function was changed to the intuitive-but-wrong one");
    }

    /**
     * ⚠ Order is the POOL's, not ours. The encoder must never sort the pair itself: it takes
     * {@code asset_a} then {@code asset_b} from the pool's own live datum, and a self-sorted guess
     * would disagree with any pool ordered the other way.
     */
    @Test
    void theHashIsOrderSensitiveSoTheCallerMustPassTheOrderThePoolDeclares() {
        assertNotEquals(ConvertTxEncoder.computeLpAssetName(ADA, FLDT),
                ConvertTxEncoder.computeLpAssetName(FLDT, ADA));
    }

    /** The pool NFT is a constant, and a different value from the computed lp name. */
    @Test
    void thePoolNftAssetNameIsTheAsciiConstantMsp() {
        assertEquals("MSP", new String(HexUtil.decodeHexString(ConvertTxEncoder.POOL_NFT_ASSET_NAME),
                StandardCharsets.UTF_8));
        assertNotEquals(ConvertTxEncoder.POOL_NFT_ASSET_NAME,
                ConvertTxEncoder.computeLpAssetName(ADA, FLDT),
                "the pool NFT and the LP token share a policy and NOT an asset name; confusing them "
                        + "puts the wrong asset in lp_asset and the wrong name in the redeemer");
    }

    // ---- the order datum, against a real one off the chain ---------------------------------------

    private static PlutusData liveOrderDatum() {
        try (InputStream is = ConvertTxEncoderTest.class
                .getResourceAsStream("/loans-v4/mainnet-minswap-order-datum.hex")) {
            String hex = new String(Objects.requireNonNull(is).readAllBytes(), StandardCharsets.UTF_8).trim();
            return PlutusData.deserialize(HexUtil.decodeHexString(hex));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<PlutusData> fields(PlutusData d) {
        return ((ConstrPlutusData) d).getData().getPlutusDataList();
    }

    /**
     * ⛔ The nine-field layout, confirmed against a <b>real order that a Minswap batcher actually
     * filled</b>. The upstream declaration says the same thing — but a declaration is a reading of
     * what should be, and this is what is.
     */
    @Test
    void theOrderDatumLayoutMatchesARealOneOffTheChain() {
        PlutusData live = liveOrderDatum();
        assertEquals(0, ((ConstrPlutusData) live).getAlternative());
        assertEquals(9, fields(live).size(), "canceller, refund_receiver, refund_receiver_datum, "
                + "success_receiver, success_receiver_datum, lp_asset, step, max_batcher_fee, "
                + "expiry_setting_opt");

        PlutusData ours = ConvertTxEncoder.orderDatum(
                new AuthorizationMethod.CardanoSignature("6fae7995cd41876a6110904d46a29596f834fc7d4a9f38e63e310330"),
                ConvertTxEncoder.plainScriptAddress("00000000000000000000000000000000000000000000000000000000"),
                "aa".repeat(32), "bb".repeat(32),
                new AssetType(MINSWAP_POOL_POLICY, ConvertTxEncoder.computeLpAssetName(ADA, FLDT)),
                ConvertTxEncoder.swapExactIn(false, BigInteger.valueOf(95_000_000L),
                        BigInteger.valueOf(20_000_000L)));

        assertEquals(fields(live).size(), fields(ours).size());

        // The two receivers are the SAME address in a convert — proceeds and refund both go to the
        // lender's asset manager (findings §25.2). A builder that pointed them anywhere else would
        // route someone's principal to the wrong party and still be a valid transaction.
        assertEquals(fields(ours).get(1).serializeToHex(), fields(ours).get(3).serializeToHex());

        // lp_asset, max_batcher_fee and expiry sit where the live order puts them.
        assertEquals(5, indexOfAssetConstructor(fields(ours)));
        assertEquals(BigInteger.valueOf(700_000L),
                ((BigIntPlutusData) fields(ours).get(7)).getValue(),
                "the validator's literal, not a Minswap default we may track");
        assertEquals(BigInteger.valueOf(700_000L), ConvertTxEncoder.MAX_BATCHER_FEE);
        assertEquals(1, ((ConstrPlutusData) fields(ours).get(8)).getAlternative(),
                "expiry_setting_opt is None, which is constructor 1");
    }

    private static int indexOfAssetConstructor(List<PlutusData> fs) {
        for (int i = 0; i < fs.size(); i++) {
            if (fs.get(i) instanceof ConstrPlutusData c && c.getAlternative() == 0
                    && c.getData().getPlutusDataList().size() == 2
                    && c.getData().getPlutusDataList().get(0) instanceof BytesPlutusData b
                    && HexUtil.encodeHexString(b.getValue()).equals(MINSWAP_POOL_POLICY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ⛔ {@code EODInlineDatum} is constructor <b>2</b>, after {@code EODNoDatum} and
     * {@code EODDatumHash}. Every ordinary swap on chain carries {@code EODNoDatum} (constructor 0),
     * so a live sample can confirm the record's field order and <b>cannot</b> confirm this index —
     * it comes from the upstream declaration, and getting it wrong decodes to a different variant.
     */
    @Test
    void theReceiverDatumVariantIsInlineWhichIsConstructorTwo() {
        var eod = (ConstrPlutusData) ConvertTxEncoder.extraOrderDatumInline("cc".repeat(32));
        assertEquals(2, eod.getAlternative());
        assertEquals(1, eod.getData().getPlutusDataList().size());

        assertEquals(0, ((ConstrPlutusData) fields(liveOrderDatum()).get(2)).getAlternative(),
                "the live sample is EODNoDatum — which is exactly why it cannot pin the index above");

        assertThrows(IllegalArgumentException.class,
                () -> ConvertTxEncoder.extraOrderDatumInline("cc"),
                "a truncated hash must be refused, not padded into a wrong-length ByteArray");
    }

    /** {@code SwapExactIn} is constructor 0, {@code SAOSpecificAmount} is constructor 0. */
    @Test
    void theSwapStepIsSwapExactInWithASpecificAmountAndIsAlwaysKillable() {
        var step = (ConstrPlutusData) ConvertTxEncoder.swapExactIn(true,
                BigInteger.valueOf(123L), BigInteger.valueOf(456L));
        assertEquals(0, step.getAlternative());
        var f = step.getData().getPlutusDataList();
        assertEquals(4, f.size());
        assertEquals(1, ((ConstrPlutusData) f.get(0)).getAlternative(), "a_to_b_direction True");
        assertEquals(0, ((ConstrPlutusData) f.get(1)).getAlternative(), "SAOSpecificAmount");
        assertEquals(BigInteger.valueOf(456L), ((BigIntPlutusData) f.get(2)).getValue());
        assertEquals(1, ((ConstrPlutusData) f.get(3)).getAlternative(),
                "killable is ALWAYS True: it is what makes a pool that cannot deliver the debt refund "
                        + "rather than fill badly, which is why this path needs no slippage model");

        var reverse = (ConstrPlutusData) ConvertTxEncoder.swapExactIn(false,
                BigInteger.ONE, BigInteger.ONE);
        assertEquals(0, ((ConstrPlutusData) reverse.getData().getPlutusDataList().get(0)).getAlternative(),
                "a_to_b_direction False");
    }

    // ---- the two datums the order embeds ---------------------------------------------------------

    /**
     * ⚠ The success datum's {@code transaction_id} is the <b>empty</b> byte string and its
     * {@code data} carries the collateral {@code Asset}; the refund datum's is the loan's own
     * reference with {@code data: None}. <b>They are deliberately not symmetric</b>, and the field is
     * typed {@code Data}, so nothing but the hash comparison would ever notice a mistake.
     */
    @Test
    void theSuccessAndRefundDatumsDifferInEveryFieldTheValidatorDictates() {
        PlutusData success = ConvertTxEncoder.successDatum(FLDT, LENDER_BOND);
        PlutusData refund = ConvertTxEncoder.refundDatum("ab".repeat(32), 1, LENDER_BOND);

        var sf = fields(success);
        var rf = fields(refund);
        assertEquals(4, sf.size());
        assertEquals(4, rf.size());

        var successRef = fields(sf.get(0));
        assertEquals(0, ((BytesPlutusData) successRef.get(0)).getValue().length,
                "OutputReference { transaction_id: \"\", output_index: 0 } — literally empty, which "
                        + "the liquidation encoder's 64-hex-char helper would have rejected");
        assertEquals(BigInteger.ZERO, ((BigIntPlutusData) successRef.get(1)).getValue());

        assertEquals("converted_to_liquidity", utf8(sf.get(1)));
        assertEquals("claimed_collateral", utf8(rf.get(1)));

        assertEquals(0, ((ConstrPlutusData) sf.get(2)).getAlternative(), "data: the collateral Asset");
        assertEquals(2, ((ConstrPlutusData) sf.get(2)).getData().getPlutusDataList().size());
        assertEquals(1, ((ConstrPlutusData) rf.get(2)).getAlternative(), "data: None");

        assertNotEquals(ConvertTxEncoder.datumHash(success), ConvertTxEncoder.datumHash(refund));
        assertEquals(64, ConvertTxEncoder.datumHash(success).length());
    }

    private static String utf8(PlutusData d) {
        return new String(((BytesPlutusData) d).getValue(), StandardCharsets.UTF_8);
    }

    /** A hash is deterministic, or the carrier output could never reproduce it. */
    @Test
    void theDatumHashIsStableAcrossIdenticalDatums() {
        assertEquals(ConvertTxEncoder.datumHash(ConvertTxEncoder.successDatum(FLDT, LENDER_BOND)),
                ConvertTxEncoder.datumHash(ConvertTxEncoder.successDatum(FLDT, LENDER_BOND)));
        assertNotEquals(ConvertTxEncoder.datumHash(ConvertTxEncoder.successDatum(FLDT, LENDER_BOND)),
                ConvertTxEncoder.datumHash(ConvertTxEncoder.successDatum(ADA, LENDER_BOND)));
    }

    // ---- the redeemer, against the DEPLOYED blueprint ---------------------------------------------

    /**
     * ⛔ <b>{@code lenderBondInputIndexes} comes BEFORE {@code lenderBondAssetNames}</b> — the reverse
     * of the order the validator's body reads them in. Both are per-loan lists, so a swap produces a
     * redeemer that decodes cleanly into the wrong fields and dies in phase 2.
     */
    @Test
    void theRedeemerFieldOrderMatchesTheDeployedBlueprint() {
        var expected = List.of("configRefInputIndex", "lenderBondInputIndexes", "lenderBondAssetNames",
                "minswapRefInputIndexes", "minswapPoolAssetNames",
                "minswapOrderSuccessInlineDatumOutputIndexes",
                "minswapOrderRefundInlineDatumOutputIndexes");
        assertEquals(expected, blueprintFieldTitles(
                "fluidtokens/types/lender_manager/LMLiquidateAndConvertActionWithdrawRedeemer"));

        var redeemer = (ConstrPlutusData) ConvertTxEncoder.convertRedeemer(3,
                List.of(0), List.of("aabb"), List.of(1), List.of(ConvertTxEncoder.POOL_NFT_ASSET_NAME),
                List.of(4), List.of(5));
        assertEquals(expected.size(), redeemer.getData().getPlutusDataList().size());

        // Position 1 must be the INDEX list and position 2 the NAME list, not the other way round.
        assertTrue(redeemer.getData().getPlutusDataList().get(1).serializeToHex()
                        .equals(com.bloxbean.cardano.client.plutus.spec.ListPlutusData
                                .of(BigIntPlutusData.of(0)).serializeToHex()),
                "field 1 is lenderBondInputIndexes");
        assertEquals("aabb", HexUtil.encodeHexString(((BytesPlutusData)
                ((com.bloxbean.cardano.client.plutus.spec.ListPlutusData)
                        redeemer.getData().getPlutusDataList().get(2))
                        .getPlutusDataList().get(0)).getValue()));
    }

    /**
     * The validator walks every per-loan list by the SAME index, so a short list does not fail — it
     * reads another loan's value. Refusing at encode time is the only place that is cheap.
     */
    @Test
    void mismatchedPerLoanListLengthsAreRefusedRatherThanSilentlyMisread() {
        assertThrows(IllegalArgumentException.class, () -> ConvertTxEncoder.convertRedeemer(0,
                List.of(0, 1), List.of("aa"), List.of(0, 1), List.of("bb", "cc"),
                List.of(0, 1), List.of(0, 1)));
    }

    private static List<String> blueprintFieldTitles(String definition) {
        try (InputStream is = ConvertTxEncoderTest.class.getResourceAsStream("/loans-v4.plutus.json")) {
            JsonNode defs = new ObjectMapper().readTree(Objects.requireNonNull(is)).get("definitions");
            var titles = new ArrayList<String>();
            defs.get(definition).get("anyOf").get(0).get("fields")
                    .forEach(f -> titles.add(f.path("title").asText(null)));
            return titles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
