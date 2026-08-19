package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.loans.LoanDatumConverter;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link LoanDatumConverter} against real preview loan datums.
 * <p>
 * {@code LoanDatum} is hand-decoded — it is absent from the blueprint, so there is no
 * generated converter to fall back on and no compiler check that the field order matches
 * {@code lib/fluidtokens/types/loan.ak}. A transposed field would still decode, just into a
 * silently wrong loan, so the fixture below is the only thing standing between us and that.
 * <p>
 * {@code preview-loan-datums.hex} is every inline datum indexed at the preview loan credential
 * {@code b8569d71…} on 2026-08-06. Refresh with:
 * <pre>
 * psql -h localhost -U fluidtokens -d aquarium -tAc \
 *   "select inline_datum from address_utxo
 *     where owner_payment_credential='b8569d71e5a918f79ba2b6899f53c534631f73db92207582a15c414a'
 *       and inline_datum is not null order by tx_hash;" \
 *   > src/test/resources/loans-v4/preview-loan-datums.hex
 * </pre>
 */
class LoanDatumConverterTest {

    private final LoanDatumConverter converter = new LoanDatumConverter();

    private static List<String> liveDatums() {
        try (var is = LoanDatumConverterTest.class.getResourceAsStream("/loans-v4/preview-loan-datums.hex")) {
            return new String(Objects.requireNonNull(is).readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One datum decoded field by field. Values were read straight off the CBOR by hand, so this
     * is an independent check of the field order rather than a snapshot of whatever the code
     * happened to produce.
     */
    @Test
    void decodesAKnownPreviewLoan() {
        var hex = "d8799f001a02625a001b0000019fa7d7c6a0001901cb00d8799f4040ffd8799f4040ff0000"
                + "d87b9f1864187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00c2d59645bd0272"
                + "656f5f5bc29562a2fadc97c45acc622e91b4c6a2d6d8799f581c0b77d150c275bd0a600633e4be"
                + "7d09f83c4b9f00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92d"
                + "accbb269611a9eae7a40f97";
        // the fixture line is the authoritative full datum; the literal above is only its prefix
        var full = liveDatums().stream().filter(line -> line.startsWith(hex.substring(0, 60))).findFirst().orElseThrow();

        LoanDatum d = converter.deserialize(full);

        assertEquals(BigInteger.ZERO, d.doneRecasts());
        assertEquals(BigInteger.valueOf(40_000_000), d.principalAmount(), "40 ada principal");
        assertEquals(BigInteger.ZERO, d.repaidInstallments());
        assertEquals(BigInteger.valueOf(459), d.interestRate(), "4.59%");
        assertEquals(BigInteger.ZERO, d.totalInstallments());
        assertTrue(d.principalAsset().isAda(), "principal is ada");
        assertFalse(d.repaymentReceipts());

        var liquidation = assertInstanceOf(LiquidationMode.Liquidation.class, d.liquidationMode());
        assertEquals(BigInteger.valueOf(100), liquidation.ltv());
        assertEquals(BigInteger.valueOf(125), liquidation.ltvDivider(), "80% LTV threshold");
        assertEquals(BigInteger.valueOf(100), liquidation.partialLiquidationPenaltyPerMille());
        assertFalse(liquidation.equityInPrincipalCurrency(), "equity in collateral currency");
        assertTrue(d.liquidationMode().allowsBotLiquidation());

        var repayment = assertInstanceOf(RepaymentMode.PerpetualLoan.class, d.repaymentMode());
        assertEquals(BigInteger.valueOf(28), repayment.apyIncreaseLinearCoefficient());
        assertEquals(BigInteger.valueOf(5), repayment.maxPossibleRecasts());

        // collateral is FLDT, with an oracle token attached
        assertEquals("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", d.collateral().policyId());
        assertEquals("0014df1074464c4454", d.collateral().assetName().orElseThrow());
        assertTrue(d.collateral().usesOracle());

        // originId is a pool id, and pool ids are prefixed "POOL"
        assertTrue(d.originId().startsWith("504f4f4c"), "originId should start with POOL: " + d.originId());
    }

    /**
     * Every live datum must decode. This is the check that actually catches a contract change:
     * the field-count guard and the constructor-index guards all throw rather than mis-decode.
     */
    @Test
    void decodesEveryLivePreviewLoan() {
        var datums = liveDatums();
        assertFalse(datums.isEmpty(), "fixture is empty");

        var liquidationModes = new TreeMap<String, Integer>();
        var repaymentModes = new TreeMap<String, Integer>();
        for (String hex : datums) {
            LoanDatum d = converter.deserialize(hex);
            assertEquals(LoanDatum.FIELD_COUNT, LoanDatum.class.getRecordComponents().length);
            liquidationModes.merge(d.liquidationMode().getClass().getSimpleName(), 1, Integer::sum);
            repaymentModes.merge(d.repaymentMode().getClass().getSimpleName(), 1, Integer::sum);
        }

        System.out.printf("decoded %d live preview loan datums%n  liquidation: %s%n  repayment:   %s%n",
                datums.size(), liquidationModes, repaymentModes);
    }

    /**
     * Bot eligibility needs all four validator filters, not just the mode. A loan can be in
     * {@code Liquidation} mode with equity in collateral currency and still be untouchable
     * because its collateral is an NFT collection or a single NFT — see §7 D1-D3.
     */
    @Test
    void botEligibilityAppliesTheCollateralFiltersToo() {
        var eligible = converter.deserialize(liveDatums().getFirst());
        assertTrue(eligible.liquidationMode().allowsBotLiquidation(), "fixture should be Liquidation mode");

        // a normal fungible collateral position clears every filter
        assertTrue(loanWith(eligible, eligible.collateral().assetName(), BigInteger.valueOf(110_000_000))
                .botLiquidatable());

        // D3: NFT-collection collateral has no asset name -> lm_liquidate_action.ak:108 rejects
        assertFalse(loanWith(eligible, Optional.empty(), BigInteger.valueOf(110_000_000))
                .botLiquidatable(), "collection collateral must not be reported liquidatable");

        // D3: a single NFT -> lm_liquidate_action.ak:116 requires quantity > 1
        assertFalse(loanWith(eligible, eligible.collateral().assetName(), BigInteger.ONE)
                .botLiquidatable(), "single-NFT collateral must not be reported liquidatable");
    }

    // ---- the sentinel datum -----------------------------------------------------------------
    //
    // WHY THIS EXISTS ALONGSIDE preview-loan-datums.hex, AND WHY NEITHER IS REDUNDANT.
    //
    // The two fixtures answer different questions and a "cleanup" that deletes either one
    // silently removes a defence the other never provided:
    //
    //   * preview-loan-datums.hex answers "does the decoder cope with what the chain really
    //     writes" — real constructor indices, real chunking, real value ranges. Only a chain
    //     fixture can answer that, and it is what catches an on-chain type change.
    //   * THIS sentinel answers "can any fixture tell field i from field j AT ALL". It cannot
    //     answer the first question — it was assembled by hand, not recorded off chain.
    //
    // The second question is not academic. LoanDatum is decoded positionally (f.get(0)..f.get(16)
    // in LoanDatumConverter) and encoded positionally by LoanFixtures.encode, and a field read
    // from the wrong position still decodes — into a plausible-looking, silently wrong loan.
    // Measured over all 56 lines of preview-loan-datums.hex (T-017): SIX Int fields —
    // repaidInstallments (f3), totalInstallments (f5), installmentPeriod (f8),
    // initialGracePeriod (f9), repaymentTimeWindow (f12) and penaltyFeeForLateRepayment (f13) —
    // encode to the byte 0x00 in EVERY recorded datum. That is 15 pairs of positions the whole
    // chain fixture cannot distinguish; the wider suite's synthetic fixtures rescue f5 and f8,
    // leaving 6 pairs no test in the repo could tell apart. Five mutants were built to prove it
    // rather than assume it — swap f3/f9, swap f12/f13, swap f3/f12, make f13 read f12
    // (duplicated neighbour), make f9 read f3 (omitted field) — and ALL FIVE passed the full
    // 489-test suite. Uniform fixtures do not merely hide transpositions; they equally hide an
    // omitted field and a duplicated neighbour.
    //
    // The sentinel below gives all 17 fields pairwise-distinct encoded bytes, mixing CBOR widths
    // (1/2/3/5/9-byte integers) and including a negative — loan.ak documents
    // penaltyFeeForLateRepayment as "<= 0 means no penalty", so -1 is a legitimate on-chain
    // value, not an invented one. It kills all five mutants. When they are re-applied,
    // decodesAKnownPreviewLoan and decodesEveryLivePreviewLoan stay GREEN and only the two
    // sentinel tests redden — which is the whole point: the production golden is not a
    // discrimination test and never was.
    //
    // sentinelFieldsAreAllPairwiseDistinct() guards the sentinel against itself. If a later edit
    // makes two of its fields equal again, the sentinel quietly stops discriminating and nothing
    // else in the repo would notice. That test makes it notice.

    /** FLDT on preview — a real 28-byte policy id, so the sentinel is not all-synthetic. */
    private static final String SENTINEL_COLLATERAL_POLICY =
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    /** The real CIP-68 FLDT asset name (9 bytes). */
    private static final String SENTINEL_COLLATERAL_NAME = "0014df1074464c4454";
    /** Deliberately unlike every other 28-byte field, so a swap with one is visible at a glance. */
    private static final String SENTINEL_ORACLE_POLICY = "11".repeat(28);
    /** 4 bytes, so the collateral oracle asset differs from f7's in length as well as value. */
    private static final String SENTINEL_ORACLE_NAME = "4f524301";

    /**
     * A hand-assembled {@code LoanDatum} whose 17 fields all encode to distinct bytes. Derived by
     * writing out the CBOR each field must produce (constructor tags {@code 121 + alternative},
     * indefinite-length lists for non-empty field lists, {@code 0x80} for empty ones,
     * minimal-length integer and bytestring headers), NOT by running an encoder and copying its
     * output — so it pins the field order independently of the code under test.
     * <pre>
     * d879 9f                                       LoanDatum constr 0, indefinite list, 17 fields
     *   01                                            f0  doneRecasts                = 1
     *   1a02625a00                                    f1  principalAmount            = 40_000_000
     *   1b0000019feadcc3bf                            f2  lendDate                   = 1_786_351_764_415
     *   02                                            f3  repaidInstallments         = 2
     *   1901cb                                        f4  interestRate               = 459
     *   19012c                                        f5  totalInstallments          = 300
     *   d8799f 4040 ff                                f6  principalAsset             = ada (empty policy + name)
     *   d8799f 444e4f4e45 444e4f4e45 ff               f7  principalOracleAsset       = Asset("NONE","NONE")
     *   1818                                          f8  installmentPeriod          = 24
     *   1819                                          f9  initialGracePeriod         = 25
     *   d87b9f 1864 187d 1865 d87980 ff               f10 liquidationMode            = Liquidation(100,125,101,False)
     *   d87b9f 181c 05 ff                             f11 repaymentMode              = PerpetualLoan(28,5)
     *   1a000186a0                                    f12 repaymentTimeWindow        = 100_000
     *   20                                            f13 penaltyFeeForLateRepayment = -1  (negative int, 1-byte form)
     *   d87a80                                        f14 repaymentReceipts          = True (Bool constr 1)
     *   48 504f4f4c00c2d596                           f15 originId                   = 8 bytes
     *   d8799f 581c&lt;28&gt; d8799f49&lt;9&gt;ff d8799f581c&lt;28&gt;44&lt;4&gt;ff ff
     *                                                 f16 collateral                 = CollateralAsset(FLDT, Some(name), Asset(11*28, "ORC"))
     * ff
     * </pre>
     */
    private static final String SENTINEL_DATUM_HEX =
            "d8799f"
                    + "01"
                    + "1a02625a00"
                    + "1b0000019feadcc3bf"
                    + "02"
                    + "1901cb"
                    + "19012c"
                    + "d8799f4040ff"
                    + "d8799f444e4f4e45444e4f4e45ff"
                    + "1818"
                    + "1819"
                    + "d87b9f1864187d1865d87980ff"
                    + "d87b9f181c05ff"
                    + "1a000186a0"
                    + "20"
                    + "d87a80"
                    + "48504f4f4c00c2d596"
                    + "d8799f581c" + SENTINEL_COLLATERAL_POLICY
                    + "d8799f49" + SENTINEL_COLLATERAL_NAME + "ff"
                    + "d8799f581c" + SENTINEL_ORACLE_POLICY + "44" + SENTINEL_ORACLE_NAME + "ff"
                    + "ff"
                    + "ff";

    /**
     * Every one of the 17 positions decodes to its own distinct value — the check
     * {@code preview-loan-datums.hex} structurally cannot perform for f3/f5/f8/f9/f12/f13,
     * because it encodes all six as {@code 0x00}.
     */
    @Test
    void theSentinelDatumPinsAllSeventeenFieldPositions() {
        LoanDatum d = converter.deserialize(SENTINEL_DATUM_HEX);

        assertEquals(BigInteger.ONE, d.doneRecasts(), "f0 doneRecasts");
        assertEquals(BigInteger.valueOf(40_000_000), d.principalAmount(), "f1 principalAmount");
        assertEquals(BigInteger.valueOf(1_786_351_764_415L), d.lendDate(), "f2 lendDate");
        assertEquals(BigInteger.TWO, d.repaidInstallments(), "f3 repaidInstallments");
        assertEquals(BigInteger.valueOf(459), d.interestRate(), "f4 interestRate");
        assertEquals(BigInteger.valueOf(300), d.totalInstallments(), "f5 totalInstallments");
        assertTrue(d.principalAsset().isAda(), "f6 principalAsset is ada");
        assertEquals("4e4f4e45", d.principalOracleAsset().policyId(), "f7 principalOracleAsset policy");
        assertEquals("4e4f4e45", d.principalOracleAsset().assetName(), "f7 principalOracleAsset name");
        assertEquals(BigInteger.valueOf(24), d.installmentPeriod(), "f8 installmentPeriod");
        assertEquals(BigInteger.valueOf(25), d.initialGracePeriod(), "f9 initialGracePeriod");
        assertEquals(BigInteger.valueOf(100_000), d.repaymentTimeWindow(), "f12 repaymentTimeWindow");
        assertEquals(BigInteger.valueOf(-1), d.penaltyFeeForLateRepayment(),
                "f13 penaltyFeeForLateRepayment — loan.ak: <= 0 means no penalty");
        assertTrue(d.repaymentReceipts(), "f14 repaymentReceipts");
        assertEquals("504f4f4c00c2d596", d.originId(), "f15 originId");

        var liquidation = assertInstanceOf(LiquidationMode.Liquidation.class, d.liquidationMode(),
                "f10 liquidationMode");
        assertEquals(BigInteger.valueOf(100), liquidation.ltv());
        assertEquals(BigInteger.valueOf(125), liquidation.ltvDivider());
        assertEquals(BigInteger.valueOf(101), liquidation.partialLiquidationPenaltyPerMille());
        assertFalse(liquidation.equityInPrincipalCurrency());

        var repayment = assertInstanceOf(RepaymentMode.PerpetualLoan.class, d.repaymentMode(),
                "f11 repaymentMode");
        assertEquals(BigInteger.valueOf(28), repayment.apyIncreaseLinearCoefficient());
        assertEquals(BigInteger.valueOf(5), repayment.maxPossibleRecasts());

        assertEquals(SENTINEL_COLLATERAL_POLICY, d.collateral().policyId(), "f16 collateral policy");
        assertEquals(SENTINEL_COLLATERAL_NAME, d.collateral().assetName().orElseThrow(),
                "f16 collateral name");
        assertEquals(SENTINEL_ORACLE_POLICY, d.collateral().oracleTokenAsset().policyId(),
                "f16 collateral oracle policy");
        assertEquals(SENTINEL_ORACLE_NAME, d.collateral().oracleTokenAsset().assetName(),
                "f16 collateral oracle name");
    }

    /**
     * The sentinel round-trips through {@link LoanFixtures#encode(LoanDatum)} byte for byte. This
     * is the same discipline as
     * {@code LiquidateTransactionBuilderTest#theFixtureEncoderReproducesARecordedPreviewLoanDatumByteForByte},
     * but against a datum whose fields are pairwise distinct: that test's fixture cannot detect a
     * permutation of the six positions that are all {@code 0x00} on chain, and this one can.
     */
    @Test
    void theFixtureEncoderReproducesTheSentinelDatumByteForByte() {
        LoanDatum decoded = converter.deserialize(SENTINEL_DATUM_HEX);

        assertEquals(SENTINEL_DATUM_HEX, LoanFixtures.hex(decoded));
    }

    /**
     * The sentinel guards itself: all 17 fields must stay pairwise distinct in their encoded
     * bytes. A sentinel whose fields drift back into uniformity stops discriminating without any
     * other test noticing — that is exactly how the production fixture ended up unable to tell
     * six positions apart.
     */
    @Test
    void sentinelFieldsAreAllPairwiseDistinct() throws Exception {
        var constr = ConstrPlutusData.deserialize(
                CborSerializationUtil.deserialize(HexUtil.decodeHexString(SENTINEL_DATUM_HEX)));
        var encoded = new ArrayList<String>();
        for (var field : constr.getData().getPlutusDataList()) {
            encoded.add(HexUtil.encodeHexString(field.serializeToBytes()));
        }

        assertEquals(LoanDatum.FIELD_COUNT, encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
            for (int j = i + 1; j < encoded.size(); j++) {
                assertNotEquals(encoded.get(i), encoded.get(j),
                        "f%d and f%d encode identically — the sentinel can no longer tell them apart"
                                .formatted(i, j));
            }
        }
    }

    private static Loan loanWith(LoanDatum base, Optional<String> collateralAssetName, BigInteger collateralAmount) {
        var collateral = new CollateralAsset(base.collateral().policyId(), collateralAssetName,
                base.collateral().oracleTokenAsset());
        var datum = new LoanDatum(base.doneRecasts(), base.principalAmount(), base.lendDate(),
                base.repaidInstallments(), base.interestRate(), base.totalInstallments(), base.principalAsset(),
                base.principalOracleAsset(), base.installmentPeriod(), base.initialGracePeriod(),
                base.liquidationMode(), base.repaymentMode(), base.repaymentTimeWindow(),
                base.penaltyFeeForLateRepayment(), base.repaymentReceipts(), base.originId(), collateral);
        return new Loan("tx", 0, "addr", "loan", collateralAmount, BigInteger.valueOf(3_000_000), datum);
    }
}
