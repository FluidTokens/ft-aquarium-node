package com.fluidtokens.aquarium.offchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the hand-written lender-bond model against the contract's own schema.
 * <p>
 * {@code LenderManagerDatum} is decoded by hand ({@code LenderManagerDatumConverter}) for the
 * same reason as {@code LoanDatum} (see {@code LoanDatumSchemaTest}): loan-family UTxOs sit at
 * {@code general_spend}-style addresses whose handler takes {@code datumOpt: Option<Data>}, so
 * Aiken emits no schema for the inline datum types.
 * <p>
 * {@code loans-v4-alltypes.plutus.json} is the specification oracle — see
 * {@code LoanDatumSchemaTest} for how to regenerate it. If this test fails, the on-chain types
 * changed and the decoder must be updated to match.
 */
class LenderManagerDatumSchemaTest {

    private static JsonNode definitions() {
        try (InputStream is = LenderManagerDatumSchemaTest.class
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

    private static List<String> fieldRefs(JsonNode constructor) {
        var refs = new ArrayList<String>();
        constructor.get("fields").forEach(f -> refs.add(f.path("$ref").asText(null)));
        return refs;
    }

    /**
     * The whole risk of a hand-written decoder: {@code LenderManagerDatumConverter} indexes
     * fields positionally, so a reordering upstream would decode into a plausible but wrong
     * bond rather than throwing.
     */
    @Test
    void lenderManagerDatumFieldOrderMatchesTheContract() {
        var expected = List.of(
                "lenderAuth", "lenderStakeCredential", "shouldLiquidationConvertToPrincipal",
                "liquidationFeePerMille", "poolId", "principalAsset");

        assertEquals(expected,
                fieldTitles(constructor("fluidtokens/types/lender_manager/LenderManagerDatum", 0)),
                "LenderManagerDatum field order changed — LenderManagerDatumConverter reads these positionally");
        assertEquals(expected.size(), LenderManagerDatum.FIELD_COUNT);
        assertEquals(expected.size(), LenderManagerDatum.class.getRecordComponents().length);
    }

    /**
     * Field <em>titles</em> catch a rename; they do not catch two fields of the same on-chain
     * type swapping places (e.g. two {@code ByteArray} fields transposed) or the schema type
     * changing under an unchanged title. Pinning the {@code $ref} per field, and the record
     * component names in declaration order, catches both.
     */
    @Test
    void lenderManagerDatumFieldTypesMatchTheContract() {
        var expectedRefs = List.of(
                "#/definitions/fluidtokens~1types~1general~1AuthorizationMethod",
                "#/definitions/Option<cardano~1address~1StakeCredential>",
                "#/definitions/Bool",
                "#/definitions/Int",
                "#/definitions/ByteArray",
                "#/definitions/fluidtokens~1types~1general~1Asset");

        assertEquals(expectedRefs, fieldRefs(constructor("fluidtokens/types/lender_manager/LenderManagerDatum", 0)));
    }

    @Test
    void lenderManagerDatumRecordComponentNamesMatchTheContractOrder() {
        var expectedNames = List.of(
                "lenderAuth", "lenderStakeCredential", "shouldLiquidationConvertToPrincipal",
                "liquidationFeePerMille", "poolId", "principalAsset");

        var actualNames = Arrays.stream(LenderManagerDatum.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(expectedNames, actualNames);
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

        for (int i = 0; i < 4; i++) {
            assertEquals(List.of("hash"),
                    fieldTitles(constructor("fluidtokens/types/general/AuthorizationMethod", i)));
        }
        assertEquals(1, AuthorizationMethod.CardanoSignature.class.getRecordComponents().length);
        assertEquals(1, AuthorizationMethod.CardanoSpendScript.class.getRecordComponents().length);
        assertEquals(1, AuthorizationMethod.CardanoWithdrawScript.class.getRecordComponents().length);
        assertEquals(1, AuthorizationMethod.CardanoMintScript.class.getRecordComponents().length);
    }

    @Test
    void assetFieldOrderMatchesTheContract() {
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
