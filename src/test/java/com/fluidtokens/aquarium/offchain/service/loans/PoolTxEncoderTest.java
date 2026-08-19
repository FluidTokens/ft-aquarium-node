package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Golden serialized-hex tests for {@link PoolTxEncoder}, in {@link RequestTxEncoderTest}'s
 * discipline: every expected hex was derived <b>independently of {@link PoolTxEncoder} itself</b>,
 * by reasoning through the CBOR each field must produce (constructor tags {@code 121 + alternative}
 * → {@code d879..d87f}; indefinite lists {@code 9f…ff} for non-empty field/element lists; the
 * definite empty array {@code 0x80} for an empty constructor; minimal integer / bytestring headers;
 * CBOR major type 1 for negatives), transcribed as literals with byte-by-byte breakdowns. None was
 * captured from the encoder, so a change that still satisfies {@link PoolTxEncoderSchemaTest} is
 * still caught here.
 *
 * <h2>Two kinds of golden, defending different things</h2>
 * <ul>
 *   <li><b>Chain goldens</b> — FluidTokens' own deployed preview pool ({@code 0edd513b…#0}), the
 *       real bond datum a loan from it carries ({@code d2a85126…#3}), and the real NFT names. These
 *       are the strongest possible pins: the bytes are on chain, verified by their own datum hash and
 *       by {@code blake2b}. They prove the encoder against reality.</li>
 *   <li><b>Field-index sentinels</b> — synthetic fixtures whose every same-typed field is
 *       byte-distinguishable, so any transposition of the encoder's positional write order changes
 *       the serialised bytes. The chain goldens cannot do this alone: FluidTokens' pool has
 *       {@code minCollateral} / {@code minCollateralDivider} both single-element and byte-close, and
 *       {@code permissionedConditionScriptHash} at 4 bytes is length-distinguishable from the 32-byte
 *       bond hash but their <em>positions</em> are still worth pinning under a distinguishing
 *       fixture.</li>
 * </ul>
 * Neither is redundant with the other. The chain goldens prove the bytes we would post are right; the
 * sentinels prove the field mapping that produced them is right.
 */
class PoolTxEncoderTest {

    private static String hex(PlutusData data) {
        try {
            return HexUtil.encodeHexString(data.serializeToBytes());
        } catch (Exception e) {
            throw new AssertionError("cannot serialise", e);
        }
    }

    // =============================================================================================
    // Chain golden 1 — FluidTokens' own deployed preview pool datum (0edd513b…#0)
    // =============================================================================================

    private static final String TFLDT_POLICY = "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    private static final String TFLDT_NAME = "0014df1074464c4454";
    private static final String FLUID_ORACLE_POLICY = "9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f";
    private static final String FLUID_ORACLE_NAME = "000de1406f766f3633";

    /** {@code b2324fbd…} = the preview pool-manager policy (this pool's {@code lenderAuth} withdraws it). */
    private static final String POOL_MANAGER_POLICY = "b2324fbdcace499f6f1a9599daaebd707eb0ca70edbd6676fa20520b";
    /** {@code e302d1be…} = the preview LenderManager spend script (the pool's bond address payment cred). */
    private static final String LENDER_MANAGER_SPEND = "e302d1bee8142bee85f7e76f68399a170b2a1454ac19ffd9927332d3";
    /** {@code 1c5621a0…} = the lender's stake key (the pool's bond address stake cred). */
    private static final String LENDER_STAKE_KEY = "1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3";

    /**
     * The {@code lenderBondInlineDatumHash} FluidTokens committed to: the {@code blake2b_256} of the
     * fee-0 bond datum a loan from this pool actually carries. Proven a hash of real bond-datum bytes,
     * not a magic constant, by {@link #theFeeZeroBondDatumHashesToThePoolsCommittedValue}.
     */
    private static final String LENDER_BOND_INLINE_DATUM_HASH =
            "d55be7e55a60e762913dfdfd08d4a284759376219637eb5dcc484d5481ce4193";

    /**
     * The pool NFT asset name of the deployed pool — the {@code poolId} a loan from it carries. All 29
     * bytes ({@code 0x00} index prefix + 28-byte hash). Sourced from the real bond / loan datum
     * ({@code RealLoanDryEvalTest}); see {@link #theBondNftNameIsTheUnprefixedHashOfThePoolsOwnRef} for
     * why the seed's own hash cannot be recomputed offline in this repo.
     */
    private static final String POOL_NFT_NAME =
            "00746eab912831d705649ad01a3f349eaefa3fb5e57e7b170c07063e83";

    private static Address lenderBondAddress() {
        return AddressProvider.getBaseAddress(Credential.fromScript(LENDER_MANAGER_SPEND),
                Credential.fromKey(LENDER_STAKE_KEY), LoanFixtures.NETWORK);
    }

    /**
     * FluidTokens' deployed pool datum, {@code 0edd513b…#0}, byte for byte. Its own datum hash is
     * {@code 9aeed63f…} ({@link #theChainPoolDatumBytesMatchTheirOwnDatumHash} proves these bytes
     * reproduce it):
     * <pre>
     * d879 9f                                    PoolDatum constr 0, 10 fields
     *   44 4e4f4e45                                 bytes(4) "NONE" = permissionedConditionScriptHash
     *   d879 80                                     Constr 0 [] = extraData (Aiken unit)
     *   d879 9f                                     commonData constr 0
     *     d879 9f 40 40 ff                            principalAsset = ada
     *     d879 9f 40 40 ff                            principalOracleAsset = ada
     *     19 01cb                                     interestRate = 459
     *     00 00 00                                    installmentPeriod, totalInstallments, initialGracePeriod
     *     d87b 9f 1864 187d 1864 d87980 ff            Liquidation(100, 125, 100, False)
     *     d87b 9f 181c 05 ff                          PerpetualLoan(28, 5)
     *     00 00                                       repaymentTimeWindow, penaltyFeeForLateRepayment
     *     d879 80                                     repaymentReceipts = False
     *     40                                          borrowerBondDestinationScriptHash = ""
     *   ff
     *   d87b 9f 581c &lt;b2324fbd…&gt; ff                   lenderAuth = CardanoWithdrawScript (constr 2)
     *   d879 9f                                       lenderBondAddress constr 0
     *     d87a 9f 581c &lt;e302d1be…&gt; ff                   payment_credential = Script (constr 1)
     *     d879 9f d879 9f d879 9f 581c &lt;1c5621a0…&gt; ff ff ff ff   Some(Inline(VerificationKey))
     *   ff
     *   5820 &lt;d55be7e5…&gt;                              lenderBondInlineDatumHash (32 bytes)
     *   9f d879 9f 581c &lt;tFLDT&gt; d879 9f 49 &lt;name&gt; ff  d879 9f 581c &lt;oracle&gt; 49 &lt;name&gt; ff ff ff   collateralOptions
     *   9f 1864 ff                                    minCollateral = [100]
     *   9f 1832 ff                                    minCollateralDivider = [50]
     *   d87a 80                                       dynamicCollateralPrice = True (constr 1)
     * ff
     * </pre>
     */
    private static final String CHAIN_POOL_DATUM_HEX =
            "d8799f444e4f4e45d87980d8799fd8799f4040ffd8799f4040ff1901cb000000d87b9f1864187d1864d8798"
                    + "0ffd87b9f181c05ff0000d8798040ffd87b9f581cb2324fbdcace499f6f1a9599daaebd707eb0ca70e"
                    + "dbd6676fa20520bffd8799fd87a9f581ce302d1bee8142bee85f7e76f68399a170b2a1454ac19ffd99"
                    + "27332d3ffd8799fd8799fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6c"
                    + "a8dc3ffffffff5820d55be7e55a60e762913dfdfd08d4a284759376219637eb5dcc484d5481ce41939"
                    + "fd8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62d8799f490014df10"
                    + "74464c4454ffd8799f581c9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f49000"
                    + "de1406f766f3633ffffff9f1864ff9f1832ffd87a80ff";

    private static PoolTxEncoder.PoolDatum chainPoolDatum() {
        var commonData = new RequestTxEncoder.CommonData(
                AssetType.ada(), AssetType.ada(),
                BigInteger.valueOf(459), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                        BigInteger.valueOf(100), false),
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                BigInteger.ZERO, BigInteger.ZERO, false, "");
        return new PoolTxEncoder.PoolDatum(
                "4e4f4e45",
                RequestTxEncoder.unit(),
                commonData,
                new AuthorizationMethod.CardanoWithdrawScript(POOL_MANAGER_POLICY),
                lenderBondAddress(),
                LENDER_BOND_INLINE_DATUM_HASH,
                List.of(new CollateralAsset(TFLDT_POLICY, Optional.of(TFLDT_NAME),
                        new AssetType(FLUID_ORACLE_POLICY, FLUID_ORACLE_NAME))),
                List.of(BigInteger.valueOf(100)),
                List.of(BigInteger.valueOf(50)),
                true);
    }

    @Test
    void chainPoolDatumEncodesToTheExactDeployedBytes() {
        assertEquals(CHAIN_POOL_DATUM_HEX, hex(PoolTxEncoder.poolDatum(chainPoolDatum())));
    }

    /** The chain golden is genuinely FluidTokens' on-chain datum: these bytes hash to its datum hash. */
    @Test
    void theChainPoolDatumBytesMatchTheirOwnDatumHash() {
        String datumHash = HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(CHAIN_POOL_DATUM_HEX)));
        assertEquals("9aeed63fa670893d582a1ac2ea5ab84060040c9c8e37366f7317f3fe21327c24", datumHash,
                "the pool UTxO 0edd513b…#0 carries an inline datum with this hash");
    }

    // =============================================================================================
    // Chain golden 2 — the fee-0 bond datum the pool commits to (d2a85126…#3)
    // =============================================================================================

    /**
     * The inline datum of {@code d2a85126…#3}, verbatim — the fee-0 bond datum a loan from the deployed
     * pool carries. The same bytes {@code RealLoanDryEvalTest} records off preview; transcribed again
     * here because that field is {@code private} and this slice's allowlist does not include that file.
     */
    static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd879"
                    + "8000581d00746eab912831d705649ad01a3f349eaefa3fb5e57e7b170c07063e83d8799f4040f"
                    + "fff";

    /** The chain {@code LOAN_ID} of the loan that pool originated ({@code RealLoanDryEvalTest}). */
    private static final String LOAN_ID = "124e7c5db2b2a8905d889ffd90ae84e3a227dd271f999799d56b02ae";

    /**
     * The fee-0 {@link LenderManagerDatum} a loan from the deployed pool actually carries, as a
     * {@link LenderManagerDatum}. {@code LoanFixtures.encode} of this reproduces {@link #BOND_DATUM_HEX}
     * exactly, and its {@code blake2b_256} is the pool's committed {@link #LENDER_BOND_INLINE_DATUM_HASH}.
     */
    private static LenderManagerDatum feeZeroBondDatum() {
        return new LenderManagerDatum(
                new AuthorizationMethod.CardanoSignature("ea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934"),
                LoanFixtures.inlineKeyStakeCredential(LENDER_STAKE_KEY),
                false,
                BigInteger.ZERO,
                POOL_NFT_NAME,
                AssetType.ada());
    }

    /**
     * <b>Proving the bond-datum hashing pipeline before we trust it with fee 100.</b> The pool commits
     * to the whole bond datum by hash, and our factory pool ({@link PoolFixtures}) computes that hash
     * through the very same {@code LoanFixtures.encode → blake2b_256}. Here that pipeline reproduces
     * FluidTokens' committed fee-0 value exactly, byte-for-byte with the recorded chain datum first.
     */
    @Test
    void theFeeZeroBondDatumHashesToThePoolsCommittedValue() {
        String encoded = LoanFixtures.hex(feeZeroBondDatum());
        assertEquals(BOND_DATUM_HEX, encoded,
                "LoanFixtures.encode must reproduce the recorded chain bond datum bytes");

        String hash;
        try {
            hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                    LoanFixtures.encode(feeZeroBondDatum()).serializeToBytes()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(LENDER_BOND_INLINE_DATUM_HASH, hash,
                "the fee-0 bond datum's blake2b_256 is the pool's lenderBondInlineDatumHash");
    }

    /** And fee 100 changes it: the fee is inside the hashed datum, so a different fee is a different pool. */
    @Test
    void aDifferentFeeChangesTheCommittedHash() {
        LenderManagerDatum feeHundred = new LenderManagerDatum(
                feeZeroBondDatum().lenderAuth(), feeZeroBondDatum().lenderStakeCredential(),
                false, BigInteger.valueOf(100), feeZeroBondDatum().poolId(), AssetType.ada());
        String hash;
        try {
            hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                    LoanFixtures.encode(feeHundred).serializeToBytes()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertNotEquals(LENDER_BOND_INLINE_DATUM_HASH, hash,
                "changing liquidationFeePerMille must change the committed bond-datum hash");
    }

    // =============================================================================================
    // Chain golden 3 — BorrowData and its wrapped redeemer (S2 template values)
    // =============================================================================================

    private static final String BORROWER_PAYMENT_KEY = "65997c7f8d4dcd677096be2a6b3ba882be0f79b7280aa2193ba20c2b";
    private static final String BORROWER_STAKE_KEY = "9e39d6f9824de5f24ac1d73243ebd54bbcaf764e56de11d0c23db9a8";

    private static Address borrowerAddress() {
        return AddressProvider.getBaseAddress(Credential.fromKey(BORROWER_PAYMENT_KEY),
                Credential.fromKey(BORROWER_STAKE_KEY), LoanFixtures.NETWORK);
    }

    /**
     * The template {@code BorrowData}, decoded field for field from the chain-shaped literal the S1
     * contract specifies:
     * <pre>
     * d879 9f                                       BorrowData constr 0, 9 fields
     *   d879 9f                                       borrowerAddress: base VK/VK
     *     d879 9f 581c &lt;65997c7f…&gt; ff                    payment_credential = VerificationKey
     *     d879 9f d879 9f d879 9f 581c &lt;9e39d6f9…&gt; ff ff ff   Some(Inline(VerificationKey))
     *   ff
     *   03                                            outputWithLenderTokenIndex        = 3
     *   02                                            outputWithBorrowerTokenIndex       = 2
     *   00                                            principalOracleRefInputIndex       = 0
     *   00                                            chosenCollateralIndex              = 0
     *   07                                            chosenCollateralOracleRefInputIndex = 7
     *   1a 02faf080                                   wantedPrincipalAmount = 50_000_000
     *   581d &lt;00746eab…&gt;                              poolId = the 29-byte pool NFT name
     *   01                                            permissionedConditionWithdrawIndex = 1
     * ff
     * </pre>
     * <b>Note</b>: 126 bytes, ending in a single {@code ff}. The S1 contract's transcribed literal for
     * this value carried one <em>extra</em> trailing {@code ff} (a 127th byte), which would make it an
     * invalid stand-alone {@code BorrowData} (trailing data); it is dropped here and the encoder
     * reproduces the clean 126-byte value, whose {@code poolId} matches {@link #POOL_NFT_NAME}.
     */
    private static final String BORROW_DATA_HEX =
            "d8799fd8799fd8799f581c65997c7f8d4dcd677096be2a6b3ba882be0f79b7280aa2193ba20c2bffd8799fd8"
                    + "799fd8799f581c9e39d6f9824de5f24ac1d73243ebd54bbcaf764e56de11d0c23db9a8ffffffff030"
                    + "20000071a02faf080581d00746eab912831d705649ad01a3f349eaefa3fb5e57e7b170c07063e830"
                    + "1ff";

    private static PoolTxEncoder.BorrowData templateBorrowData() {
        return new PoolTxEncoder.BorrowData(
                borrowerAddress(), 3, 2, 0, 0, 7,
                BigInteger.valueOf(50_000_000), POOL_NFT_NAME, 1);
    }

    @Test
    void borrowDataEncodesToTheExactPinnedBytes() {
        assertEquals(BORROW_DATA_HEX, hex(PoolTxEncoder.borrowData(templateBorrowData())));
        assertEquals(POOL_NFT_NAME.length() / 2, 29, "the poolId is the 29-byte pool NFT name");
    }

    /**
     * The wrapped redeemer {@code PoolBorrowActionWithdrawRedeemer(4, [templateBorrowData])}:
     * <pre>
     * d879 9f 04 9f &lt;BORROW_DATA_HEX&gt; ff ff
     * </pre>
     */
    @Test
    void poolBorrowActionWithdrawRedeemerEncodesToTheExactPinnedBytes() {
        String expected = "d8799f049f" + BORROW_DATA_HEX + "ffff";
        assertEquals(expected, hex(PoolTxEncoder.poolBorrowActionWithdrawRedeemer(
                4, List.of(templateBorrowData()))));
    }

    // =============================================================================================
    // Chain golden 4 — the NFT asset names
    // =============================================================================================

    /** The pool UTxO's own output reference, {@code 0edd513b…#0} — spent to originate a loan from it. */
    private static final TransactionInput POOL_OUTPUT_REF = new TransactionInput(
            "0edd513b5f2f82e15145434d0d8578fc289cbdff0575543764ff0a5929991f26", 0);

    /**
     * The lender-/borrower-bond NFT name is the unprefixed 28-byte {@code hash_output_ref} of the
     * pool's own reference. For {@code 0edd513b…#0} it is {@code 124e7c5d…}, which is the real
     * {@code LOAN_ID} of the loan that pool originated ({@code RealLoanDryEvalTest}) — so this is a
     * genuine agreement between this repo's hashing and the chain, not a tautology.
     */
    @Test
    void theBondNftNameIsTheUnprefixedHashOfThePoolsOwnRef() {
        assertEquals(LOAN_ID, PoolTxEncoder.bondAssetName(POOL_OUTPUT_REF),
                "the bond NFT name is the same 28-byte hash the deployed loan carries as its LOAN_ID");
    }

    /**
     * The pool NFT name is that same 28-byte hash with a 1-byte token-index prefix. Pinned here against
     * the fully resolvable {@code 0edd513b…#0} reference.
     * <p>
     * <b>Offline limitation, reported to the ticket owner:</b> the deployed pool's <em>own</em> NFT
     * name {@link #POOL_NFT_NAME} is {@code poolAssetName(0, seed)} where {@code seed} is the pool-create
     * input {@code 2aab420b…#2}. That seed's full 32-byte transaction id cannot be resolved in this
     * repo — it needs a Blockfrost lookup and no key is available offline — so {@code POOL_NFT_NAME} is
     * pinned from the value the real bond / loan datum carries as its {@code poolId} rather than
     * recomputed from the seed. The {@code poolAssetName} mechanism itself is chain-arbitrated through
     * {@code 0edd513b…#0} above and here.
     */
    @Test
    void thePoolNftNameIsTheIndexPrefixFollowedByTheHashedOutputReference() {
        String name = PoolTxEncoder.poolAssetName(0, POOL_OUTPUT_REF);
        assertEquals("00124e7c5db2b2a8905d889ffd90ae84e3a227dd271f999799d56b02ae", name);
        assertEquals(29, name.length() / 2, "the pool NFT name is 29 bytes: 1-byte prefix + 28-byte hash");
        assertEquals("00", name.substring(0, 2), "the index prefix of the only minted token is 0x00");
        // The deployed pool's own name is the same shape; its poolId is what a loan from it carries.
        assertEquals("00", POOL_NFT_NAME.substring(0, 2));
        assertEquals(29, POOL_NFT_NAME.length() / 2);
    }

    // =============================================================================================
    // Redeemer pins (hand-derived)
    // =============================================================================================

    @Test
    void poolMintRedeemerEncodesToTheExactPinnedBytes() {
        // d879 9f 00 <OutputReference ee×32 #0> ff
        String expected = "d8799f00d8799f5820" + "ee".repeat(32) + "00ffff";
        assertEquals(expected, hex(PoolTxEncoder.poolMintRedeemer(0,
                new TransactionInput("ee".repeat(32), 0))));

        // A two-byte configRefInputIndex (300 = 0x012c) and a non-zero output index (7).
        String expectedLarge = "d8799f19012cd8799f5820" + "ab".repeat(32) + "07ffff";
        assertEquals(expectedLarge, hex(PoolTxEncoder.poolMintRedeemer(300,
                new TransactionInput("ab".repeat(32), 7))));
    }

    /**
     * The four {@code Action} alternatives, each a fieldless constructor: {@code d879 80} (Cancel),
     * {@code d87a 80} (Borrow), {@code d87b 80} (SellLenderPosition), {@code d87c 80} (Compound). The
     * {@code configRefInputIndex} is varied so a hardcoded zero cannot pass, and the tag is the only
     * thing distinguishing the actions.
     */
    @Test
    void poolWithdrawRedeemerEncodesEachActionToTheExactPinnedBytes() {
        assertEquals("d8799f00d87980ff", hex(PoolTxEncoder.poolWithdrawRedeemer(0, PoolTxEncoder.ACTION_CANCEL)));
        assertEquals("d8799f05d87a80ff", hex(PoolTxEncoder.poolWithdrawRedeemer(5, PoolTxEncoder.ACTION_BORROW)));
        assertEquals("d8799f07d87b80ff",
                hex(PoolTxEncoder.poolWithdrawRedeemer(7, PoolTxEncoder.ACTION_SELL_LENDER_POSITION)));
        assertEquals("d8799f09d87c80ff",
                hex(PoolTxEncoder.poolWithdrawRedeemer(9, PoolTxEncoder.ACTION_COMPOUND)));
    }

    @Test
    void cancelDataAndCancelActionRedeemerEncodeToTheExactPinnedBytes() {
        assertEquals("d8799f581d" + POOL_NFT_NAME + "ff",
                hex(PoolTxEncoder.cancelData(POOL_NFT_NAME)));

        // PoolCancelActionWithdrawRedeemer(3, [idA, idB]) — two same-length ids so the length header
        // does no discriminating; only the content does.
        String idA = "0a" + "a1".repeat(28);
        String idB = "0b" + "b2".repeat(28);
        String expected = "d8799f039f" + "d8799f581d" + idA + "ff" + "d8799f581d" + idB + "ff" + "ffff";
        assertEquals(expected, hex(PoolTxEncoder.poolCancelActionWithdrawRedeemer(3, List.of(idA, idB))));
    }

    // =============================================================================================
    // Pool-manager pins (hand-derived) — T-024
    // =============================================================================================

    /**
     * {@code PoolManagerDatum}, both authorisation shapes and both interesting fee values.
     * <pre>
     * d879 9f                                   PoolManagerDatum constr 0
     *   d879 9f 581c &lt;d3×28&gt; ff                   [0] poolOwnerAuth = CardanoSignature (constr 0)
     *   00                                        [1] compoudingFeePerMille = 0
     * ff
     * </pre>
     * The second case swaps the authorisation to {@code CardanoWithdrawScript} (constr 2, {@code d87b})
     * and the fee to 7, so neither field can be a constant the encoder ignores.
     */
    @Test
    void poolManagerDatumEncodesToTheExactPinnedBytes() {
        assertEquals("d8799f" + "d8799f581c" + "d3".repeat(28) + "ff" + "00" + "ff",
                hex(PoolTxEncoder.poolManagerDatum(new PoolTxEncoder.PoolManagerDatum(
                        new AuthorizationMethod.CardanoSignature("d3".repeat(28)), BigInteger.ZERO))));

        assertEquals("d8799f" + "d87b9f581c" + "c4".repeat(28) + "ff" + "07" + "ff",
                hex(PoolTxEncoder.poolManagerDatum(new PoolTxEncoder.PoolManagerDatum(
                        new AuthorizationMethod.CardanoWithdrawScript("c4".repeat(28)),
                        BigInteger.valueOf(7)))));
    }

    /**
     * {@code PoolManagerMintRedeemer}'s two fields are <b>both {@code Int}</b> and adjacent, so a
     * transposition is invisible to the compiler and to any pin taken at a single argument ordering.
     * Both orderings are therefore pinned, and asserted to differ.
     */
    @Test
    void poolManagerMintRedeemerEncodesToTheExactPinnedBytes() {
        assertEquals("d8799f0305ff", hex(PoolTxEncoder.poolManagerMintRedeemer(3, 5)));
        assertEquals("d8799f0503ff", hex(PoolTxEncoder.poolManagerMintRedeemer(5, 3)));
        assertNotEquals(hex(PoolTxEncoder.poolManagerMintRedeemer(3, 5)),
                hex(PoolTxEncoder.poolManagerMintRedeemer(5, 3)),
                "configRefInputIndex and poolWithdrawRedeemerIndex must not encode identically");

        // A two-byte configRefInputIndex (300 = 0x012c), so neither field is width-assumed.
        assertEquals("d8799f19012c07ff", hex(PoolTxEncoder.poolManagerMintRedeemer(300, 7)));
    }

    /**
     * The three {@code PoolManagerAction} alternatives: {@code d879 80} (CancelPoolManager),
     * {@code d87a 80} (UpdatePoolManager), {@code d87b 80} (CompoundLiquidity).
     * <p>
     * <b>Byte-identical to {@code PoolWithdrawRedeemer} at the same numbers</b> — {@code
     * poolManagerWithdrawRedeemer(0, PM_ACTION_CANCEL_POOL_MANAGER)} and
     * {@code poolWithdrawRedeemer(0, ACTION_CANCEL)} both produce {@code d8799f00d87980ff}. The two are
     * told apart only by which script consumes them, which is why the enum's numbering is pinned
     * separately in {@link PoolTxEncoderSchemaTest}: nothing in these bytes would catch using one
     * enum's constant on the other's redeemer.
     */
    @Test
    void poolManagerWithdrawRedeemerEncodesEachActionToTheExactPinnedBytes() {
        assertEquals("d8799f00d87980ff", hex(PoolTxEncoder.poolManagerWithdrawRedeemer(
                0, PoolTxEncoder.PM_ACTION_CANCEL_POOL_MANAGER)));
        assertEquals("d8799f05d87a80ff", hex(PoolTxEncoder.poolManagerWithdrawRedeemer(
                5, PoolTxEncoder.PM_ACTION_UPDATE_POOL_MANAGER)));
        assertEquals("d8799f07d87b80ff", hex(PoolTxEncoder.poolManagerWithdrawRedeemer(
                7, PoolTxEncoder.PM_ACTION_COMPOUND_LIQUIDITY)));

        assertEquals(hex(PoolTxEncoder.poolWithdrawRedeemer(0, PoolTxEncoder.ACTION_CANCEL)),
                hex(PoolTxEncoder.poolManagerWithdrawRedeemer(
                        0, PoolTxEncoder.PM_ACTION_CANCEL_POOL_MANAGER)),
                "the collision is real and documented — do not let a future change hide it");
    }

    /**
     * {@code CancelPoolManagerActionWithdrawRedeemer}: two adjacent {@code Int}s again (both orderings
     * pinned), then a {@code List<ByteArray>} of PoolManager NFT names.
     * <pre>
     * d879 9f
     *   03                                        [0] configRefInputIndex        = 3
     *   05                                        [1] poolWithdrawRedeemerIndex  = 5
     *   9f 581d &lt;0a a1×28&gt; 581d &lt;0b b2×28&gt; ff      [2] poolManagerNFTAssetNames
     * ff
     * </pre>
     * The names are <b>flat {@code ByteArray}s</b>, not wrapped in a per-element constructor — unlike
     * {@code PoolCancelActionWithdrawRedeemer}, whose elements are {@code CancelData} constructors
     * ({@code d8799f581d…ff}). Encoding one like the other is the mistake this pin exists to catch, so
     * the two are asserted to differ on the same names.
     */
    @Test
    void cancelPoolManagerActionWithdrawRedeemerEncodesToTheExactPinnedBytes() {
        String nameA = "0a" + "a1".repeat(28);
        String nameB = "0b" + "b2".repeat(28);

        assertEquals("d8799f03059f581d" + nameA + "581d" + nameB + "ffff",
                hex(PoolTxEncoder.cancelPoolManagerActionWithdrawRedeemer(3, 5, List.of(nameA, nameB))));
        assertEquals("d8799f05039f581d" + nameA + "581d" + nameB + "ffff",
                hex(PoolTxEncoder.cancelPoolManagerActionWithdrawRedeemer(5, 3, List.of(nameA, nameB))));

        assertNotEquals(hex(PoolTxEncoder.poolCancelActionWithdrawRedeemer(3, List.of(nameA, nameB))),
                hex(PoolTxEncoder.cancelPoolManagerActionWithdrawRedeemer(3, 5, List.of(nameA, nameB))),
                "the pool-manager names are flat ByteArrays; the pool ids are CancelData constructors");
    }

    @Test
    void bondRedeemerEncodesToTheExactPinnedBytes() {
        // d879 9f 9f <OutputReference ee×32 #0> <OutputReference ab×32 #7> ff ff
        String expected = "d8799f9f"
                + "d8799f5820" + "ee".repeat(32) + "00ff"
                + "d8799f5820" + "ab".repeat(32) + "07ff"
                + "ffff";
        assertEquals(expected, hex(PoolTxEncoder.bondRedeemer(List.of(
                new TransactionInput("ee".repeat(32), 0),
                new TransactionInput("ab".repeat(32), 7)))));
    }

    // =============================================================================================
    // Field-index sentinel — PoolDatum
    // =============================================================================================

    private static final String SENTINEL_PERMISSIONED = "c0".repeat(28);
    private static final String SENTINEL_BOND_HASH = "ee".repeat(32);
    private static final String SENTINEL_AUTH = "d3".repeat(28);
    private static final String SENTINEL_COLLATERAL_POLICY = "c5".repeat(28);
    private static final String SENTINEL_COLLATERAL_ORACLE_POLICY = "c6".repeat(28);

    /** The sentinel's {@code CommonData}, identical to the request encoder's — its bytes are pinned there. */
    private static RequestTxEncoder.CommonData sentinelCommonData() {
        return new RequestTxEncoder.CommonData(
                new AssetType("a1".repeat(28), "5052494e"),
                new AssetType("a2".repeat(28), "4f5241434c45"),
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(24),
                new LiquidationMode.Liquidation(BigInteger.valueOf(5), BigInteger.valueOf(6),
                        BigInteger.valueOf(7), true),
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(8), BigInteger.valueOf(9)),
                BigInteger.valueOf(256), BigInteger.valueOf(4097), false, "bb".repeat(28));
    }

    private static final String SENTINEL_COMMON_DATA_HEX =
            "d8799f"
                    + "d8799f581c" + "a1".repeat(28) + "445052494eff"
                    + "d8799f581c" + "a2".repeat(28) + "464f5241434c45ff"
                    + "01020318" + "18"
                    + "d87b9f050607d87a80ff"
                    + "d87b9f0809ff"
                    + "190100" + "191001"
                    + "d87980"
                    + "581c" + "bb".repeat(28)
                    + "ff";

    /**
     * The sentinel {@code PoolDatum}: every same-typed field is byte-distinguishable, so a
     * transposition of the encoder's positional write order changes these bytes.
     * <pre>
     * d879 9f                                       PoolDatum constr 0, 10 fields
     *   581c &lt;c0×28&gt;                                 [0] permissionedConditionScriptHash (28 bytes)
     *   182a                                          [1] extraData = Int 42 (NOT the unit d87980)
     *   &lt;SENTINEL_COMMON_DATA_HEX&gt;                    [2] commonData
     *   d87b 9f 581c &lt;d3×28&gt; ff                       [3] lenderAuth = CardanoWithdrawScript (constr 2)
     *   &lt;botAddress base VK/VK&gt;                       [4] lenderBondAddress
     *   5820 &lt;ee×32&gt;                                  [5] lenderBondInlineDatumHash (32 bytes)
     *   9f d879 9f 581c &lt;c5×28&gt; d879 9f 44 434f4c4c ff  d879 9f 581c &lt;c6×28&gt; 44 46454544 ff ff ff  [6] collateralOptions
     *   9f 0b ff                                      [7] minCollateral = [11]
     *   9f 0c ff                                      [8] minCollateralDivider = [12]
     *   d87a 80                                       [9] dynamicCollateralPrice = True
     * ff
     * </pre>
     */
    private static final String SENTINEL_BOROWER_ADDRESS_HEX =
            "d8799f"
                    + "d8799f581c" + "11".repeat(28) + "ff"
                    + "d8799fd8799fd8799f581c" + "22".repeat(28) + "ffffff"
                    + "ff";

    private static final String SENTINEL_POOL_DATUM_HEX =
            "d8799f"
                    + "581c" + SENTINEL_PERMISSIONED
                    + "182a"
                    + SENTINEL_COMMON_DATA_HEX
                    + "d87b9f581c" + SENTINEL_AUTH + "ff"
                    + SENTINEL_BOROWER_ADDRESS_HEX
                    + "5820" + SENTINEL_BOND_HASH
                    + "9f"
                    + "d8799f581c" + SENTINEL_COLLATERAL_POLICY
                    + "d8799f44434f4c4cff"
                    + "d8799f581c" + SENTINEL_COLLATERAL_ORACLE_POLICY + "4446454544ff"
                    + "ff"
                    + "ff"
                    + "9f0bff"
                    + "9f0cff"
                    + "d87a80"
                    + "ff";

    private static PoolTxEncoder.PoolDatum sentinelPoolDatum() {
        return new PoolTxEncoder.PoolDatum(
                SENTINEL_PERMISSIONED,
                BigIntPlutusData.of(42),
                sentinelCommonData(),
                new AuthorizationMethod.CardanoWithdrawScript(SENTINEL_AUTH),
                AddressProvider.getBaseAddress(Credential.fromKey("11".repeat(28)),
                        Credential.fromKey("22".repeat(28)), LoanFixtures.NETWORK),
                SENTINEL_BOND_HASH,
                List.of(new CollateralAsset(SENTINEL_COLLATERAL_POLICY, Optional.of("434f4c4c"),
                        new AssetType(SENTINEL_COLLATERAL_ORACLE_POLICY, "46454544"))),
                List.of(BigInteger.valueOf(11)),
                List.of(BigInteger.valueOf(12)),
                true);
    }

    @Test
    void sentinelPoolDatumEncodesToTheExactPinnedBytes() {
        assertEquals(SENTINEL_COMMON_DATA_HEX, hex(RequestTxEncoder.commonData(sentinelCommonData())),
                "the sentinel's CommonData bytes are what the rest of the pin is built on");
        assertEquals(SENTINEL_POOL_DATUM_HEX, hex(PoolTxEncoder.poolDatum(sentinelPoolDatum())));
    }

    /**
     * Proves the sentinel really discriminates. The two {@code ByteArray} fields
     * (permissionedConditionScriptHash [0], lenderBondInlineDatumHash [5]) and the two
     * {@code List<Int>} fields (minCollateral [7], minCollateralDivider [8]) are the pairs the encoder
     * writes positionally and the record could transpose without the compiler noticing; swapping each
     * must change the bytes.
     */
    @Test
    void everySameTypedPoolDatumTranspositionChangesTheEncodedBytes() {
        forEachPair(List.of(0, 5), (a, b) -> assertNotEquals(SENTINEL_POOL_DATUM_HEX,
                hex(PoolTxEncoder.poolDatum(withSwapped(sentinelPoolDatum(), a, b))),
                "PoolDatum ByteArray fields " + a + " and " + b + " encode identically"));
        forEachPair(List.of(7, 8), (a, b) -> assertNotEquals(SENTINEL_POOL_DATUM_HEX,
                hex(PoolTxEncoder.poolDatum(withSwapped(sentinelPoolDatum(), a, b))),
                "PoolDatum List<Int> fields " + a + " and " + b + " encode identically"));
    }

    /**
     * {@code extraData} (index 1, a {@code Data}) and {@code dynamicCollateralPrice} (index 9, a
     * {@code Bool}) are different Java types, so the record cannot express that swap and the
     * transposition test above cannot cover it — but {@link PoolTxEncoder#poolDatum} writes both into
     * the same positional argument list. It is caught only because the sentinel's {@code extraData}
     * bytes differ from <em>both</em> {@code Bool} constructors, which is what this asserts.
     */
    @Test
    void sentinelExtraDataCannotCollideWithEitherBoolConstructor() {
        String extraData = hex(BigIntPlutusData.of(42));
        assertNotEquals("d87980", extraData, "sentinel extraData collides with Bool False");
        assertNotEquals("d87a80", extraData, "sentinel extraData collides with Bool True");
        assertEquals("d87980", hex(RequestTxEncoder.unit()),
                "the chain fixture's extraData is the unit, and this is why it cannot cover the swap");
    }

    // =============================================================================================
    // Field-index sentinel — BorrowData
    // =============================================================================================

    /**
     * The sentinel {@code BorrowData}: the six same-typed {@code long} index fields carry distinct,
     * byte-distinguishable values (one of them {@code -1}, exercising the CBOR negative-integer path),
     * so any transposition among them changes the bytes.
     * <pre>
     * d879 9f                                       BorrowData constr 0
     *   &lt;botAddress base VK/VK&gt;                       [0] borrowerAddress
     *   03                                            [1] outputWithLenderTokenIndex        = 3
     *   04                                            [2] outputWithBorrowerTokenIndex       = 4
     *   20                                            [3] principalOracleRefInputIndex       = -1
     *   05                                            [4] chosenCollateralIndex              = 5
     *   1818                                          [5] chosenCollateralOracleRefInputIndex = 24
     *   1a 02faf080                                   [6] wantedPrincipalAmount              = 50_000_000
     *   581d &lt;0a a1×28&gt;                               [7] poolId (29 bytes)
     *   06                                            [8] permissionedConditionWithdrawIndex = 6
     * ff
     * </pre>
     */
    private static final String SENTINEL_BORROW_DATA_HEX =
            "d8799f"
                    + SENTINEL_BOROWER_ADDRESS_HEX
                    + "03" + "04" + "20" + "05" + "1818"
                    + "1a02faf080"
                    + "581d" + "0a" + "a1".repeat(28)
                    + "06"
                    + "ff";

    private static PoolTxEncoder.BorrowData sentinelBorrowData() {
        return new PoolTxEncoder.BorrowData(
                AddressProvider.getBaseAddress(Credential.fromKey("11".repeat(28)),
                        Credential.fromKey("22".repeat(28)), LoanFixtures.NETWORK),
                3, 4, -1, 5, 24,
                BigInteger.valueOf(50_000_000),
                "0a" + "a1".repeat(28),
                6);
    }

    @Test
    void sentinelBorrowDataEncodesToTheExactPinnedBytes() {
        assertEquals(SENTINEL_BORROW_DATA_HEX, hex(PoolTxEncoder.borrowData(sentinelBorrowData())));
    }

    /**
     * Proves the sentinel discriminates: the six {@code long} index fields (record components 1–5 and
     * 8) are the ones the encoder writes positionally and the record could transpose; swapping each
     * pair must change the bytes.
     */
    @Test
    void everySameTypedBorrowDataTranspositionChangesTheEncodedBytes() {
        forEachPair(List.of(1, 2, 3, 4, 5, 8), (a, b) -> assertNotEquals(SENTINEL_BORROW_DATA_HEX,
                hex(PoolTxEncoder.borrowData(withSwapped(sentinelBorrowData(), a, b))),
                "BorrowData long fields " + a + " and " + b + " encode identically"));
    }

    // =============================================================================================
    // plumbing
    // =============================================================================================

    private static void forEachPair(List<Integer> indices, BiConsumer<Integer, Integer> check) {
        for (int a = 0; a < indices.size(); a++) {
            for (int b = a + 1; b < indices.size(); b++) {
                check.accept(indices.get(a), indices.get(b));
            }
        }
    }

    /** Rebuilds {@code record} through its canonical constructor with components {@code i} and {@code j} exchanged. */
    @SuppressWarnings("unchecked")
    private static <T> T withSwapped(T record, int i, int j) {
        try {
            var components = record.getClass().getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int k = 0; k < components.length; k++) {
                args[k] = components[k].getAccessor().invoke(record);
                types[k] = components[k].getType();
            }
            Object swap = args[i];
            args[i] = args[j];
            args[j] = swap;
            return (T) record.getClass().getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot rebuild " + record.getClass().getSimpleName()
                    + " with components " + i + " and " + j + " swapped", e);
        }
    }
}
