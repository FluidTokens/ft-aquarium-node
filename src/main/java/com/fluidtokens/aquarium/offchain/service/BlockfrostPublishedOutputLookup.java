package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.Liveness;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.PublishedOutput;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * One reference-script coordinate as Blockfrost reports it: {@code GET /txs/{hash}/utxos}, the
 * creating transaction's outputs, read for {@code reference_script_hash} and {@code consumed_by_tx}.
 *
 * <p>⛔ <b>This endpoint and not {@code getTxOutput}, because only this one can see a spend</b> (CCL
 * trap 12). The creating transaction reports its output — value, assets, reference-script hash — forever,
 * spent or not; {@code consumed_by_tx} is the one field on it that moves when the output is consumed.
 * The same read {@code MainnetReferenceScriptsTest.assertUnspent} makes, without paging the address.
 *
 * <p>⛔ <b>The project id is a secret.</b> It travels only as the {@code project_id} header and appears
 * in no Result text, no exception message and no log line produced here.
 */
public class BlockfrostPublishedOutputLookup implements LoansReferenceScriptVerifier.PublishedOutputLookup {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int BODY_EXCERPT = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String projectId;
    private final HttpClient http;

    public BlockfrostPublishedOutputLookup(String baseUrl, String projectId) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.projectId = projectId;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * The output at {@code txHash#outputIndex}: a success with a {@code null} value when the creating
     * transaction has no such (non-collateral) output, an error Result carrying the HTTP status for any
     * other answer, and a throw when the provider could not be asked at all.
     */
    @Override
    public Result<PublishedOutput> lookup(String txHash, int outputIndex) throws IOException, InterruptedException {
        String path = "txs/" + txHash + "/utxos";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (projectId != null && !projectId.isEmpty()) {
            request.header("project_id", projectId);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status != 200) {
            return Result.<PublishedOutput>error("HTTP %d for %s: %s".formatted(status, path, excerpt(body)))
                    .code(status);
        }

        JsonNode outputs;
        try {
            outputs = JSON.readTree(body).get("outputs");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unparseable 200 body for %s: %s".formatted(path, excerpt(body)));
        }
        if (outputs == null || !outputs.isArray()) {
            throw new IllegalStateException("unparseable 200 body for %s (no outputs array): %s"
                    .formatted(path, excerpt(body)));
        }

        for (JsonNode output : outputs) {
            JsonNode index = output.get("output_index");
            if (index == null || !index.canConvertToInt() || index.asInt() != outputIndex) {
                continue;
            }
            // A collateral-return output shares the output_index space; it is never a coordinate.
            JsonNode collateral = output.get("collateral");
            if (collateral != null && collateral.isBoolean() && collateral.asBoolean()) {
                continue;
            }
            String referenceScriptHash = output.hasNonNull("reference_script_hash")
                    ? output.get("reference_script_hash").asText() : null;
            JsonNode consumedBy = output.get("consumed_by_tx");
            Liveness liveness;
            String spender = null;
            if (consumedBy == null) {
                liveness = Liveness.UNKNOWN;
            } else if (consumedBy.isNull()) {
                liveness = Liveness.UNSPENT;
            } else if (consumedBy.isTextual()) {
                liveness = Liveness.SPENT;
                spender = consumedBy.asText();
            } else {
                liveness = Liveness.UNKNOWN;
            }
            return Result.<PublishedOutput>success("ok")
                    .withValue(new PublishedOutput(referenceScriptHash, liveness, spender))
                    .code(200);
        }
        return Result.<PublishedOutput>success("ok").withValue(null).code(200);
    }

    private static String excerpt(String body) {
        return body.length() <= BODY_EXCERPT ? body : body.substring(0, BODY_EXCERPT) + "…";
    }
}
