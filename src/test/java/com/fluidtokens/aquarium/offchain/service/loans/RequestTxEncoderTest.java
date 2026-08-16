package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden serialized-hex tests for {@link RequestTxEncoder}, in {@link LiquidationTxEncoderTest}'s
 * discipline: every expected hex below was derived once, <b>independently of
 * {@link RequestTxEncoder} itself</b>, by reasoning through the CBOR each field must produce
 * (constructor tags {@code 121 + alternative}, indefinite-length lists — {@code 9f…ff} — for
 * non-empty field lists, the definite empty array {@code 0x80} for empty ones, minimal-length
 * integer and bytestring headers, CBOR major type 1 for negative integers) and is transcribed here
 * as a literal constant with a byte-by-byte breakdown comment. None was captured by running the
 * encoder and copying its output — a change to the encoder that still satisfies
 * {@link RequestTxEncoderSchemaTest} would still be caught here.
 *
 * <h2>Why the goldens carry more weight in this slice than usual</h2>
 * {@code request.request}'s {@code mint} handler never reads the request output's datum, so
 * {@link RequestMintDryEvalTest} cannot arbitrate a single field of the {@code RequestDatum}. These
 * literals and the schema pins are the only defences the datum has until S3's Cancel spends it.
 *
 * <h2>Two fixtures: the production one and the field-index sentinel</h2>
 * The production fixture ({@link RequestFixtures}) is the one that goes on chain, and its values are
 * chosen for what the validators demand, not for legibility. The cost is that most of its fields are
 * <b>byte-indistinguishable</b>: six of {@code CommonData}'s twelve encode as {@code 00}, its two
 * {@code Asset}s are both ada, and {@code RequestDatum.extraData} and
 * {@code RequestDatum.dynamicCollateralPrice} both encode as {@code d87980}. A transposition of any
 * two of those inside {@link RequestTxEncoder} would leave the production goldens <em>green</em>
 * while writing a plausible-but-wrong datum — and nothing throws, because every transposed field is
 * still well-typed.
 * <p>
 * The <b>field-index sentinel</b> ({@link #SENTINEL_REQUEST_DATUM_HEX}) closes that hole: a second
 * fixture, used nowhere but here, in which every field is byte-distinguishable from every other, so
 * any transposition of the encoder's write order changes the serialised bytes.
 * {@link #everySameTypedSentinelTranspositionChangesTheEncodedBytes} proves the sentinel really has
 * that property rather than asserting it in a comment. The production fixture is deliberately
 * <b>not</b> "improved" to be more distinguishable — its values are load-bearing.
 * <p>
 * <b>DO NOT DELETE EITHER FIXTURE AS REDUNDANT. They defend different things, and that is measured,
 * not argued.</b> This was not hypothetical: two transpositions
 * ({@code principalAsset}/{@code principalOracleAsset} with {@code interestRate}/
 * {@code installmentPeriod}, and {@code extraData} with {@code dynamicCollateralPrice}) survived the
 * entire 353-test suite before the sentinel existed. When the second was re-run afterwards, the
 * <em>production</em> golden stayed green and only the sentinel went red. The production golden
 * proves the bytes we will actually post are right; the sentinel proves the field mapping that
 * produced them is right. Delete the sentinel and the hole reopens silently, because a green
 * production golden is exactly what it looked like the first time.
 * <p>
 * The general property is <b>fixture discrimination</b> — can this fixture tell field <i>i</i> from
 * field <i>j</i> at all? — and it defeats more than transpositions: an omitted zero or a duplicated
 * neighbour is equally invisible in a uniform fixture. A test can be correct, its golden
 * hand-derived, its intent documented, and its discriminating power zero.
 *
 * <h2>The three cross-checks</h2>
 * The bottom of this file compares {@link RequestTxEncoder}'s output against the two encoders in
 * this repo that <em>are</em> arbitrated by real chain bytes — {@link LiquidationTxEncoder}, whose
 * shared sub-encoders are exercised by {@code LiquidateDryEvalTest} against the deployed
 * validators, and {@code LoanFixtures}' {@code LoanDatum} encoder, which is pinned by re-encoding
 * datums recorded off preview ({@code src/test/resources/loans-v4/preview-loan-datums.hex}). Those
 * comparisons are <b>additional to</b> the literals, never a substitute: they are not
 * equal-by-construction, because the other side is an independent implementation.
 */
class RequestTxEncoderTest {

    private static String hex(PlutusData data) {
        try {
            return HexUtil.encodeHexString(data.serializeToBytes());
        } catch (Exception e) {
            throw new AssertionError("cannot serialise", e);
        }
    }

    // ---- the fixture's constituent hashes, spelled out so every literal below is readable -------

    private static final String BORROWER_PAYMENT_KEY = "11".repeat(28);
    private static final String BORROWER_STAKE_KEY = "22".repeat(28);
    private static final String SOME_SCRIPT_HASH = "33".repeat(28);

    private static final String TFLDT_POLICY = "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    private static final String TFLDT_NAME = "0014df1074464c4454";
    private static final String C3_POLICY = "decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3";
    private static final String C3_NAME = "4f7261636c6546656564";

    /** {@code LoanFixtures.botAddress()} is exactly this pair, and this test depends on that. */
    private static Address borrowerAddress() {
        Address address = new Address(LoanFixtures.botAddress());
        assertEquals(BORROWER_PAYMENT_KEY,
                HexUtil.encodeHexString(address.getPaymentCredentialHash().orElseThrow()),
                "the golden literals below assume LoanFixtures.botAddress()'s payment key");
        assertEquals(BORROWER_STAKE_KEY,
                HexUtil.encodeHexString(address.getDelegationCredentialHash().orElseThrow()),
                "the golden literals below assume LoanFixtures.botAddress()'s stake key");
        return address;
    }

    // ---- CommonData ------------------------------------------------------------------------------

    /**
     * The fixture {@code CommonData}, checked field by field:
     * <pre>
     * d879 9f                          tag 121 = CommonData constr 0, indefinite list, 12 fields
     *   d879 9f 40 40 ff                  Asset constr 0 [bytes(0), bytes(0)]  = principalAsset (ada)
     *   d879 9f 40 40 ff                  Asset constr 0 [bytes(0), bytes(0)]  = principalOracleAsset (ada)
     *   00                                uint 0 = interestRate
     *   00                                uint 0 = installmentPeriod
     *   00                                uint 0 = totalInstallments
     *   00                                uint 0 = initialGracePeriod
     *   d87b 9f                           tag 123 = LiquidationMode constr 2 (Liquidation)
     *     1902bc                             uint(2 bytes) 0x02bc =  700 = lTV
     *     1903e8                             uint(2 bytes) 0x03e8 = 1000 = lTVDivider
     *     20                                 NEGATIVE int, 1-byte form, n=0 -&gt; -1-0 = -1
     *                                        = partialLiquidationPenaltyPerMille
     *     d879 80                            tag 121 = Bool constr 0 (False), empty definite list
     *                                        = equityInPrincipalCurrency
     *   ff
     *   d87b 9f 00 00 ff                  tag 123 = RepaymentMode constr 2 (PerpetualLoan)
     *                                        [apyIncreaseLinearCoefficient 0, max_possible_recasts 0]
     *   00                                uint 0 = repaymentTimeWindow
     *   00                                uint 0 = penaltyFeeForLateRepayment
     *   d879 80                           Bool constr 0 (False) = repaymentReceipts
     *   40                                bytes(0) = borrowerBondDestinationScriptHash
     * ff
     * </pre>
     */
    private static final String COMMON_DATA_HEX =
            "d8799f"
                    + "d8799f4040ff"
                    + "d8799f4040ff"
                    + "00" + "00" + "00" + "00"
                    + "d87b9f1902bc1903e820d87980ff"
                    + "d87b9f0000ff"
                    + "00" + "00"
                    + "d87980"
                    + "40"
                    + "ff";

    @Test
    void commonDataEncodesToTheExactPinnedBytes() {
        assertEquals(COMMON_DATA_HEX, hex(RequestTxEncoder.commonData(RequestFixtures.commonData())));
    }

    // ---- RequestDatum ----------------------------------------------------------------------------

    /**
     * The borrower's {@code Address} — a base address, key payment credential and key stake
     * credential:
     * <pre>
     * d879 9f                                    Address constr 0, indefinite list, 2 fields
     *   d879 9f 581c &lt;11 x28&gt; ff                    VerificationKey constr 0 = payment_credential
     *   d879 9f                                     Option constr 0 = Some
     *     d879 9f                                      StakeCredential constr 0 = Inline
     *       d879 9f 581c &lt;22 x28&gt; ff                     VerificationKey constr 0
     *     ff
     *   ff
     * ff
     * </pre>
     */
    private static final String BORROWER_ADDRESS_HEX =
            "d8799f"
                    + "d8799f581c" + BORROWER_PAYMENT_KEY + "ff"
                    + "d8799fd8799fd8799f581c" + BORROWER_STAKE_KEY + "ffffff"
                    + "ff";

    /**
     * {@code CardanoSignature { hash }} for the borrower's payment key:
     * <pre>
     * d879 9f 581c &lt;11 x28&gt; ff      CardanoSignature constr 0 [bytestring(28)]
     * </pre>
     */
    private static final String BORROWER_AUTH_HEX =
            "d8799f581c" + BORROWER_PAYMENT_KEY + "ff";

    /**
     * The tFLDT {@code CollateralAsset}, {@code Some(assetName)}:
     * <pre>
     * d879 9f                                     CollateralAsset constr 0, 3 fields
     *   581c &lt;28 bytes&gt;                              bytestring(28) = policyId (tFLDT)
     *   d879 9f 49 &lt;9 bytes&gt; ff                      Option constr 0 = Some(bytestring(9) assetName)
     *   d879 9f 581c &lt;28&gt; 4a &lt;10&gt; ff                  Asset constr 0 = oracleTokenAsset
     *                                                  (Charli3 policy, "OracleFeed")
     * ff
     * </pre>
     */
    private static final String COLLATERAL_HEX =
            "d8799f"
                    + "581c" + TFLDT_POLICY
                    + "d8799f49" + TFLDT_NAME + "ff"
                    + "d8799f581c" + C3_POLICY + "4a" + C3_NAME + "ff"
                    + "ff";

    /**
     * The full fixture {@code RequestDatum}:
     * <pre>
     * d879 9f                          RequestDatum constr 0, indefinite list, 12 fields
     *   44 4e4f4e45                       bytestring(4) ASCII "NONE" = permissionedConditionScriptHash
     *   d879 80                           Constr 0 [] = extraData (Aiken unit)
     *   &lt;COMMON_DATA_HEX&gt;                 commonData
     *   &lt;BORROWER_AUTH_HEX&gt;               borrowerAuth
     *   &lt;BORROWER_ADDRESS_HEX&gt;            borrowerAddress
     *   &lt;COLLATERAL_HEX&gt;                  collateral
     *   03                                uint 3 = minPrincipal
     *   01                                uint 1 = minPrincipalDivider
     *   1a 068e7780                       uint(4 bytes) 0x068e7780 = 110_000_000 = maxPrincipal
     *   d879 80                           Bool constr 0 (False) = dynamicCollateralPrice
     *   1b 000003bb2cc3d800               uint(8 bytes) 0x3bb2cc3d800 = 4_102_444_800_000
     *                                     = requestExpiration (2100-01-01T00:00:00Z)
     *   00                                uint 0 = requestExpirationPenalty
     * ff
     * </pre>
     */
    static final String REQUEST_DATUM_HEX =
            "d8799f"
                    + "444e4f4e45"
                    + "d87980"
                    + COMMON_DATA_HEX
                    + BORROWER_AUTH_HEX
                    + BORROWER_ADDRESS_HEX
                    + COLLATERAL_HEX
                    + "03"
                    + "01"
                    + "1a068e7780"
                    + "d87980"
                    + "1b000003bb2cc3d800"
                    + "00"
                    + "ff";

    @Test
    void requestDatumEncodesToTheExactPinnedBytes() {
        assertEquals(REQUEST_DATUM_HEX,
                hex(RequestTxEncoder.requestDatum(RequestFixtures.requestDatum(LoanFixtures.botAddress()))));
    }

    // ---- the field-index sentinel ------------------------------------------------------------------
    //
    // A second fixture whose only job is to be byte-distinguishable in every position. It never goes
    // near a transaction; RequestFixtures stays the fixture that does. See the class javadoc.

    private static final String SENTINEL_PRINCIPAL_POLICY = "a1".repeat(28);
    private static final String SENTINEL_PRINCIPAL_ORACLE_POLICY = "a2".repeat(28);
    private static final String SENTINEL_BOND_DESTINATION = "bb".repeat(28);
    private static final String SENTINEL_PERMISSIONED_CONDITION = "c0".repeat(28);
    private static final String SENTINEL_COLLATERAL_POLICY = "c5".repeat(28);
    private static final String SENTINEL_COLLATERAL_ORACLE_POLICY = "c6".repeat(28);
    private static final String SENTINEL_AUTH_HASH = "d3".repeat(28);

    /** ASCII {@code "PRIN"}, {@code "ORACLE"}, {@code "COLL"}, {@code "FEED"} — all different lengths where they can be. */
    private static final String SENTINEL_PRINCIPAL_NAME = "5052494e";
    private static final String SENTINEL_PRINCIPAL_ORACLE_NAME = "4f5241434c45";
    private static final String SENTINEL_COLLATERAL_NAME = "434f4c4c";
    private static final String SENTINEL_COLLATERAL_ORACLE_NAME = "46454544";

    /**
     * {@code RequestDatum.extraData} for the sentinel. <b>Deliberately not the Aiken unit</b>
     * ({@code d87980}) the production fixture uses: unit is byte-identical to a {@code Bool} False,
     * so with unit here a swap of {@code extraData} (index 1) with {@code dynamicCollateralPrice}
     * (index 9) would be invisible. {@code 42} encodes as {@code 182a}, which collides with neither
     * {@code Bool} constructor — {@link #sentinelExtraDataCannotCollideWithEitherBoolConstructor}
     * asserts exactly that.
     */
    private static final PlutusData SENTINEL_EXTRA_DATA =
            com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(42);

    /**
     * The sentinel {@code CommonData}. Every {@code Int} is distinct and non-zero, and the encoded
     * widths are mixed (immediate / {@code 0x18 xx} / {@code 0x19 xxxx}) so no transposition can
     * accidentally preserve the byte string:
     * <pre>
     * d879 9f                                       CommonData constr 0, indefinite list, 12 fields
     *   d879 9f 581c &lt;a1 x28&gt; 44 5052494e ff           [0] principalAsset       = a1…  ‖ "PRIN"
     *   d879 9f 581c &lt;a2 x28&gt; 46 4f5241434c45 ff       [1] principalOracleAsset = a2…  ‖ "ORACLE"
     *   01                                            [2] interestRate               = 1
     *   02                                            [3] installmentPeriod          = 2
     *   03                                            [4] totalInstallments          = 3
     *   1818                                          [5] initialGracePeriod         = 24
     *                                                     (24 &gt; 23, so the 1-byte-follows form 0x18)
     *   d87b 9f                                       [6] liquidationMode: tag 123 = constr 2
     *     05                                              lTV                              = 5
     *     06                                              lTVDivider                       = 6
     *     07                                              partialLiquidationPenaltyPerMille = 7
     *     d87a 80                                         tag 122 = Bool constr 1 (True)
     *                                                     = equityInPrincipalCurrency
     *   ff
     *   d87b 9f 08 09 ff                              [7] repaymentMode: tag 123 = PerpetualLoan
     *                                                     [apyIncreaseLinearCoefficient 8,
     *                                                      max_possible_recasts 9]
     *   190100                                        [8] repaymentTimeWindow        = 256  = 0x0100
     *   191001                                        [9] penaltyFeeForLateRepayment = 4097 = 0x1001
     *   d879 80                                       [10] repaymentReceipts = False
     *   581c &lt;bb x28&gt;                                 [11] borrowerBondDestinationScriptHash
     * ff
     * </pre>
     */
    static final String SENTINEL_COMMON_DATA_HEX =
            "d8799f"
                    + "d8799f581c" + SENTINEL_PRINCIPAL_POLICY + "44" + SENTINEL_PRINCIPAL_NAME + "ff"
                    + "d8799f581c" + SENTINEL_PRINCIPAL_ORACLE_POLICY + "46"
                    + SENTINEL_PRINCIPAL_ORACLE_NAME + "ff"
                    + "01"
                    + "02"
                    + "03"
                    + "1818"
                    + "d87b9f" + "05" + "06" + "07" + "d87a80" + "ff"
                    + "d87b9f" + "08" + "09" + "ff"
                    + "190100"
                    + "191001"
                    + "d87980"
                    + "581c" + SENTINEL_BOND_DESTINATION
                    + "ff";

    /**
     * The sentinel {@code RequestDatum}:
     * <pre>
     * d879 9f                                       RequestDatum constr 0, indefinite list, 12 fields
     *   581c &lt;c0 x28&gt;                                 [0] permissionedConditionScriptHash
     *   182a                                          [1] extraData = Int 42 (NOT the unit d87980)
     *   &lt;SENTINEL_COMMON_DATA_HEX&gt;                    [2] commonData
     *   d87c 9f 581c &lt;d3 x28&gt; ff                      [3] borrowerAuth: tag 124 = constr 3
     *                                                     = CardanoMintScript { hash }
     *   &lt;BORROWER_ADDRESS_HEX&gt;                        [4] borrowerAddress
     *   d879 9f                                       [5] collateral: CollateralAsset constr 0
     *     581c &lt;c5 x28&gt;                                    policyId
     *     d879 9f 44 434f4c4c ff                           Some(bytestring(4) "COLL")
     *     d879 9f 581c &lt;c6 x28&gt; 44 46454544 ff             oracleTokenAsset = c6… ‖ "FEED"
     *   ff
     *   0b                                            [6] minPrincipal        = 11
     *   0c                                            [7] minPrincipalDivider = 12
     *   1903e8                                        [8] maxPrincipal        = 1000 = 0x03e8
     *   d87a 80                                       [9] dynamicCollateralPrice = True
     *   18c8                                          [10] requestExpiration  = 200 = 0xc8
     *   0d                                            [11] requestExpirationPenalty = 13
     * ff
     * </pre>
     */
    static final String SENTINEL_REQUEST_DATUM_HEX =
            "d8799f"
                    + "581c" + SENTINEL_PERMISSIONED_CONDITION
                    + "182a"
                    + SENTINEL_COMMON_DATA_HEX
                    + "d87c9f581c" + SENTINEL_AUTH_HASH + "ff"
                    + BORROWER_ADDRESS_HEX
                    + "d8799f"
                    + "581c" + SENTINEL_COLLATERAL_POLICY
                    + "d8799f44" + SENTINEL_COLLATERAL_NAME + "ff"
                    + "d8799f581c" + SENTINEL_COLLATERAL_ORACLE_POLICY + "44"
                    + SENTINEL_COLLATERAL_ORACLE_NAME + "ff"
                    + "ff"
                    + "0b"
                    + "0c"
                    + "1903e8"
                    + "d87a80"
                    + "18c8"
                    + "0d"
                    + "ff";

    private static RequestTxEncoder.CommonData sentinelCommonData() {
        return new RequestTxEncoder.CommonData(
                new AssetType(SENTINEL_PRINCIPAL_POLICY, SENTINEL_PRINCIPAL_NAME),
                new AssetType(SENTINEL_PRINCIPAL_ORACLE_POLICY, SENTINEL_PRINCIPAL_ORACLE_NAME),
                BigInteger.ONE,
                BigInteger.TWO,
                BigInteger.valueOf(3),
                BigInteger.valueOf(24),
                new LiquidationMode.Liquidation(BigInteger.valueOf(5), BigInteger.valueOf(6),
                        BigInteger.valueOf(7), true),
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(8), BigInteger.valueOf(9)),
                BigInteger.valueOf(256),
                BigInteger.valueOf(4097),
                false,
                SENTINEL_BOND_DESTINATION);
    }

    private static RequestTxEncoder.RequestDatum sentinelRequestDatum() {
        return new RequestTxEncoder.RequestDatum(
                SENTINEL_PERMISSIONED_CONDITION,
                SENTINEL_EXTRA_DATA,
                sentinelCommonData(),
                new AuthorizationMethod.CardanoMintScript(SENTINEL_AUTH_HASH),
                borrowerAddress(),
                new CollateralAsset(SENTINEL_COLLATERAL_POLICY,
                        Optional.of(SENTINEL_COLLATERAL_NAME),
                        new AssetType(SENTINEL_COLLATERAL_ORACLE_POLICY,
                                SENTINEL_COLLATERAL_ORACLE_NAME)),
                BigInteger.valueOf(11),
                BigInteger.valueOf(12),
                BigInteger.valueOf(1000),
                true,
                BigInteger.valueOf(200),
                BigInteger.valueOf(13));
    }

    /**
     * <b>The write-order pin for {@link RequestTxEncoder#commonData}.</b> Transposing any two of its
     * twelve {@code constr(0, ..)} arguments changes this hex. The production golden
     * ({@link #COMMON_DATA_HEX}) is blind to sixteen of those sixty-six transpositions: six of its
     * fields encode as {@code 00} (fifteen mutually invisible swaps) and two more both encode as
     * {@code d8799f4040ff} (the sixteenth).
     */
    @Test
    void sentinelCommonDataEncodesToTheExactPinnedBytes() {
        assertEquals(SENTINEL_COMMON_DATA_HEX, hex(RequestTxEncoder.commonData(sentinelCommonData())));
    }

    /** The same pin for {@link RequestTxEncoder#requestDatum}. */
    @Test
    void sentinelRequestDatumEncodesToTheExactPinnedBytes() {
        assertEquals(SENTINEL_REQUEST_DATUM_HEX,
                hex(RequestTxEncoder.requestDatum(sentinelRequestDatum())));
    }

    /**
     * Proves the sentinel is actually a sentinel. For every pair of fields that share a Java type —
     * and could therefore be transposed inside the encoder without the compiler noticing — swapping
     * that pair in the fixture must change the encoded bytes. If any pair ever became
     * byte-identical, the goldens above would stop covering it and this test says so by name.
     * <p>
     * The groups are the ones the encoder writes positionally into a single {@code constr(0, ..)}
     * call: {@code CommonData}'s two {@code Asset}s (indices 0, 1) and its six {@code Int}s
     * (2, 3, 4, 5, 8, 9); {@code RequestDatum}'s five {@code Int}s (6, 7, 8, 10, 11).
     */
    @Test
    void everySameTypedSentinelTranspositionChangesTheEncodedBytes() {
        for (List<Integer> group : List.of(List.of(0, 1), List.of(2, 3, 4, 5, 8, 9))) {
            forEachPair(group, (i, j) -> assertNotEquals(SENTINEL_COMMON_DATA_HEX,
                    hex(RequestTxEncoder.commonData(withSwapped(sentinelCommonData(), i, j))),
                    "sentinel CommonData fields " + i + " and " + j + " encode identically, so a "
                            + "transposition of them in RequestTxEncoder.commonData would go undetected"));
        }
        forEachPair(List.of(6, 7, 8, 10, 11), (i, j) -> assertNotEquals(SENTINEL_REQUEST_DATUM_HEX,
                hex(RequestTxEncoder.requestDatum(withSwapped(sentinelRequestDatum(), i, j))),
                "sentinel RequestDatum fields " + i + " and " + j + " encode identically, so a "
                        + "transposition of them in RequestTxEncoder.requestDatum would go undetected"));
    }

    /**
     * {@code extraData} (index 1) is a {@code Data} and {@code dynamicCollateralPrice} (index 9) is a
     * {@code Bool}, so the record cannot express that swap and
     * {@link #everySameTypedSentinelTranspositionChangesTheEncodedBytes} cannot cover it — but
     * {@link RequestTxEncoder#requestDatum} writes both into the same positional argument list and a
     * transposition there compiles fine. It is caught only because the sentinel's {@code extraData}
     * bytes differ from <em>both</em> {@code Bool} constructors, which is what this asserts.
     */
    @Test
    void sentinelExtraDataCannotCollideWithEitherBoolConstructor() {
        String extraData = hex(SENTINEL_EXTRA_DATA);
        assertNotEquals("d87980", extraData, "sentinel extraData collides with Bool False");
        assertNotEquals("d87a80", extraData, "sentinel extraData collides with Bool True");
        assertEquals("d87980", hex(RequestTxEncoder.unit()),
                "the production fixture's extraData is the unit, and this is why it cannot cover the swap");
    }

    private static void forEachPair(List<Integer> indices, java.util.function.BiConsumer<Integer, Integer> check) {
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

    // ---- Address ---------------------------------------------------------------------------------

    @Test
    void baseAddressWithKeyPaymentAndKeyStakeEncodesToTheExactPinnedBytes() {
        assertEquals(BORROWER_ADDRESS_HEX, hex(RequestTxEncoder.address(borrowerAddress())));
    }

    /**
     * The enterprise variant — script payment credential, {@code None} stake credential:
     * <pre>
     * d879 9f                                    Address constr 0, 2 fields
     *   d87a 9f 581c &lt;33 x28&gt; ff                    tag 122 = Script constr 1 = payment_credential
     *   d87a 80                                     tag 122 = Option constr 1 = None, empty list
     * ff
     * </pre>
     */
    @Test
    void enterpriseScriptAddressWithNoStakeCredentialEncodesToTheExactPinnedBytes() {
        String expected = "d8799f"
                + "d87a9f581c" + SOME_SCRIPT_HASH + "ff"
                + "d87a80"
                + "ff";

        Address enterprise = AddressProvider.getEntAddress(
                Credential.fromScript(SOME_SCRIPT_HASH), LoanFixtures.NETWORK);

        assertEquals(expected, hex(RequestTxEncoder.address(enterprise)));
    }

    /**
     * A pointer address is refused by name rather than encoded as a plausible {@code Inline}.
     * cardano-client-lib's {@code getDelegationCredential} would hand back a {@link Credential}
     * built from the pointer bytes, which is exactly the "plausible but wrong" outcome this encoder
     * must not produce.
     */
    @Test
    void aPointerAddressIsRefusedRatherThanEncoded() {
        Address pointer = AddressProvider.getPointerAddress(
                Credential.fromKey(BORROWER_PAYMENT_KEY),
                new com.bloxbean.cardano.client.address.Pointer(1L, 2, 3),
                LoanFixtures.NETWORK);

        var thrown = assertThrows(RequestTxEncoder.UnrepresentableAddressException.class,
                () -> RequestTxEncoder.address(pointer));
        assertTrue(thrown.getMessage().contains("pointer"), thrown.getMessage());
    }

    /** A reward address has no payment credential at all — also refused by name. */
    @Test
    void aRewardAddressIsRefusedRatherThanEncoded() {
        Address reward = new Address(LoanFixtures.rewardAddress(SOME_SCRIPT_HASH));
        assertThrows(RequestTxEncoder.UnrepresentableAddressException.class,
                () -> RequestTxEncoder.address(reward));
    }

    // ---- CollateralAsset ----------------------------------------------------------------------------

    @Test
    void collateralAssetWithSomeAssetNameEncodesToTheExactPinnedBytes() {
        assertEquals(COLLATERAL_HEX, hex(RequestTxEncoder.collateralAsset(RequestFixtures.COLLATERAL)));
    }

    /**
     * The collection variant — {@code None} asset name:
     * <pre>
     * d879 9f
     *   581c &lt;28&gt;                       policyId
     *   d87a 80                         tag 122 = Option constr 1 = None
     *   d879 9f 581c &lt;28&gt; 4a &lt;10&gt; ff     oracleTokenAsset
     * ff
     * </pre>
     */
    @Test
    void collateralAssetWithNoAssetNameEncodesToTheExactPinnedBytes() {
        String expected = "d8799f"
                + "581c" + TFLDT_POLICY
                + "d87a80"
                + "d8799f581c" + C3_POLICY + "4a" + C3_NAME + "ff"
                + "ff";

        CollateralAsset collection = new CollateralAsset(TFLDT_POLICY, Optional.empty(),
                RequestFixtures.TFLDT_C3_ORACLE);

        assertEquals(expected, hex(RequestTxEncoder.collateralAsset(collection)));
    }

    // ---- RequestMintRedeemer -------------------------------------------------------------------------

    /**
     * {@code RequestMintRedeemer { configRefInputIndex: 0, inputRef: ee×32 #0 }}:
     * <pre>
     * d879 9f                                    RequestMintRedeemer constr 0, 2 fields
     *   00                                          uint 0 = configRefInputIndex
     *   d879 9f                                     OutputReference constr 0
     *     5820 &lt;ee x32&gt;                                bytestring(32), FLAT — not a nested constr
     *     00                                           uint 0 = output_index
     *   ff
     * ff
     * </pre>
     */
    @Test
    void requestMintRedeemerEncodesToTheExactPinnedBytes() {
        String expected = "d8799f00d8799f5820" + "ee".repeat(32) + "00ffff";

        assertEquals(expected, hex(RequestTxEncoder.requestMintRedeemer(0, RequestFixtures.seed())));
    }

    /** A two-byte {@code configRefInputIndex} and a non-zero output index, so neither is a lucky zero. */
    @Test
    void requestMintRedeemerEncodesLargerIndexesToTheExactPinnedBytes() {
        // 300 = 0x012c -> uint 2-byte form 0x19; output index 7 -> immediate 0x07
        String expected = "d8799f19012cd8799f5820" + "ab".repeat(32) + "07ffff";

        assertEquals(expected, hex(RequestTxEncoder.requestMintRedeemer(300,
                new TransactionInput("ab".repeat(32), 7))));
    }

    // ---- the 29-byte asset name ------------------------------------------------------------------

    /**
     * <b>Consequence A, pinned.</b> For the seed {@code ee}×32 {@code #0}:
     * <pre>
     * serialise_data(OutputReference)  = d8799f5820eeee…ee00ff
     * blake2b_224                      = 0b2065981fcdef5983ae50c83e759d75d912bc35468d62be3b1aa534   (28 bytes)
     * request NFT asset name           = 000b2065981fcdef5983ae50c83e759d75d912bc35468d62be3b1aa534 (29 bytes)
     * </pre>
     * These were computed independently of this repo's code — python
     * {@code hashlib.blake2b(.., digest_size=28)} over hand-assembled CBOR — so a match here is a
     * real agreement between two implementations, not a tautology.
     * <p>
     * The 29 bytes are a 1-byte <em>index</em> prefix plus the hash. The loan NFT, borrower bond and
     * lender bond of TX B are 28 bytes and hash a <em>different</em> output reference; see
     * {@link RequestTxEncoder}'s class javadoc.
     */
    @Test
    void theRequestAssetNameIsTheIndexPrefixFollowedByTheHashedOutputReference() {
        String pinnedPreImage = "d8799f5820" + "ee".repeat(32) + "00ff";
        String pinnedHash = "0b2065981fcdef5983ae50c83e759d75d912bc35468d62be3b1aa534";
        String pinnedName = "00" + pinnedHash;

        assertEquals(pinnedPreImage, hex(RequestTxEncoder.outputReference(RequestFixtures.seed())));
        assertEquals(pinnedHash, RequestFixtures.hashOutputRef(RequestFixtures.seed()));

        String name = RequestFixtures.requestAssetName(RequestFixtures.seed());
        assertEquals(pinnedName, name);
        assertEquals(29, name.length() / 2, "the request NFT asset name is 29 bytes, not 28");
        assertEquals("00", name.substring(0, 2), "the index prefix of the single minted token is 0x00");
    }

    // ---- cross-checks against chain-arbitrated encoders ---------------------------------------------

    /**
     * {@link LiquidationTxEncoder} is a separate hand-written implementation whose
     * {@code LiquidationMode} output is run through the deployed validators by
     * {@code LiquidateDryEvalTest}. Agreement here therefore inherits that arbitration — and with a
     * negative {@code partialLiquidationPenaltyPerMille} it exercises the CBOR negative-integer path
     * in both encoders.
     */
    @Test
    void liquidationModeAgreesWithTheChainArbitratedEncoder() {
        LiquidationMode mode = RequestFixtures.LIQUIDATION_MODE;
        assertEquals(hex(LiquidationTxEncoder.liquidationMode(mode)),
                hex(RequestTxEncoder.liquidationMode(mode)));

        assertEquals(hex(LiquidationTxEncoder.liquidationMode(LoanFixtures.liquidation())),
                hex(RequestTxEncoder.liquidationMode(LoanFixtures.liquidation())));
        assertEquals(hex(LiquidationTxEncoder.liquidationMode(
                        new LiquidationMode.NoLiquidationFullCollateralClaim())),
                hex(RequestTxEncoder.liquidationMode(
                        new LiquidationMode.NoLiquidationFullCollateralClaim())));
        assertEquals(hex(LiquidationTxEncoder.liquidationMode(
                        new LiquidationMode.NoLiquidationDutchAuctionClaim())),
                hex(RequestTxEncoder.liquidationMode(
                        new LiquidationMode.NoLiquidationDutchAuctionClaim())));
    }

    /** All four {@code AuthorizationMethod} constructors, against the same arbitrated encoder. */
    @Test
    void authorizationMethodAgreesWithTheChainArbitratedEncoderForAllFourConstructors() {
        String hash = BORROWER_PAYMENT_KEY;
        for (AuthorizationMethod auth : java.util.List.of(
                new AuthorizationMethod.CardanoSignature(hash),
                new AuthorizationMethod.CardanoSpendScript(hash),
                new AuthorizationMethod.CardanoWithdrawScript(hash),
                new AuthorizationMethod.CardanoMintScript(hash))) {
            assertEquals(hex(LiquidationTxEncoder.authorizationMethod(auth)),
                    hex(RequestTxEncoder.authorizationMethod(auth)),
                    "disagreement on " + auth.getClass().getSimpleName());
        }
    }

    /**
     * {@code LoanFixtures}' {@code LoanDatum} encoder is pinned by re-encoding datums recorded off
     * preview ({@code src/test/resources/loans-v4/preview-loan-datums.hex}), so its
     * {@code CollateralAsset}, {@code RepaymentMode} and {@code Asset} bytes are chain-arbitrated.
     * Each of this encoder's three must appear as a <b>contiguous substring</b> of a
     * {@code LoanDatum} built from the same sub-values.
     */
    @Test
    void collateralRepaymentModeAndAssetAppearVerbatimInAChainArbitratedLoanDatum() {
        var loanDatum = LoanFixtures.loanDatum(AssetType.ada(),
                BigInteger.valueOf(110_000_000), BigInteger.ZERO, RequestFixtures.COLLATERAL,
                1_700_000_000_000L, RequestFixtures.LIQUIDATION_MODE, RequestFixtures.REPAYMENT_MODE,
                false);
        String arbitrated = LoanFixtures.hex(loanDatum);

        String collateral = hex(RequestTxEncoder.collateralAsset(RequestFixtures.COLLATERAL));
        assertTrue(arbitrated.contains(collateral),
                "collateral " + collateral + " not found in " + arbitrated);

        String repaymentMode = hex(RequestTxEncoder.repaymentMode(RequestFixtures.REPAYMENT_MODE));
        assertTrue(arbitrated.contains(repaymentMode),
                "repaymentMode " + repaymentMode + " not found in " + arbitrated);

        String ada = hex(RequestTxEncoder.asset(AssetType.ada()));
        assertTrue(arbitrated.contains(ada), "ada Asset " + ada + " not found in " + arbitrated);

        String oracleToken = hex(RequestTxEncoder.asset(RequestFixtures.TFLDT_C3_ORACLE));
        assertTrue(arbitrated.contains(oracleToken),
                "oracle Asset " + oracleToken + " not found in " + arbitrated);
    }
}
