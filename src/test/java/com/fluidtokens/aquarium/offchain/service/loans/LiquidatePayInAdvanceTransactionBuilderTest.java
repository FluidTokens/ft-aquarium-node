package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>THE MULTISIG ORACLE LEGS</b> — {@link LiquidatePayInAdvanceTransactionBuilder} against feeds
 * that carry <em>signatures</em> instead of a Charli3 provider reference input.
 *
 * <h2>Why this class exists (2026-09-10)</h2>
 * Every offline fixture of this builder had been Charli3-backed, because preview's only live oracle
 * feeds are the three Charli3 ones. <b>Mainnet publishes every FluidTokens feed as multisig</b>
 * (findings §40), and the first-ever mainnet build — Giovanni's node, 07:41Z — died with a bare
 * {@link NullPointerException} out of {@code referenceInputs}: {@code List.of} refuses a null
 * element, and a multisig feed's {@code charlieProviderReferenceInput} is null by construction.
 * <p>
 * Behind the NPE sat the second defect, which these encoding assertions exist for: both legs called
 * {@code oracleRedeemer(feed, providerRefIndex, List.of())} unconditionally — the Charli3 shape, a
 * provider index and an <b>empty signature list</b>. Against a multisig validator branch that runs
 * {@code verify_ed25519_signature} over the published signatures, an empty list fails the threshold,
 * and such a transaction assembles, passes the mempool and dies in <b>phase 2</b> with the fee spent
 * and the collateral consumed (CCL trap 8's failure tier). {@link ConvertTransactionBuilder} — the
 * only builder that has ever built on mainnet — has encoded the signed shape since findings §40, and
 * its comment there names this builder's old call as exactly that fatal one.
 * <p>
 * ⚠ <b>Measured, and worth recording because it is better news than the above:</b> on the unmodified
 * builder a signed feed did not silently reach the chain in the Charli3 shape. It could not:
 * {@code OracleFeedConverter.toPlutusData(feed, providerRefInputIndex)} type-checks its own variant
 * and threw {@code "only encodes PRICE_DATA_CHARLIE, not AGGREGATED"} at build time. The encoder's
 * guard was the backstop that kept the phase-2 transaction unreachable. So what the tests below pin
 * is not the absence of a throw — that was already true — but that each leg is encoded in the shape
 * its own variant requires.
 *
 * <h2>Every assertion reads the DESERIALISED transaction</h2>
 * Never a builder field and never an intermediate: the redeemer is fetched by its withdrawal's
 * position in the finished body (CCL trap 14 — a redeemer is identified by its script purpose's
 * position, never by its data's shape) and its {@code PlutusData} is decoded from there. What ships
 * is what is asserted.
 *
 * <h2>What this class does NOT prove</h2>
 * It is a build-and-inspect rig with no evaluator, so ex-units are cardano-client-lib placeholders
 * here and nothing below reads them. That the deployed mainnet validators <em>accept</em> the
 * encoding — the phase-2 question — is proven by {@link LiquidatePayInAdvanceLiveDryEvalTest}
 * against the real chain, with both real oracle legs and a {@code payout − 1} adversarial case.
 */
class LiquidatePayInAdvanceTransactionBuilderTest {

    /**
     * A second priced asset with its own oracle deployment, so the principal leg gets a
     * <b>distinct</b> withdrawal credential from the collateral leg's. The builder only emits a
     * second withdraw-0 when the two credentials differ — the ledger permits one withdrawal per
     * reward address, and {@code retrieve_oracle_data} resolves its feed with
     * {@code pairs.get_first(self.redeemers, Withdraw(oraclePaymentCredential))}, one redeemer per
     * credential.
     */
    private static final AssetType PRINCIPAL_TOKEN =
            new AssetType("11".repeat(28), "5052494e434950414c");
    private static final AssetType PRINCIPAL_ORACLE_NFT =
            new AssetType("22".repeat(28), "6f7261636c65");
    private static final String PRINCIPAL_ORACLE_SCRIPT_HASH = "33".repeat(28);
    private static final TransactionInput PRINCIPAL_ORACLE_REF_INPUT =
            new TransactionInput("44".repeat(32), 0);
    private static final TransactionInput PRINCIPAL_ORACLE_REF_SCRIPT =
            new TransactionInput("44".repeat(32), 1);

    private static final List<OracleSignature> COLLATERAL_SIGNATURES = List.of(
            new OracleSignature(0, "aa".repeat(64)),
            new OracleSignature(2, "bb".repeat(64)));
    private static final List<OracleSignature> PRINCIPAL_SIGNATURES = List.of(
            new OracleSignature(1, "cc".repeat(64)));

    // ---- 1. the multisig COLLATERAL leg -------------------------------------------------------

    /**
     * ⛔ <b>THE FAIL-FIRST CASE.</b> On the unmodified builder this throws
     * {@code NullPointerException} out of {@code referenceInputs} before a single byte is assembled.
     */
    @Test
    void aMultisigCollateralLegBuildsAndCarriesItsSignaturesInTheOracleRedeemer() {
        var base = LiquidatePayInAdvanceDryEvalTest.fixture();
        OracleEntry multisig = multisigLike(base.request().oracle(), COLLATERAL_SIGNATURES);
        var request = withOracles(base.request(), multisig, null);

        Transaction built = deserialised(build(request));

        // The signed encoding: OraclePriceFeed::Aggregated is constructor 0 — three fields
        // (common, price, denominator) and NO leading provider index — and the signature list
        // carries every published signature, in order.
        PlutusData redeemer = oracleRedeemerAt(built, multisig.rewardAddress());
        assertSignedFeed(redeemer, multisig.feed());
        assertSignatures(redeemer, COLLATERAL_SIGNATURES);

        // And no provider reference input was invented for a feed that has none: the body's
        // reference inputs are exactly the two configs plus this oracle's own two.
        assertEquals(4, built.getBody().getReferenceInputs().size(),
                "a provider-less feed must add no provider reference input");
        assertTrue(built.getBody().getReferenceInputs().contains(multisig.referenceInput()));
        assertTrue(built.getBody().getReferenceInputs().contains(multisig.referenceScript()));
    }

    // ---- 2. the multisig PRINCIPAL leg --------------------------------------------------------

    /**
     * The principal leg is a SEPARATE withdraw-0 of the oracle validator at its own credential, and
     * it is encoded by its own feed's variant — not by the collateral leg's. The collateral leg here
     * is deliberately left <b>Charli3</b>, so this case isolates the ENCODING defect from the
     * null-provider one: on the unmodified builder it assembles cleanly and ships the principal leg
     * in the Charli3 shape, which is the phase-2-fatal transaction.
     */
    @Test
    void aMultisigPrincipalLegGetsItsOwnWithdrawZeroCarryingItsOwnSignatures() {
        var base = LiquidatePayInAdvanceDryEvalTest.fixture();
        OracleEntry charli3Collateral = base.request().oracle();
        OracleEntry multisigPrincipal = multisigPrincipal(charli3Collateral.feed());
        var request = withOracles(base.request(), charli3Collateral, multisigPrincipal);

        Transaction built = deserialised(build(request));

        // Two DISTINCT oracle withdrawals — one per credential.
        assertFalse(charli3Collateral.rewardAddress().equals(multisigPrincipal.rewardAddress()),
                "the two legs must not share a credential, or the second withdraw-0 is skipped and "
                        + "this test proves nothing about it");
        assertTrue(rewardAddresses(built).contains(charli3Collateral.rewardAddress()),
                "the collateral oracle withdraw-0 is missing");
        assertTrue(rewardAddresses(built).contains(multisigPrincipal.rewardAddress()),
                "the principal oracle withdraw-0 is missing");

        PlutusData principalRedeemer = oracleRedeemerAt(built, multisigPrincipal.rewardAddress());
        assertSignedFeed(principalRedeemer, multisigPrincipal.feed());
        assertSignatures(principalRedeemer, PRINCIPAL_SIGNATURES);

        // The collateral leg, on the same transaction, still takes the Charli3 shape — the variant
        // decides each leg independently and one leg's shape never leaks into the other's.
        PlutusData collateralRedeemer = oracleRedeemerAt(built, charli3Collateral.rewardAddress());
        assertCharli3Feed(collateralRedeemer, built, charli3Collateral);
        assertSignatures(collateralRedeemer, List.of());
    }

    // ---- 3. the Charli3 leg, unchanged --------------------------------------------------------

    /**
     * The regression guard: the preview shape this builder has always emitted is untouched — the
     * provider IS a reference input, the redeemer names its real position among the body's
     * canonically sorted reference inputs, and the signature list is legitimately EMPTY (a c3 feed
     * carries no signature over its own bytes; the validator checks it structurally against the
     * provider UTxO instead).
     */
    @Test
    void theCharli3LegIsUnchanged() {
        var base = LiquidatePayInAdvanceDryEvalTest.fixture();
        OracleEntry charli3 = base.request().oracle();

        Transaction built = deserialised(build(base.request()));

        assertTrue(built.getBody().getReferenceInputs().contains(charli3.charlieProviderReferenceInput()),
                "the Charli3 provider must still be a reference input");
        PlutusData redeemer = oracleRedeemerAt(built, charli3.rewardAddress());
        assertCharli3Feed(redeemer, built, charli3);
        assertSignatures(redeemer, List.of());
    }

    // ---- 4. an unmodelled variant is refused BY NAME -------------------------------------------

    /**
     * {@code POOLED} carries pool reserves and fees, not a plain price, and
     * {@link OracleFeedConverter} cannot encode it at all. The builder must say so about the
     * CANDIDATE — a named refusal the executor records as {@code REFUSED} — rather than letting an
     * {@code UnsupportedOperationException} escape the encoder mid-assembly, which reads as
     * machinery breakage.
     */
    @Test
    void aPooledFeedIsRefusedByNameBeforeAnythingIsBuilt() {
        var base = LiquidatePayInAdvanceDryEvalTest.fixture();
        OracleEntry pooled = pooledLike(base.request().oracle());
        var request = withOracles(base.request(), pooled, null);

        var refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> build(request));

        assertTrue(refusal.getMessage().contains("POOLED"),
                "the refusal must name the variant it cannot model: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("collateral"),
                "the refusal must name the leg: " + refusal.getMessage());
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** The same asset, the same deployment, the same window — published MULTISIG instead of c3. */
    private static OracleEntry multisigLike(OracleEntry charli3, List<OracleSignature> signatures) {
        OraclePriceFeed feed = charli3.feed();
        return LoanFixtures.multisig(charli3.token(), charli3.oracleToken(),
                charli3.withdrawCredentialHash(),
                OraclePriceFeed.aggregated(feed.token(), feed.priceInLovelaces(), feed.priceDenominator(),
                        feed.validFrom(), feed.validTo()),
                charli3.referenceInput(), charli3.referenceScript(), signatures);
    }

    /**
     * A second, multisig oracle for the principal leg.
     * <p>
     * ⚠ Its price is deliberately <b>1 lovelace / 1</b>, which is arithmetically identical to the
     * unit feed {@code numbers()} substitutes for a null principal oracle — so every payout figure,
     * every output and every index in this transaction is the ada-principal baseline's, and the ONLY
     * thing that changes is that a second oracle credential is named. This test is about the
     * REDEEMER, and pinning the arithmetic still means a failure here can only be the redeemer.
     * (The genuine token-principal economics are proven live, against real USDM, by
     * {@link LiquidatePayInAdvanceLiveDryEvalTest}.)
     */
    private static OracleEntry multisigPrincipal(OraclePriceFeed windowSource) {
        return LoanFixtures.multisig(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT, PRINCIPAL_ORACLE_SCRIPT_HASH,
                OraclePriceFeed.aggregated(PRINCIPAL_TOKEN, BigInteger.ONE, BigInteger.ONE,
                        windowSource.validFrom(), windowSource.validTo()),
                PRINCIPAL_ORACLE_REF_INPUT, PRINCIPAL_ORACLE_REF_SCRIPT, PRINCIPAL_SIGNATURES);
    }

    /** The same entry with its feed re-labelled {@code POOLED} — nothing else moves. */
    private static OracleEntry pooledLike(OracleEntry entry) {
        OraclePriceFeed feed = entry.feed();
        return new OracleEntry(entry.token(), entry.oracleToken(), entry.rewardAddress(),
                entry.withdrawCredentialHash(), entry.referenceInput(), entry.referenceScript(),
                entry.verificationKeys(), entry.threshold(),
                new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, feed.token(),
                        feed.priceInLovelaces(), feed.priceDenominator(),
                        feed.validFrom(), feed.validTo()),
                entry.signatures(), entry.charlieProviderReferenceInput());
    }

    private static LiquidatePayInAdvanceTransactionBuilder.Request withOracles(
            LiquidatePayInAdvanceTransactionBuilder.Request r, OracleEntry oracle, OracleEntry principal) {
        return new LiquidatePayInAdvanceTransactionBuilder.Request(r.loan(), r.loanUtxo(), r.bond(),
                r.bondUtxo(), r.walletUtxo(), r.configUtxo(), r.lmConfigUtxo(), oracle, principal,
                r.validFromMillis(), r.validToMillis(), r.validFromSlot(), r.validToSlot(),
                r.changeAddress(), r.referenceScripts(), r.oracleWindowMarginMillis());
    }

    /**
     * The builder with no evaluator. Reference inputs are body coordinates, not resolvable UTxOs —
     * {@code complete()} installs a no-op {@code ScriptSupplier} offline (CCL trap 2) — so only the
     * spendable universe has to be served here.
     */
    private static Transaction build(LiquidatePayInAdvanceTransactionBuilder.Request request) {
        List<Utxo> universe = new ArrayList<>(List.of(request.configUtxo(), request.lmConfigUtxo(),
                request.walletUtxo(), request.loanUtxo(), request.bondUtxo()));
        return new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams())
                .build(request);
    }

    // ---- reading the built body ------------------------------------------------------------------

    private static Transaction deserialised(Transaction built) {
        try {
            return Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            throw new AssertionError("the built transaction does not round-trip through CBOR", e);
        }
    }

    private static List<String> rewardAddresses(Transaction tx) {
        return tx.getBody().getWithdrawals().stream().map(Withdrawal::getRewardAddress).toList();
    }

    /**
     * The oracle validator's withdraw-0 redeemer, located by the POSITION of its script purpose —
     * the withdrawal's index in the body's own (canonically sorted) withdrawals list — and never by
     * matching the redeemer's shape (CCL trap 14).
     */
    private static PlutusData oracleRedeemerAt(Transaction tx, String rewardAddress) {
        int index = rewardAddresses(tx).indexOf(rewardAddress);
        assertTrue(index >= 0, "no withdrawal at " + rewardAddress);
        Redeemer redeemer = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward && r.getIndex().intValue() == index)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reward redeemer at withdrawal " + index));
        return redeemer.getData();
    }

    /**
     * {@code OracleRedeemer { data: OraclePriceFeed, signatures: List<Signature> }} — the feed is
     * field 0, the signature list field 1.
     */
    private static ConstrPlutusData feedOf(PlutusData redeemer) {
        return (ConstrPlutusData) ((ConstrPlutusData) redeemer).getData().getPlutusDataList().get(0);
    }

    private static ListPlutusData signaturesOf(PlutusData redeemer) {
        return (ListPlutusData) ((ConstrPlutusData) redeemer).getData().getPlutusDataList().get(1);
    }

    /**
     * {@code Aggregated { common, price_in_lovelaces, price_denominator }} — constructor 0, THREE
     * fields, no provider index. The price is read back out to prove this is the feed handed in and
     * not some other leg's.
     */
    private static void assertSignedFeed(PlutusData redeemer, OraclePriceFeed expected) {
        ConstrPlutusData feed = feedOf(redeemer);
        assertEquals(0L, feed.getAlternative(),
                "a signed (AGGREGATED) feed is constructor 0; constructor 3 is the Charli3 shape, "
                        + "which a multisig validator branch refuses in phase 2");
        List<PlutusData> fields = feed.getData().getPlutusDataList();
        assertEquals(3, fields.size(), "the signed feed has no provider_ref_input_index");
        assertEquals(expected.priceInLovelaces(), ((BigIntPlutusData) fields.get(1)).getValue());
        assertEquals(expected.priceDenominator(), ((BigIntPlutusData) fields.get(2)).getValue());
    }

    /**
     * {@code PriceDataCharlie { provider_ref_input_index, common, price_in_lovelaces,
     * price_denominator }} — constructor 3, and the index must point at the provider's real position
     * in the FINISHED body's reference inputs.
     */
    private static void assertCharli3Feed(PlutusData redeemer, Transaction tx, OracleEntry entry) {
        ConstrPlutusData feed = feedOf(redeemer);
        assertEquals(3L, feed.getAlternative(), "a Charli3 feed is constructor 3");
        List<PlutusData> fields = feed.getData().getPlutusDataList();
        assertEquals(4, fields.size(), "the Charli3 feed carries a leading provider_ref_input_index");
        int providerIndex = ((BigIntPlutusData) fields.get(0)).getValue().intValueExact();
        assertEquals(entry.charlieProviderReferenceInput(),
                tx.getBody().getReferenceInputs().get(providerIndex),
                "provider_ref_input_index does not point at the Charli3 provider in the body's own "
                        + "sorted reference inputs");
    }

    /**
     * {@code Signature { signature: ByteArray, key_position: Int }} — signature BEFORE key_position,
     * the opposite of {@link OracleSignature}'s own field order, which is exactly the kind of detail
     * an assertion off the deserialised bytes catches and a builder-field assertion cannot.
     */
    private static void assertSignatures(PlutusData redeemer, List<OracleSignature> expected) {
        List<PlutusData> actual = signaturesOf(redeemer).getPlutusDataList();
        assertEquals(expected.size(), actual.size(),
                "the oracle redeemer carries " + actual.size() + " signatures, expected "
                        + expected.size() + " — an empty list on a multisig feed fails the "
                        + "validator's threshold in PHASE 2");
        for (int i = 0; i < expected.size(); i++) {
            List<PlutusData> fields = ((ConstrPlutusData) actual.get(i)).getData().getPlutusDataList();
            assertEquals(expected.get(i).signatureHex(),
                    HexUtil.encodeHexString(
                            ((com.bloxbean.cardano.client.plutus.spec.BytesPlutusData) fields.get(0))
                                    .getValue()),
                    "signature " + i + " is not the one published");
            assertEquals(BigInteger.valueOf(expected.get(i).keyPosition()),
                    ((BigIntPlutusData) fields.get(1)).getValue(),
                    "key_position " + i + " is not the one published");
        }
    }
}
