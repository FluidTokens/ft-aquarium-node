package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;

/** The common outcome of one attempted unit of outward work. */
public enum WorkResult {
    DEFINITE_FAILURE,
    SUBMITTED,
    WOULD_SUBMIT,
    AMBIGUOUS;

    private static final int MAX_PROVIDER_RESPONSE_LENGTH = 131_072;
    private static final int MAX_PROVIDER_JSON_DEPTH = 16;
    private static final int MAX_PROVIDER_JSON_STRING_LENGTH = 65_536;
    private static final int MAX_PROVIDER_JSON_NUMBER_LENGTH = 20;
    private static final int MIN_PROVIDER_ROOT_MEMBERS = 3;
    private static final int MAX_PROVIDER_ROOT_MEMBERS = 32;
    private static final ObjectMapper BLOCKFROST_ERROR_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_PROVIDER_JSON_DEPTH)
                    .maxStringLength(MAX_PROVIDER_JSON_STRING_LENGTH)
                    .maxNameLength(MAX_PROVIDER_JSON_STRING_LENGTH)
                    .maxNumberLength(MAX_PROVIDER_JSON_NUMBER_LENGTH)
                    .build())
            .build());

    public boolean terminal() {
        return this != DEFINITE_FAILURE;
    }

    public static WorkResult classify(Result<?> result) {
        if (result != null && result.isSuccessful()) {
            return "WOULD_SUBMIT".equals(result.getValue())
                    || "WOULD_SUBMIT".equals(result.getResponse())
                    ? WOULD_SUBMIT : SUBMITTED;
        }
        return isDecodedBlockfrostProviderSubmitRefusal(result) ? DEFINITE_FAILURE : AMBIGUOUS;
    }

    /**
     * CCL 0.7.2 exposes a Blockfrost failure only as an HTTP code plus its raw response body.
     * Continue the candidate pass only for the complete, bounded HTTP-400 envelope documented by
     * Blockfrost for {@code /tx/submit}. The diagnostic {@code message} is deliberately opaque: the
     * provider publishes no Cardano/Haskell grammar for it, so its content cannot decide whether a
     * different candidate may be submitted.
     */
    private static boolean isDecodedBlockfrostProviderSubmitRefusal(Result<?> result) {
        if (result == null || result.isSuccessful() || result.code() != 400
                || result.getResponse() == null
                || result.getResponse().length() < 1
                || result.getResponse().length() > MAX_PROVIDER_RESPONSE_LENGTH) {
            return false;
        }
        String response = result.getResponse();
        int start = 0;
        int end = response.length();
        while (start < end && isJsonWhitespace(response.charAt(start))) {
            start++;
        }
        while (end > start && isJsonWhitespace(response.charAt(end - 1))) {
            end--;
        }
        if (start == end || response.charAt(start) == '\uFEFF') {
            return false;
        }

        try {
            JsonParser parser = BLOCKFROST_ERROR_MAPPER.getFactory()
                    .createParser(response.substring(start, end));
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try (parser) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return false;
                }
                int depth = 1;
                int rootMembers = 0;
                String rootField = null;
                boolean statusSeen = false;
                boolean statusValid = false;
                boolean errorSeen = false;
                boolean errorValid = false;
                boolean messageSeen = false;
                boolean messageValid = false;

                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (depth == 0) {
                        return false;
                    }
                    if (token == JsonToken.FIELD_NAME) {
                        if (parser.currentName().length() > MAX_PROVIDER_JSON_STRING_LENGTH) {
                            return false;
                        }
                        if (depth == 1) {
                            rootMembers++;
                            if (rootMembers > MAX_PROVIDER_ROOT_MEMBERS) {
                                return false;
                            }
                            rootField = parser.currentName();
                        }
                        continue;
                    }
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (depth == 1 && rootField != null) {
                            if ("status_code".equals(rootField)) {
                                statusSeen = true;
                            } else if ("error".equals(rootField)) {
                                errorSeen = true;
                            } else if ("message".equals(rootField)) {
                                messageSeen = true;
                            }
                            rootField = null;
                        }
                        depth++;
                        if (depth > MAX_PROVIDER_JSON_DEPTH) {
                            return false;
                        }
                        continue;
                    }
                    if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        depth--;
                        if (depth < 0) {
                            return false;
                        }
                        continue;
                    }
                    if (token == JsonToken.VALUE_STRING
                            && parser.getText().length() > MAX_PROVIDER_JSON_STRING_LENGTH) {
                        return false;
                    }
                    if ((token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
                            && parser.getText().length() > MAX_PROVIDER_JSON_NUMBER_LENGTH) {
                        return false;
                    }
                    if (depth == 1 && rootField != null) {
                        if ("status_code".equals(rootField)) {
                            statusSeen = true;
                            statusValid = token == JsonToken.VALUE_NUMBER_INT
                                    && BigInteger.valueOf(400).equals(parser.getBigIntegerValue())
                                    && result.code() == 400;
                        } else if ("error".equals(rootField)) {
                            errorSeen = true;
                            errorValid = token == JsonToken.VALUE_STRING
                                    && "Bad Request".equals(parser.getText());
                        } else if ("message".equals(rootField)) {
                            messageSeen = true;
                            messageValid = token == JsonToken.VALUE_STRING;
                        }
                        rootField = null;
                    }
                }
                return depth == 0
                        && rootMembers >= MIN_PROVIDER_ROOT_MEMBERS
                        && statusSeen && statusValid
                        && errorSeen && errorValid
                        && messageSeen && messageValid;
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
