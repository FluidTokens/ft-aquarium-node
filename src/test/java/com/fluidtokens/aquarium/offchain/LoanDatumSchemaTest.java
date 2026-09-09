package com.fluidtokens.aquarium.offchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the hand-written loan model against the contract's own schema.
 * <p>
 * {@code LoanDatum} and friends are decoded by hand ({@code LoanDatumConverter}) because they are
 * absent from the deployed blueprint: loan UTxOs sit at a {@code general_spend} address whose
 * handler takes {@code datumOpt: Option<Data>}, so Aiken emits no schema for them.
 * <p>
 * They <em>can</em> be made to appear, though — {@code aiken build --include-all-types} emits every
 * serialisable type rather than only those in validator signatures (102 → 139 definitions). That
 * output is useless for hashes (it is built with a different aiken version, so
 * {@code compiledCode} differs for 68 of 74 validators) and cardano-client-lib 0.7.2 cannot
 * generate from it, but it is an excellent <b>specification oracle</b>: it states the field order
 * and constructor indices that {@code LoanDatumConverter} has to match, and a mismatch there
 * decodes silently into a wrong loan.
 * <p>
 * Regenerate the fixture from a checkout of {@code FluidTokens/ft-cardano-loans-v4}:
 * <pre>
 * aiken build -I -o loans-v4-alltypes.plutus.json
 * cp loans-v4-alltypes.plutus.json &lt;here&gt;/src/test/resources/loans-v4/
 * </pre>
 * If this test fails, the on-chain types changed and the decoder must be updated to match.
 */
class LoanDatumSchemaTest {

    private static JsonNode definitions() {
        try (InputStream is = LoanDatumSchemaTest.class
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

    /**
     * The whole risk of a hand-written decoder: {@code LoanDatumConverter} indexes fields
     * positionally, so a reordering upstream would decode into a plausible but wrong loan
     * rather than throwing.
     */
    @Test
    void loanDatumFieldOrderMatchesTheContract() {
        var expected = List.of(
                "doneRecasts", "principalAmount", "lendDate", "repaidInstallments", "interestRate",
                "totalInstallments", "principalAsset", "principalOracleAsset", "installmentPeriod",
                "initialGracePeriod", "liquidationMode", "repaymentMode", "repaymentTimeWindow",
                "penaltyFeeForLateRepayment", "repaymentReceipts", "originId", "collateral");

        assertEquals(expected, fieldTitles(constructor("fluidtokens/types/loan/LoanDatum", 0)),
                "LoanDatum field order changed — LoanDatumConverter reads these positionally");
        assertEquals(expected.size(), LoanDatum.FIELD_COUNT);
        assertEquals(expected.size(), LoanDatum.class.getRecordComponents().length);
    }

    @Test
    void liquidationModeConstructorIndicesMatchTheContract() {
        assertEquals("NoLiquidationFullCollateralClaim",
                constructor("fluidtokens/types/general/LiquidationMode", 0).get("title").asText());
        assertEquals("NoLiquidationDutchAuctionClaim",
                constructor("fluidtokens/types/general/LiquidationMode", 1).get("title").asText());

        var liquidation = constructor("fluidtokens/types/general/LiquidationMode", 2);
        assertEquals("Liquidation", liquidation.get("title").asText());
        assertEquals(List.of("lTV", "lTVDivider", "partialLiquidationPenaltyPerMille",
                        "equityInPrincipalCurrency"),
                fieldTitles(liquidation));
        assertEquals(4, LiquidationMode.Liquidation.class.getRecordComponents().length);
    }

    @Test
    void repaymentModeConstructorIndicesMatchTheContract() {
        assertEquals("InterestOnRemainingPrincipal",
                constructor("fluidtokens/types/general/RepaymentMode", 0).get("title").asText());
        assertEquals("PrincipalAndInterestOnInstallments",
                constructor("fluidtokens/types/general/RepaymentMode", 1).get("title").asText());

        var perpetual = constructor("fluidtokens/types/general/RepaymentMode", 2);
        assertEquals("PerpetualLoan", perpetual.get("title").asText());
        assertEquals(List.of("apyIncreaseLinearCoefficient", "max_possible_recasts"),
                fieldTitles(perpetual), "PerpetualLoan field order — the decoder reads these positionally");
        assertEquals(2, RepaymentMode.PerpetualLoan.class.getRecordComponents().length);
    }

    @Test
    void collateralAndAssetFieldOrderMatchTheContract() {
        assertEquals(List.of("policyId", "maybeAssetName", "oracleTokenAsset"),
                fieldTitles(constructor("fluidtokens/types/general/CollateralAsset", 0)));
        assertEquals(List.of("policyId", "assetName"),
                fieldTitles(constructor("fluidtokens/types/general/Asset", 0)));
    }

    /** Aiken Bool is Constr 0 = False / Constr 1 = True; the decoder hardcodes that. */
    @Test
    void boolConstructorIndicesMatchTheContract() {
        assertEquals("False", constructor("Bool", 0).get("title").asText());
        assertEquals("True", constructor("Bool", 1).get("title").asText());
    }
}
