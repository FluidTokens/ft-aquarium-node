package com.fluidtokens.aquarium.offchain;

import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.loans.LoanDatumConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
