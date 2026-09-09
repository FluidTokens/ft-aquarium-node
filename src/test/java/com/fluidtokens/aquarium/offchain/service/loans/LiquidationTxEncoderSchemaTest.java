package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks {@link LiquidationTxEncoder} against the contract's own schema, the same way
 * {@code LoanDatumSchemaTest} and {@code OracleFeedSchemaTest} pin their converters.
 * <p>
 * Every redeemer and datum {@code LiquidationTxEncoder} builds is absent from the deployed
 * blueprint for the same reason {@code LoanDatum} and {@code OraclePriceFeed} are — the
 * withdraw/mint/spend validators involved are typed {@code Data} — so
 * {@code aiken build --include-all-types} is again the only source of truth for constructor
 * indices and field order. See {@code LoanDatumSchemaTest} for how to regenerate
 * {@code loans-v4-alltypes.plutus.json}; this test reads the exact same fixture rather than a
 * copy of it.
 * <p>
 * <b>One test here deliberately does not read that fixture:
 * {@link #lmLiquidateWithdrawRedeemerFieldOrderMatchesTheContract()} reads the deployed blueprint
 * {@code src/main/resources/loans-v4.plutus.json} instead.</b> The reason is that
 * {@code loans-v4-alltypes.plutus.json} predates the deployed commit {@code ff005fb} and still
 * describes the <em>previous</em> preview deployment: there,
 * {@code LMLiquidateWithdrawRedeemer} had three fields, and at {@code ff005fb} it has four —
 * {@code assetOutputIndexes} was added. Pinning the encoder against the stale fixture would pin it
 * to a redeemer shape the deployed validator destructures as four fields and dies on. The deployed
 * blueprint carries the four-field definition because {@code lender_manager.ak}'s own validators
 * expose the type, so it is a schema oracle for this one redeemer without needing a regenerated
 * all-types build. {@link #theAllTypesOracleIsStillThePreFf005fbOne()} is the tripwire that keeps
 * this paragraph honest: it asserts the fixture is still the three-field one, so regenerating the
 * fixture turns it red and forces whoever does it to read this.
 */
class LiquidationTxEncoderSchemaTest {

    private static JsonNode definitions() {
        return definitions("/loans-v4/loans-v4-alltypes.plutus.json");
    }

    private static JsonNode definitions(String resource) {
        try (InputStream is = LiquidationTxEncoderSchemaTest.class.getResourceAsStream(resource)) {
            return new ObjectMapper().readTree(Objects.requireNonNull(is)).get("definitions");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode constructor(String definition, int index) {
        return constructor(definitions(), definition, index);
    }

    private static JsonNode constructor(JsonNode definitions, String definition, int index) {
        for (JsonNode c : definitions.get(definition).get("anyOf")) {
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
     * The whole risk of {@code LiquidationTxEncoder#loanClaimActionWithdrawRedeemer}: it writes
     * fields positionally, so a reordering upstream would encode a plausible but wrong redeemer
     * rather than throwing.
     */
    @Test
    void loanClaimActionWithdrawRedeemerFieldOrderMatchesTheContract() {
        var claimAction = constructor("fluidtokens/types/loan/LoanClaimActionWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "actionsForEachInput"), fieldTitles(claimAction));
    }

    @Test
    void claimDataFieldOrderMatchesTheContract() {
        var claimData = constructor("fluidtokens/types/loan/ClaimData", 0);
        var expected = List.of("liquidationMode", "lenderBondOutputIndex", "collateralOracleRefInputIndex",
                "principalOracleRefInputIndex", "lenderAuth", "equity", "loanId", "remainingDebt");
        assertEquals(expected, fieldTitles(claimData));
        assertEquals(expected.size(), ClaimData.FIELD_COUNT);
        assertEquals(expected.size(), ClaimData.class.getRecordComponents().length);
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

    /**
     * Read off the <b>deployed</b> blueprint, not the all-types fixture — see this class's javadoc
     * for why. The fourth field is what a redeemer built at the three-field shape is missing, and
     * {@code safe_list_at} bottoms out in {@code builtin.head_list}, so the validator does not
     * report a shape error: it walks off the end of the field list and dies with
     * {@code Machine(EmptyList(..))} before doing any work.
     */
    @Test
    void lmLiquidateWithdrawRedeemerFieldOrderMatchesTheContract() {
        var lm = constructor(definitions("/loans-v4.plutus.json"),
                "fluidtokens/types/lender_manager/LMLiquidateWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "lenderBondInputIndexes", "lenderBondAssetNames",
                        "assetOutputIndexes"),
                fieldTitles(lm));
    }

    /**
     * A staleness tripwire, not a claim about the contract: it asserts that
     * {@code loans-v4-alltypes.plutus.json} still describes the <em>pre-{@code ff005fb}</em>
     * deployment, where {@code LMLiquidateWithdrawRedeemer} had three fields.
     * <p>
     * It is here to go red. Regenerating the fixture makes it fail, and the failure is the prompt to
     * read this class's javadoc and repoint
     * {@link #lmLiquidateWithdrawRedeemerFieldOrderMatchesTheContract()} back at the fixture — and to
     * re-examine every other test in this file that still reads it, all of which are pinned against a
     * schema one deployment behind.
     */
    @Test
    void theAllTypesOracleIsStillThePreFf005fbOne() {
        var lm = constructor("fluidtokens/types/lender_manager/LMLiquidateWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "lenderBondInputIndexes", "lenderBondAssetNames"),
                fieldTitles(lm),
                "the all-types fixture has been regenerated — read this class's javadoc");
    }

    @Test
    void loanWithdrawRedeemerFieldOrderAndClaimActionIndexMatchTheContract() {
        var loanWithdraw = constructor("fluidtokens/types/loan/LoanWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "action"), fieldTitles(loanWithdraw));

        var claim = constructor("fluidtokens/types/loan/Action", 0);
        assertEquals("Claim", claim.get("title").asText());
        assertEquals(List.of(), fieldTitles(claim));
    }

    @Test
    void lenderManagerWithdrawRedeemerFieldOrderAndLiquidateActionIndexMatchTheContract() {
        var lmWithdraw = constructor("fluidtokens/types/lender_manager/LenderManagerWithdrawRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "action"), fieldTitles(lmWithdraw));

        assertEquals("WithdrawBonds",
                constructor("fluidtokens/types/lender_manager/LenderManagerAction", 0).get("title").asText());
        var liquidate = constructor("fluidtokens/types/lender_manager/LenderManagerAction", 1);
        assertEquals("Liquidate", liquidate.get("title").asText());
        assertEquals(List.of(), fieldTitles(liquidate));
    }

    @Test
    void loanMintRedeemerFieldOrderMatchesTheContract() {
        var mint = constructor("fluidtokens/types/loan/LoanMintRedeemer", 0);
        assertEquals(List.of("configRefInputIndex", "isPoolOrigin", "originWithdrawRedeemerIndex"),
                fieldTitles(mint));
    }

    @Test
    void oracleRedeemerFieldOrderMatchesTheContract() {
        var oracleRedeemer = constructor("fluidtokens/types/oracle/OracleRedeemer", 0);
        assertEquals(List.of("data", "signatures"), fieldTitles(oracleRedeemer));
    }

    /**
     * The whole risk of {@code LiquidationTxEncoder#signature}: the on-chain field order is
     * signature-then-key_position, the opposite of {@code OracleSignature}'s own record order, so
     * a copy-paste of the record's field order into the encoder would silently transpose them.
     */
    @Test
    void signatureFieldOrderMatchesTheContract() {
        var signature = constructor("fluidtokens/types/oracle/Signature", 0);
        assertEquals(List.of("signature", "key_position"), fieldTitles(signature));
    }

    @Test
    void assetManagerDatumWithTokenIsConstructorZeroOfAssetManagerDatum() {
        var withToken = constructor("fluidtokens/types/asset_manager/AssetManagerDatum", 0);
        assertEquals("AssetManagerDatumWithToken", withToken.get("title").asText());
        assertEquals(List.of("inputOutputReference", "action", "data", "ownerAsset"), fieldTitles(withToken));

        // Constructor index 1 exists too (AssetManagerDatumWithHash) — out of scope for this
        // ticket, checked here only to confirm index 0 is really "WithToken" and not a fluke.
        var withHash = constructor("fluidtokens/types/asset_manager/AssetManagerDatum", 1);
        assertEquals("AssetManagerDatumWithHash", withHash.get("title").asText());
    }

    @Test
    void outputReferenceFieldOrderMatchesTheContractAndTransactionIdIsFlat() {
        var outputReference = constructor("cardano/transaction/OutputReference", 0);
        assertEquals(List.of("transaction_id", "output_index"), fieldTitles(outputReference));

        // The load-bearing check for the "flat, PlutusV3, no nested constr" pin: transaction_id
        // must resolve straight to ByteArray, not to a wrapped-hash type with its own constructor.
        var txIdField = outputReference.get("fields").get(0);
        assertEquals("#/definitions/ByteArray", txIdField.get("$ref").asText());
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

    @Test
    void assetManagerDatumWithTokenModelFieldCountMatchesTheContract() {
        assertEquals(4, AssetManagerDatumWithToken.class.getRecordComponents().length);
    }
}
