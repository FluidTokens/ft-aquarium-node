package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The convert builder's guards — the ones that fire before a node is ever involved, and the post-assert
 * that re-derives the redeemer's claims from a finished body.
 *
 * <p>The end-to-end build and its ex-units belong to the dry-eval rig (sub-stage C); what is provable
 * here without a node is provable here, and stating which is which is the point of splitting them.
 */
class ConvertTransactionBuilderGuardTest {

    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final AssetType LENDER_BOND = new AssetType(
            "bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b",
            "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d");
    private static final String BOND_ADDRESS = "addr_test1wq" + "0".repeat(50);
    private static final String ORDER_ADDRESS = "addr_test1wz" + "1".repeat(50);
    private static final String BOT = "addr_test1vq" + "2".repeat(50);

    /**
     * ⛔ <b>THE INVARIANT OF THIS CLASS, asserted structurally rather than trusted.</b>
     *
     * <p>The sibling builders each grew a convenience constructor without a {@link TransactionEvaluator},
     * and that is how the 2026-08-21 incident shipped placeholder ex-units — 10000 mem / 1000 steps
     * against a real 352,041,926 — which pass the mempool and fail in phase 2, forfeiting collateral.
     *
     * <p>The stakes are specific here: <b>the operator's whole exposure on this path is "a transaction
     * fee per execution", and that is true only while the budgets are real.</b> Placeholders move the
     * exposure to the collateral, which is the one way the case for running this bot at a stated loss
     * stops holding. So a future convenience overload must fail THIS test rather than a submission.
     */
    @Test
    void noPublicConstructorCanBuildWithoutATransactionEvaluator() {
        for (Constructor<?> c : ConvertTransactionBuilder.class.getConstructors()) {
            assertTrue(Arrays.asList(c.getParameterTypes()).contains(TransactionEvaluator.class),
                    "public constructor " + Arrays.toString(c.getParameterTypes()) + " takes no "
                            + "TransactionEvaluator; a builder made through it would ship placeholder "
                            + "ex-units and fail in phase 2 (CCL trap 8)");
        }
        assertTrue(ConvertTransactionBuilder.class.getConstructors().length >= 2,
                "if this drops to zero the loop above is vacuous");
    }

    /**
     * ⚠ Every OTHER argument is valid, deliberately. The first version passed nulls for all of them
     * and passed because the registry's own {@code requireNonNull} threw first — so removing the
     * evaluator guard entirely left it green. <b>A test whose subject is the last check on the path
     * has to reach that check.</b>
     */
    @Test
    void aNullEvaluatorIsRefusedAtConstructionRatherThanAtSubmission() {
        var e = assertThrows(NullPointerException.class, () -> new ConvertTransactionBuilder(
                LoanFixtures.registry(), LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams(),
                (TransactionEvaluator) null));
        assertTrue(e.getMessage() != null && e.getMessage().contains("trap 8"),
                "the failure must say WHY, not merely that something was null: " + e.getMessage());
    }

    // ---- the post-assert, driven against hand-built bodies -----------------------------------------

    private static ConvertOrderPlan plan() {
        var pool = new MinswapPoolDatum(AssetType.ada(), FLDT,
                BigInteger.TEN, BigInteger.TEN, BigInteger.TEN);
        return ConvertOrderPlan.plan(FLDT, AssetType.ada(),
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, BigInteger.valueOf(20_000_000L),
                50L, true, false, pool,
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c", LENDER_BOND,
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                ConvertTxEncoder.plainScriptAddress("bb".repeat(28)), "cc".repeat(32), 1);
    }

    /**
     * A multisig oracle entry that would satisfy the validator — one signature against a threshold of
     * one, the shape mainnet FLDT actually uses (findings §40). {@code assertStructure} never reads it;
     * it exists because {@code build()} refuses without one, and because a fixture that could not
     * satisfy the validator would make the refusal test below pass for the wrong reason.
     */
    private static OracleEntry oracle() {
        return new OracleEntry(FLDT,
                new AssetType("93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b",
                        "6f7261636c65464c44544333"),
                "stake_test17" + "q".repeat(51),
                "4a48df8eac9f3abb39bfcd15e8cc82e8f465ece322a45ed47ef7ebb9",
                new TransactionInput("ee".repeat(32), 0),
                new TransactionInput("ef".repeat(32), 0),
                List.of("cb1506c82c3143948618c50834a527b8d471ebae067bc0a5dee1627dc511e914"),
                1,
                OraclePriceFeed.aggregated(FLDT, BigInteger.valueOf(22_265_406L),
                        BigInteger.valueOf(100_000_000L), 0L, Long.MAX_VALUE),
                List.of(new OracleSignature(0, "ab".repeat(64))),
                null);
    }

