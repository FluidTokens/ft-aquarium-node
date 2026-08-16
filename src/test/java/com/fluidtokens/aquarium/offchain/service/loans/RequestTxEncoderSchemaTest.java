package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Checks {@link RequestTxEncoder} against the contract's own schema, exactly the way
 * {@link LiquidationTxEncoderSchemaTest} pins {@link LiquidationTxEncoder} — same fixture, same
 * helpers.
 * <p>
 * {@code RequestDatum}, {@code CommonData} and {@code RequestMintRedeemer} are absent from the
 * deployed blueprint for the usual reason (the validators involved are typed {@code Data}), so
 * {@code aiken build --include-all-types} output — {@code /loans-v4/loans-v4-alltypes.plutus.json} —
 * is again the only source of truth for constructor indices and field order. See
 * {@code LoanDatumSchemaTest} for how that fixture is regenerated; this test reads the very same
 * file rather than a copy.
 *
 * <h2>What this test does and does not cover</h2>
 * The risk in play is that {@link RequestTxEncoder} writes every field positionally, so a
 * transposition would encode a plausible-but-wrong datum rather than throwing. That risk has two
 * halves and this test covers only one of them:
 * <ul>
 *   <li><b>Covered here:</b> the <em>contract's</em> field order — the constructor indices and field
 *       titles in the schema oracle — and the fact that the record types this repo hands the encoder
 *       declare their components in that same order. Nothing below reads a single byte the encoder
 *       produces.</li>
 *   <li><b>Not covered here:</b> the encoder's actual <em>write</em> order. Nothing stops
 *       {@link RequestTxEncoder#requestDatum} or {@link RequestTxEncoder#commonData} from reading a
 *       correctly ordered record and writing its accessors into the wrong slots. That guarantee lives
 *       in {@code RequestTxEncoderTest}'s <b>field-index sentinel</b> goldens, a fixture whose every
 *       field is byte-distinguishable from every other so that any transposition changes the
 *       serialised bytes.</li>
 * </ul>
 */
class RequestTxEncoderSchemaTest {

    private static JsonNode definitions() {
        try (InputStream is = RequestTxEncoderSchemaTest.class
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
    void requestDatumFieldOrderMatchesTheContract() {
        var requestDatum = constructor("fluidtokens/types/request/RequestDatum", 0);
        var expected = List.of("permissionedConditionScriptHash", "extraData", "commonData",
                "borrowerAuth", "borrowerAddress", "collateral", "minPrincipal", "minPrincipalDivider",
                "maxPrincipal", "dynamicCollateralPrice", "requestExpiration", "requestExpirationPenalty");
        assertEquals(expected, fieldTitles(requestDatum));
        assertEquals(expected.size(),
                RequestTxEncoder.RequestDatum.class.getRecordComponents().length);
    }

    @Test
    void commonDataFieldOrderMatchesTheContract() {
        var commonData = constructor("fluidtokens/types/pool/CommonData", 0);
        var expected = List.of("principalAsset", "principalOracleAsset", "interestRate",
                "installmentPeriod", "totalInstallments", "initialGracePeriod", "liquidationMode",
                "repaymentMode", "repaymentTimeWindow", "penaltyFeeForLateRepayment",
                "repaymentReceipts", "borrowerBondDestinationScriptHash");
        assertEquals(expected, fieldTitles(commonData));
        assertEquals(expected.size(), RequestTxEncoder.CommonData.class.getRecordComponents().length);
    }

    /**
     * The trap {@link RequestTxEncoder.CommonData} exists to avoid: {@code CommonData} and
     * {@code LoanDatum} share most of their field <em>names</em> and agree on almost none of their
     * <em>positions</em>. {@code totalInstallments} sits at index 4 of {@code CommonData} and at
     * index 5 of {@code LoanDatum}; {@code interestRate} at 2 and 4. A copy-paste of
     * {@code LoanFixtures.encode(LoanDatum)}'s field order into {@link RequestTxEncoder#commonData}
     * would transpose them silently — every field would still be a well-typed Int and nothing would
     * throw. So the difference is asserted deliberately rather than merely avoided.
     * <p>
     * Note the limit, per this class's javadoc: what follows pins the <em>schema's</em> indices and
     * the <em>record's</em> component order. That the encoder writes {@code totalInstallments} into
     * slot 4 is pinned in {@code RequestTxEncoderTest} by the field-index sentinel — and only there,
     * because in the production fixture {@code totalInstallments} is one of six {@code CommonData}
     * fields that encode as {@code 00}.
     */
    @Test
    void commonDataIsNotLoanDatumReordered() {
        var commonData = fieldTitles(constructor("fluidtokens/types/pool/CommonData", 0));
        var loanDatum = fieldTitles(constructor("fluidtokens/types/loan/LoanDatum", 0));

        assertEquals(4, commonData.indexOf("totalInstallments"),
                "CommonData.totalInstallments must sit at index 4");
        assertEquals(5, loanDatum.indexOf("totalInstallments"),
                "LoanDatum.totalInstallments must sit at index 5");
        assertNotEquals(commonData.indexOf("totalInstallments"), loanDatum.indexOf("totalInstallments"),
                "if these ever coincide, this test is no longer guarding anything");

        assertEquals(2, commonData.indexOf("interestRate"));
        assertEquals(4, loanDatum.indexOf("interestRate"));

        // And the record this repo hands the encoder follows CommonData, not LoanDatum.
        assertEquals("totalInstallments",
                RequestTxEncoder.CommonData.class.getRecordComponents()[4].getName());
        assertEquals("interestRate",
                RequestTxEncoder.CommonData.class.getRecordComponents()[2].getName());
        assertEquals("totalInstallments", LoanDatum.class.getRecordComponents()[5].getName());
    }

    @Test
    void requestMintRedeemerFieldOrderMatchesTheContract() {
        var mint = constructor("fluidtokens/types/request/RequestMintRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "inputRef"), fieldTitles(mint));
    }

    @Test
    void addressFieldOrderAndCredentialIndicesMatchTheContract() {
        var address = constructor("cardano/address/Address", 0);
        assertEquals(List.of("payment_credential", "stake_credential"), fieldTitles(address));

        assertEquals("VerificationKey",
                constructor("cardano/address/PaymentCredential", 0).get("title").asText());
        assertEquals("Script",
                constructor("cardano/address/PaymentCredential", 1).get("title").asText());

        assertEquals("Inline",
                constructor("cardano/address/StakeCredential", 0).get("title").asText());
        assertEquals("Pointer",
                constructor("cardano/address/StakeCredential", 1).get("title").asText());

        assertEquals("Some",
                constructor("Option<cardano/address/StakeCredential>", 0).get("title").asText());
        assertEquals("None",
                constructor("Option<cardano/address/StakeCredential>", 1).get("title").asText());
    }

    @Test
    void collateralAssetFieldOrderAndOptionIndicesMatchTheContract() {
        var collateral = constructor("fluidtokens/types/general/CollateralAsset", 0);
        assertEquals(List.of("policyId", "maybeAssetName", "oracleTokenAsset"), fieldTitles(collateral));
        assertEquals(3, CollateralAsset.class.getRecordComponents().length);

        assertEquals("Some", constructor("Option<cardano/assets/AssetName>", 0).get("title").asText());
        assertEquals("None", constructor("Option<cardano/assets/AssetName>", 1).get("title").asText());
    }

    @Test
    void outputReferenceFieldOrderMatchesTheContractAndTransactionIdIsFlat() {
        var outputReference = constructor("cardano/transaction/OutputReference", 0);
        assertEquals(List.of("transaction_id", "output_index"), fieldTitles(outputReference));

        // The load-bearing pin for "flat, PlutusV3, no nested constr": transaction_id must resolve
        // straight to ByteArray, not to a wrapped-hash type with a constructor of its own.
        assertEquals("#/definitions/ByteArray",
                outputReference.get("fields").get(0).get("$ref").asText());
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
                "equityInPrincipalCurrency"), fieldTitles(liquidation));
        assertEquals(4, LiquidationMode.Liquidation.class.getRecordComponents().length);
    }

    @Test
    void repaymentModeConstructorIndicesMatchTheContract() {
        var interestOnly = constructor("fluidtokens/types/general/RepaymentMode", 0);
        assertEquals("InterestOnRemainingPrincipal", interestOnly.get("title").asText());
        assertEquals(List.of("max_possible_recasts"), fieldTitles(interestOnly));

        var installments = constructor("fluidtokens/types/general/RepaymentMode", 1);
        assertEquals("PrincipalAndInterestOnInstallments", installments.get("title").asText());
        assertEquals(List.of(), fieldTitles(installments));

        var perpetual = constructor("fluidtokens/types/general/RepaymentMode", 2);
        assertEquals("PerpetualLoan", perpetual.get("title").asText());
        assertEquals(List.of("apyIncreaseLinearCoefficient", "max_possible_recasts"),
                fieldTitles(perpetual));
        assertEquals(2, RepaymentMode.PerpetualLoan.class.getRecordComponents().length);
    }

    @Test
    void authorizationMethodConstructorIndicesMatchTheContract() {
        assertEquals("CardanoSignature",
                constructor("fluidtokens/types/general/AuthorizationMethod", 0).get("title").asText());
        assertEquals("CardanoSpendScript",
                constructor("fluidtokens/types/general/AuthorizationMethod", 1).get("title").asText());
        assertEquals("CardanoWithdrawScript",
                constructor("fluidtokens/types/general/AuthorizationMethod", 2).get("title").asText());
        assertEquals("CardanoMintScript",
                constructor("fluidtokens/types/general/AuthorizationMethod", 3).get("title").asText());
        assertEquals(1, AuthorizationMethod.CardanoSignature.class.getRecordComponents().length);
    }

    @Test
    void assetFieldOrderMatchesTheContract() {
        assertEquals(List.of("policyId", "assetName"),
                fieldTitles(constructor("fluidtokens/types/general/Asset", 0)));
    }

    /** Aiken Bool is Constr 0 = False / Constr 1 = True; the encoder hardcodes that. */
    @Test
    void boolConstructorIndicesMatchTheContract() {
        assertEquals("False", constructor("Bool", 0).get("title").asText());
        assertEquals("True", constructor("Bool", 1).get("title").asText());
    }
}
