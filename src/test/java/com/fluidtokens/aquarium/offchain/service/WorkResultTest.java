package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkResultTest {

    @Test
    void terminalityHasOneRecoverableOutcome() {
        assertFalse(WorkResult.DEFINITE_FAILURE.terminal());
        assertTrue(WorkResult.SUBMITTED.terminal());
        assertTrue(WorkResult.WOULD_SUBMIT.terminal());
        assertTrue(WorkResult.AMBIGUOUS.terminal());
    }

    @Test
    void successfulResultsDistinguishWouldSubmitInValueOrResponse() {
        assertClassifies(Result.<String>success("ok").withValue("deadbeef"), WorkResult.SUBMITTED,
                "an ordinary successful result is submitted");
        assertClassifies(Result.<String>success("ok").withValue("WOULD_SUBMIT"), WorkResult.WOULD_SUBMIT,
                "the exact sentinel in the value is a withheld submission");
        assertClassifies(Result.<String>success("WOULD_SUBMIT").withValue("deadbeef"),
                WorkResult.WOULD_SUBMIT, "the exact sentinel in the response is a withheld submission");
    }

    @Test
    void aProviderSubmitRefusalWithALegacyLookingMessageIsDefinite() {
        assertClassifies(blockfrostRejection(400,
                        "transaction submit error ShelleyTxValidationError ShelleyBasedEraConway "
                                + "(ApplyTxError [UtxowFailure (UtxoFailure (BadInputsUTxO "
                                + "(fromList [TxIn deadbeef 0])))])"), WorkResult.DEFINITE_FAILURE,
                "a complete decoded Blockfrost provider submit refusal is definite");
    }

    @Test
    void documentedBlockfrostBadRequestIsDefinite() {
        assertClassifies(Result.<String>error(
                        "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":"
                                + "\"Backend did not understand your request.\"}").code(400),
                WorkResult.DEFINITE_FAILURE, "the documented Blockfrost submit-refusal envelope");
    }

    @Test
    void opaqueMessageNeverDecidesProviderRefusal() {
        for (String message : List.of(
                "",
                "arbitrary prose",
                "transaction submit error arbitrary ShelleyTxValidationError text ApplyTxError",
                "transaction submit error ShelleyTxValidationError (ApplyTxError [BadInputsUTxO])",
                "{\"constructor\":\"ApplyTxError\",\"details\":[]}")) {
            assertClassifies(blockfrostRejection(400, message), WorkResult.DEFINITE_FAILURE,
                    "message is opaque: " + message);
        }
    }

    @Test
    void schemaPermittedExtraMemberIsDefinite() {
        assertClassifies(Result.<String>error(
                        "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                                + "\"provider_trace\":{\"region\":\"eu\"}}").code(400),
                WorkResult.DEFINITE_FAILURE, "the provider schema permits additional members");
    }

    @Test
    void resultAndEnvelopeStatusMustBothBe400() {
        List<Result<String>> responses = List.of(
                Result.<String>error(blockfrostError(400, "Bad Request", "opaque")).code(408),
                Result.<String>error(blockfrostError(408, "Bad Request", "opaque")).code(400),
                Result.<String>error("{\"status_code\":400.0,\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}").code(400),
                Result.<String>error("{\"status_code\":\"400\",\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}").code(400));
        for (Result<String> response : responses) {
            assertClassifies(response, WorkResult.AMBIGUOUS,
                    "both statuses must be the integral number 400");
        }
    }

    @Test
    void badRequestNameMustMatchRecognizedSubset() {
        for (String body : List.of(
                "{\"status_code\":400,\"message\":\"opaque\",\"extra\":0}",
                "{\"status_code\":400,\"error\":400,\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"bad request\",\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Conflict\",\"message\":\"opaque\"}")) {
            assertClassifies(Result.<String>error(body).code(400), WorkResult.AMBIGUOUS,
                    "error must be the exact recognized provider example");
        }
    }

    @Test
    void allRequiredFieldsAreTyped() {
        for (String body : List.of(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"extra\":0}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":400}",
                "{\"error\":\"Bad Request\",\"message\":\"opaque\",\"extra\":0}",
                "{\"status_code\":null,\"error\":\"Bad Request\",\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":null,\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":null}",
                "[400,\"Bad Request\",\"opaque\"]")) {
            assertClassifies(Result.<String>error(body).code(400), WorkResult.AMBIGUOUS,
                    "required fields and root must retain their documented JSON types");
        }
    }

    @Test
    void duplicateOrTrailingJsonIsAmbiguous() {
        for (String body : List.of(
                "{\"status_code\":400,\"status_code\":400,\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                        + "\"extra\":{\"x\":1,\"x\":2}}",
                blockfrostError(400, "Bad Request", "opaque") + "{}",
                blockfrostError(400, "Bad Request", "opaque") + " trailing",
                "\uFEFF" + blockfrostError(400, "Bad Request", "opaque"))) {
            assertClassifies(Result.<String>error(body).code(400), WorkResult.AMBIGUOUS,
                    "duplicates, a BOM, and unconsumed input are ambiguous");
        }
    }

    @Test
    void malformedOrUnframed400IsAmbiguous() {
        for (String body : List.of("{not-json", "Bad Request", "<html>Bad Request</html>", "", " \t\r\n")) {
            assertClassifies(Result.<String>error(body).code(400), WorkResult.AMBIGUOUS,
                    "only a complete provider JSON envelope is definite");
        }
    }

    @Test
    void decoderBoundsAreClosed() {
        List<String> exactBounds = List.of(
                exactLengthBlockfrostBody(131_072),
                blockfrostError(400, "Bad Request", "x".repeat(65_536)),
                escapedMessageBody(20_000),
                longFieldNameBody(65_536),
                nestedExtraBody(15),
                extraNumberBody("12345678901234567890"),
                memberCountBody(32));
        for (int i = 0; i < exactBounds.size(); i++) {
            assertClassifies(Result.<String>error(exactBounds.get(i)).code(400),
                    WorkResult.DEFINITE_FAILURE,
                    "exact body/string/escaped-string/field-name/depth/number/member control " + i);
        }

        List<String> oneOverBounds = List.of(
                exactLengthBlockfrostBody(131_073),
                blockfrostError(400, "Bad Request", "x".repeat(65_537)),
                longFieldNameBody(65_537),
                nestedExtraBody(16),
                extraNumberBody("123456789012345678901"),
                memberCountBody(33));
        for (int i = 0; i < oneOverBounds.size(); i++) {
            assertClassifies(Result.<String>error(oneOverBounds.get(i)).code(400), WorkResult.AMBIGUOUS,
                    "one-over body/string/field-name/depth/number/member control " + i);
        }

        assertClassifies(Result.<String>error(" \t\r\n"
                        + blockfrostError(400, "Bad Request", "opaque") + "\n\t ").code(400),
                WorkResult.DEFINITE_FAILURE,
                "JSON whitespace around the one complete document is allowed");
    }

    @Test
    void http408IsAmbiguous() {
        assertClassifies(Result.<String>error(blockfrostError(408, "Request Timeout",
                        "request timed out while waiting for the backend")).code(408),
                WorkResult.AMBIGUOUS, "HTTP 408 does not establish rejection");
    }

    @Test
    void undecodable4xxIsAmbiguous() {
        assertClassifies(Result.<String>error("upstream proxy refused the request").code(429),
                WorkResult.AMBIGUOUS, "an arbitrary 4xx body does not establish rejection");
    }

    @Test
    void nullOther4xxAnd5xxResponsesRemainAmbiguous() {
        assertClassifies(null, WorkResult.AMBIGUOUS, "a null result has unknown acceptance");
        assertClassifies(Result.<String>error(null).code(400), WorkResult.AMBIGUOUS,
                "an HTTP 400 without a response body has unknown acceptance");
        for (int status : List.of(418, 425, 429, 500, 503)) {
            assertClassifies(Result.<String>error(
                            blockfrostError(status, "Bad Request", "opaque")).code(status),
                    WorkResult.AMBIGUOUS,
                    "HTTP " + status + " is outside the recognized provider refusal boundary");
        }
    }

    @Test
    void raw400CannotMasqueradeAsProviderSubmitRefusal() {
        assertClassifies(Result.<String>error("BadInputsUTxO").code(400), WorkResult.AMBIGUOUS,
                "a status and raw ledger tag are not a decoded provider refusal");
    }

    @Test
    void malformedJsonCannotMasqueradeAsProviderSubmitRefusal() {
        assertClassifies(Result.<String>error("{not-json").code(400), WorkResult.AMBIGUOUS,
                "malformed JSON is not a decoded provider refusal");
    }

    @Test
    void statusMismatchedPayloadCannotMasqueradeAsProviderSubmitRefusal() {
        assertClassifies(Result.<String>error(blockfrostError(409, "Conflict",
                        "transaction submit error ShelleyTxValidationError ShelleyBasedEraConway "
                                + "(ApplyTxError [BadInputsUTxO])")).code(400),
                WorkResult.AMBIGUOUS, "HTTP status and payload status must agree");
    }

    private static void assertClassifies(Result<?> result, WorkResult expected, String message) {
        assertEquals(expected, WorkResult.classify(result), message);
    }

    private static Result<String> blockfrostRejection(int statusCode, String message) {
        return Result.<String>error(blockfrostError(statusCode, "Bad Request", message)).code(statusCode);
    }

    private static String blockfrostError(int statusCode, String error, String message) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "status_code", statusCode,
                    "error", error,
                    "message", message));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("test-only Blockfrost response fixture must serialize", e);
        }
    }

    private static String exactLengthBlockfrostBody(int targetLength) {
        String prefix = "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"\""
                + ",\"a\":\"";
        String middle = "\",\"b\":\"";
        String suffix = "\"}";
        int firstLength = 65_536;
        int secondLength = targetLength - prefix.length() - firstLength - middle.length() - suffix.length();
        if (secondLength < 0 || secondLength > 65_536) {
            throw new IllegalArgumentException("unsupported exact body length " + targetLength);
        }
        String body = prefix + "x".repeat(firstLength) + middle + "y".repeat(secondLength) + suffix;
        assertEquals(targetLength, body.length(), "test fixture must isolate the body-length bound");
        return body;
    }

    private static String nestedExtraBody(int nestedArrays) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                + "\"nested\":" + "[".repeat(nestedArrays) + "0" + "]".repeat(nestedArrays) + "}";
    }

    private static String escapedMessageBody(int decodedLength) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\""
                + "\\u0061".repeat(decodedLength) + "\"}";
    }

    private static String longFieldNameBody(int fieldNameLength) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\",\""
                + "f".repeat(fieldNameLength) + "\":null}";
    }

    private static String extraNumberBody(String numberToken) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                + "\"number\":" + numberToken + "}";
    }

    private static String memberCountBody(int memberCount) {
        StringBuilder body = new StringBuilder(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\"");
        for (int i = 3; i < memberCount; i++) {
            body.append(",\"extra").append(i).append("\":null");
        }
        return body.append('}').toString();
    }
}