    private static Utxo bondUtxo(String datumHex) {
        Utxo u = new Utxo();
        u.setTxHash("dd".repeat(32));
        u.setOutputIndex(0);
        u.setAddress(BOND_ADDRESS);
        u.setInlineDatum(datumHex);
        u.setAmount(List.of());
        return u;
    }

    private static ConvertTransactionBuilder.Request request(ConvertOrderPlan p, String bondDatumHex) {
        return new ConvertTransactionBuilder.Request(null, bondUtxo(bondDatumHex), null, oracle(),
                null, null, null, Map.of(), p,
                new ClaimData(new LiquidationMode.Liquidation(BigInteger.ONE, BigInteger.ONE,
                        BigInteger.ZERO, false),
                        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                        new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                        BigInteger.ZERO, "loan", BigInteger.valueOf(20_000_000L)),
                FLDT, LENDER_BOND, ORDER_ADDRESS, BOT, 0L, 0L);
    }

    private static TransactionOutput out(String address, long lovelace, PlutusData datum,
                                         AssetType token, BigInteger tokenQty) {
        Value.ValueBuilder v = Value.builder().coin(BigInteger.valueOf(lovelace));
        if (token != null) {
            v.multiAssets(List.of(MultiAsset.builder().policyId(token.policyId())
                    .assets(List.of(Asset.builder().name("0x" + token.assetName())
                            .value(tokenQty).build()))
                    .build()));
        }
        return TransactionOutput.builder().address(address).value(v.build()).inlineDatum(datum).build();
    }

    private static Transaction txWith(List<TransactionOutput> outs) {
        return Transaction.builder().body(TransactionBody.builder().outputs(outs).build()).build();
    }

    /** The layout the builder aims for, with every claim true. */
    private static List<TransactionOutput> goodOutputs(ConvertOrderPlan p, String bondDatumHex) {
        PlutusData bondDatum;
        try {
            bondDatum = PlutusData.deserialize(
                    com.bloxbean.cardano.client.util.HexUtil.decodeHexString(bondDatumHex));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var outs = new ArrayList<TransactionOutput>();
        outs.add(out(BOT, 1_000_000L, null, null, null));                        // trap-1 dummy
        outs.add(out(ORDER_ADDRESS, 2_800_000L, p.orderDatum(), FLDT,
                p.swappableCollateralAmount()));                                  // 1: the order
        outs.add(out(BOND_ADDRESS, 2_000_000L, bondDatum, null, null));           // 2: the bond echo
        outs.add(out(BOT, 1_200_000L, p.successDatum(), null, null));             // 3: success carrier
        outs.add(out(BOT, 1_200_000L, p.refundDatum(), null, null));              // 4: refund carrier
        outs.add(out(BOT, 5_000_000L, null, FLDT, p.liquidationFee()));           // 5: the bot's fee
        return outs;
    }

    /**
     * ⚠ Deliberately DISTINCT from either carrier datum. The first version of this file reused the
     * success datum as the bond's, and the off-by-the-dummy-output test then passed for the wrong
     * reason: index 2 really did hold the success datum, so the wrong index was accidentally right.
     * <b>A fixture that collides with the thing under test disarms the test silently.</b>
     */
    private static String bondDatumHex() {
        return com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(424242).serializeToHex();
    }

    /**
     * ⚠ A registry that CAN derive the convert action, because the derivation guard runs first and
     * would otherwise answer for the oracle guard below. The first version of the oracle test used
     * the plain fixture registry and got {@code CONVERT_ACTION_NOT_DERIVED} — a test whose subject is
     * not the first check on a path has to REACH its check, which is the third time this file has had
     * to learn that.
     */
    private static com.fluidtokens.aquarium.offchain.service.LoansContractRegistry convertCapableRegistry() {
        return new com.fluidtokens.aquarium.offchain.service.LoansContractRegistry(
                LoanFixtures.CONFIG_POLICY_ID, LoanFixtures.LM_CONFIG_POLICY_ID,
                LoanFixtures.CONFIG_ASSET_NAME, LoanFixtures.SMART_TOKENS_SPEND,
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");
    }

    private static ConvertTransactionBuilder builder() {
        return new ConvertTransactionBuilder(convertCapableRegistry(), LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams(),
                // ⚠ Never used: assertStructure is driven directly against hand-built bodies. It is
                // here only because the constructor REFUSES a null one, which is the invariant above.
                (bytes, utxos) -> null);
    }

    @Test
    void aBodyThatMatchesEveryClaimPasses() {
        ConvertOrderPlan p = plan();
        builder().assertStructure(txWith(goodOutputs(p, bondDatumHex())),
                request(p, bondDatumHex()), 3, 4, 2);
    }

    /**
     * ⛔ THE ONE THE TWO-PASS EXISTS FOR. The carrier indexes are ABSOLUTE, and a withdrawing
     * transaction gets a dummy output prepended (trap 1). Predicting them by arithmetic — "my first
     * payToContract is output 0" — is off by exactly that dummy, and the redeemer then names an output
     * whose datum hashes to something else.
     */
    @Test
    void aCarrierIndexOffByTheDummyOutputIsRefused() {
        ConvertOrderPlan p = plan();
        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(goodOutputs(p, bondDatumHex())),
                        request(p, bondDatumHex()), 2, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.CARRIER_DATUM_MISMATCH, e.reason());
    }

    @Test
    void anIndexPastTheEndOfTheBodyIsRefusedRatherThanThrowingAnIndexError() {
        ConvertOrderPlan p = plan();
        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(goodOutputs(p, bondDatumHex())),
                        request(p, bondDatumHex()), 99, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.INDEX_MISMATCH, e.reason());
    }

    /** {@code equals_data} compares the whole output, so a re-encoded datum fails on chain. */
    @Test
    void aBondEchoThatIsNotByteIdenticalIsRefused() {
        ConvertOrderPlan p = plan();
        var outs = goodOutputs(p, bondDatumHex());
        outs.set(2, out(BOND_ADDRESS, 2_000_000L, p.refundDatum(), null, null));

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(outs), request(p, bondDatumHex()), 3, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, e.reason());
    }

    /** The order's lovelace is the validator's literal, not a min-ada estimate. */
    @Test
    void anOrderCarryingTheWrongLovelaceIsRefused() {
        ConvertOrderPlan p = plan();
        var outs = goodOutputs(p, bondDatumHex());
        outs.set(1, out(ORDER_ADDRESS, 2_000_000L, p.orderDatum(), FLDT, p.swappableCollateralAmount()));

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(outs), request(p, bondDatumHex()), 3, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.ORDER_OUTPUT_MISMATCH, e.reason());
        assertTrue(e.getMessage().contains("2800000"), e.getMessage());
    }

    @Test
    void anOrderCarryingTheWrongCollateralAmountIsRefused() {
        ConvertOrderPlan p = plan();
        var outs = goodOutputs(p, bondDatumHex());
        outs.set(1, out(ORDER_ADDRESS, 2_800_000L, p.orderDatum(), FLDT,
                p.swappableCollateralAmount().add(BigInteger.ONE)));

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(outs), request(p, bondDatumHex()), 3, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.ORDER_OUTPUT_MISMATCH, e.reason());
    }

    /**
     * ⛔ <b>NO VALIDATOR ASKS FOR THE BOT'S FEE.</b> It is the unconstrained residue
     * {@code collateral − equity − swappable} (findings §25.3), so a builder that left it in the order
     * or handed it to the lender produces a <b>perfectly valid transaction</b> that simply pays the
     * operator nothing. Nothing on chain and nothing in the economics would notice — only this.
     */
    @Test
    void aTransactionThatDoesNotPayTheBotItsFeeIsRefusedEvenThoughItWouldBeValid() {
        ConvertOrderPlan p = plan();
        var outs = goodOutputs(p, bondDatumHex());
        outs.remove(5);

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(outs), request(p, bondDatumHex()), 3, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.FEE_NOT_PAID_TO_BOT, e.reason());
        assertTrue(e.getMessage().contains(p.liquidationFee().toString()), e.getMessage());
    }

    /**
     * ⛔ The hash check is not redundant with the bytes check, and this is the only case that
     * separates them: an order datum embedding a hash the carrier's datum does not produce. It cannot
     * arise from {@link ConvertOrderPlan}, which computes both from one object — but a future path
     * that took the hash from anywhere else would build a transaction whose carriers are ignored by
     * Minswap, and nothing on chain would object.
     *
     * <p>⚑ Found by a mutant: deleting the hash check left every other test green.
     */
    @Test
    void anOrderEmbeddingAHashItsCarrierDoesNotProduceIsRefused() {
        ConvertOrderPlan p = plan();
        ConvertOrderPlan lying = new ConvertOrderPlan(p.aToBDirection(), p.lpAssetName(),
                p.liquidationFee(), p.swappableCollateralAmount(), p.orderLovelace(),
                p.minimumReceive(), p.successDatum(), p.refundDatum(),
                "ff".repeat(32), p.refundDatumHash(), p.orderDatum());

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(goodOutputs(p, bondDatumHex())),
                        request(lying, bondDatumHex()), 3, 4, 2));
        assertEquals(ConvertTransactionBuilder.Refusal.CARRIER_DATUM_MISMATCH, e.reason());
        assertTrue(e.getMessage().contains("hashes to"), e.getMessage());
    }

    // ---- the oracle leg, which this builder shipped without -----------------------------------

    /**
     * ⛔ <b>THE DEFECT THIS CLASS EXISTS TO NOT REPEAT.</b> The first version of
     * {@link ConvertTransactionBuilder} emitted four withdrawals and <b>no oracle leg at all</b> — no
     * oracle withdrawal, no oracle reference inputs, no oracle indexes in the claim data.
     *
     * <p>It assembled, it serialised, and it passed <b>every one of the ten structural assertions
     * above</b>, because they all inspect what the builder EMITS and none can see a limb that was
     * never added. It would have failed {@code loan_claim_action} on chain.
     *
     * <p>⚑ And what hid it: a since-retracted finding said a convert could not be dry-evaluated
     * offline (§38/§40). That claim also, quietly, meant "the builder need not be finished to be
     * provable". <b>A wrong impossibility claim protects the thing it excuses.</b>
     *
     * <p>So the guard is at the FRONT of {@code build()}, before anything else can be got right.
     */
    @Test
    void aCandidateWithNoUsableCollateralOracleIsRefusedBeforeAnythingIsBuilt() {
        ConvertOrderPlan p = plan();
        var noOracle = new ConvertTransactionBuilder.Request(null, bondUtxo(bondDatumHex()), null,
                null, null, null, null, Map.of(), p, request(p, bondDatumHex()).claim(),
                FLDT, LENDER_BOND, ORDER_ADDRESS, BOT, 0L, 0L);

        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().build(noOracle));
        assertEquals(ConvertTransactionBuilder.Refusal.COLLATERAL_ORACLE_MISSING, e.reason());
        assertTrue(e.getMessage().contains("nothing here would have noticed"), e.getMessage());
    }

    /**
     * ⛔ <b>AND NOT THE SIBLING'S CALL.</b> {@code LiquidatePayInAdvanceTransactionBuilder} passes
     * {@code List.of()} for the signatures — correct for a Charli3/Orcfax feed, whose price is proven
     * by a provider reference input instead. <b>Mainnet FLDT is multisig</b> (findings §40), so its
     * branch runs {@code verify_ed25519_signature} against the published signatures and an empty list
     * fails the threshold.
     *
     * <p>Copying the sibling verbatim is what a hurried fix does, and it emits a transaction that
     * assembles cleanly and dies in phase 2. This asserts the entry's own signatures reach the
     * redeemer rather than being dropped.
     */
    @Test
    void theOracleRedeemerCarriesThePublishedSignaturesAndNotAnEmptyList() {
        OracleEntry entry = oracle();
        assertEquals(1, entry.signatures().size(), "the fixture must have a signature to lose");
        assertTrue(entry.hasEnoughSignatures());
        assertTrue(entry.usableForLiquidation(),
                "a fixture the validator could not satisfy would make the refusal test above pass "
                        + "for the wrong reason");

        String withSignatures = LiquidationTxEncoder
                .oracleRedeemer(entry.feed(), entry.signatures()).serializeToHex();
        String theSiblingsCall = LiquidationTxEncoder
                .oracleRedeemer(entry.feed(), List.of()).serializeToHex();

        assertNotEquals(theSiblingsCall, withSignatures,
                "if these ever match, the signatures are being dropped and the multisig branch would "
                        + "fail its threshold on chain");
        assertTrue(withSignatures.contains("ab".repeat(32)),
                "the published signature bytes must actually reach the redeemer");
    }

    @Test
    void aBondOutputIndexPointingAtTheWrongAddressIsRefused() {
        ConvertOrderPlan p = plan();
        var e = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder().assertStructure(txWith(goodOutputs(p, bondDatumHex())),
                        request(p, bondDatumHex()), 3, 4, 1));
        assertEquals(ConvertTransactionBuilder.Refusal.INDEX_MISMATCH, e.reason());
    }
}
