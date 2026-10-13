package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.Liveness;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.PublishedOutput;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider half of the reference-script check: Blockfrost {@code /txs/{hash}/utxos}, read for
 * {@code reference_script_hash} and — the reason it is this endpoint and not {@code getTxOutput} —
 * {@code consumed_by_tx}, the only content on a creating transaction's output that moves once the
 * output is spent (CCL trap 12).
 *
 * <p>Served by a loopback {@link HttpServer} on an ephemeral port: no external network and no new
 * dependency. ⛔ The project id is a secret, so every case also asserts that a sentinel id appears in
 * no Result text and no exception message.
 */
class BlockfrostPublishedOutputLookupTest {

    private static final String SENTINEL = "previewSENTINELprojectid0000000000";

    /**
     * FAB-108 F1: a key that cannot be an HTTP header value — {@code SENTINEL + "a\nb" + TAIL}. Every
     * part of it is searched for: the head, the tail, and the span across the line break, so a text
     * echoing only one side of the break is still caught.
     */
    private static final String TAIL = "tailSENTINELafterthebreak0000";
    private static final String BROKEN_KEY = SENTINEL + "a\nb" + TAIL;
    private static final List<String> KEY_FRAGMENTS = List.of(SENTINEL, TAIL, "a\nb", SENTINEL + "a", "b" + TAIL);
    private static final String TX = "ab".repeat(32);
    private static final String SCRIPT = "cd".repeat(28);
    private static final String OTHER_SCRIPT = "ef".repeat(28);
    private static final String SPENDER = "5b".repeat(32);

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> projectId = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    /** Every text the lookup produced in this test, to be searched for the sentinel at the end. */
    private final List<String> produced = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            projectId.set(exchange.getRequestHeaders().getFirst("project_id"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        for (String text : produced) {
            for (String fragment : KEY_FRAGMENTS) {
                assertFalse(text != null && text.contains(fragment), "the project id leaked into: " + text);
            }
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0";
    }

    private Result<PublishedOutput> lookup(String baseUrl, int index) throws Exception {
        return lookup(baseUrl, SENTINEL, index);
    }

    private Result<PublishedOutput> lookup(String baseUrl, String key, int index) throws Exception {
        try {
            Result<PublishedOutput> result = new BlockfrostPublishedOutputLookup(baseUrl, key).lookup(TX, index);
            produced.add(result.getResponse());
            produced.add(String.valueOf(result.getValue()));
            return result;
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                produced.add(t.getMessage());
                produced.add(String.valueOf(t));
            }
            throw e;
        }
    }

    private void serve(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private static String output(int index, String consumedBy, boolean collateral, String scriptHash) {
        String consumed = consumedBy == null ? "" : ", \"consumed_by_tx\": " + consumedBy;
        return "{\"address\": \"addr1x\", \"amount\": [{\"unit\": \"lovelace\", \"quantity\": \"20000000\"}], "
                + "\"output_index\": " + index + ", \"collateral\": " + collateral
                + ", \"reference_script_hash\": " + (scriptHash == null ? "null" : "\"" + scriptHash + "\"")
                + consumed + "}";
    }

    private static String utxos(String... outputs) {
        return "{\"hash\": \"" + TX + "\", \"inputs\": [], \"outputs\": [" + String.join(", ", outputs) + "]}";
    }

    // ======================================================================================

    @Test
    void theRequestNamesTheCreatingTransactionAndCarriesTheProjectIdHeader() throws Exception {
        serve(200, utxos(output(0, "null", false, SCRIPT)));
        for (String baseUrl : new String[]{base(), base() + "/"}) {
            path.set(null);
            projectId.set(null);

            lookup(baseUrl, 0);

            assertEquals("/api/v0/txs/" + TX + "/utxos", path.get(), "base url " + baseUrl);
            assertEquals(SENTINEL, projectId.get(), "the project id travels as the project_id header");
        }
    }

    @Test
    void consumedByTxReadsAsUnspentSpentOrUnknown() throws Exception {
        serve(200, utxos(output(0, "null", false, SCRIPT)));
        Result<PublishedOutput> unspent = lookup(base(), 0);
        assertTrue(unspent.isSuccessful());
        assertEquals(new PublishedOutput(SCRIPT, Liveness.UNSPENT, null), unspent.getValue());

        serve(200, utxos(output(0, "\"" + SPENDER + "\"", false, SCRIPT)));
        assertEquals(new PublishedOutput(SCRIPT, Liveness.SPENT, SPENDER), lookup(base(), 0).getValue());

        serve(200, utxos(output(0, null, false, SCRIPT)));
        assertEquals(new PublishedOutput(SCRIPT, Liveness.UNKNOWN, null), lookup(base(), 0).getValue(),
                "a provider that does not report consumed_by_tx cannot say spent or unspent");

        serve(200, utxos(output(0, "null", false, null)));
        assertEquals(new PublishedOutput(null, Liveness.UNSPENT, null), lookup(base(), 0).getValue(),
                "no reference script reads as a null hash");
    }

    @Test
    void anIndexTheTransactionDoesNotHaveIsASuccessWithNoValue() throws Exception {
        serve(200, utxos(output(0, "null", false, SCRIPT), output(1, "null", false, OTHER_SCRIPT)));

        Result<PublishedOutput> absent = lookup(base(), 7);

        assertTrue(absent.isSuccessful());
        assertEquals(200, absent.code());
        assertNull(absent.getValue());
        assertEquals(OTHER_SCRIPT, lookup(base(), 1).getValue().referenceScriptHash(), "must still find index 1");
    }

    /** A collateral-return output shares the {@code output_index} space; it is never the coordinate. */
    @Test
    void aCollateralOutputAtTheSameIndexIsIgnored() throws Exception {
        serve(200, utxos(output(0, "null", true, OTHER_SCRIPT), output(0, "null", false, SCRIPT)));
        assertEquals(SCRIPT, lookup(base(), 0).getValue().referenceScriptHash());

        serve(200, utxos(output(0, "null", true, OTHER_SCRIPT)));
        assertNull(lookup(base(), 0).getValue(), "a collateral output alone is not the coordinate");
    }

    @Test
    void aNonSuccessStatusIsAnErrorResultCarryingItsCode() throws Exception {
        for (int code : new int[]{404, 402, 500}) {
            serve(code, "{\"status_code\": " + code + ", \"error\": \"Some error\", \"message\": \"nope\"}");

            Result<PublishedOutput> result = lookup(base(), 0);

            assertFalse(result.isSuccessful(), "HTTP " + code);
            assertEquals(code, result.code());
            assertTrue(result.getResponse().contains("HTTP " + code), result.getResponse());
            assertTrue(result.getResponse().contains("txs/" + TX + "/utxos"), result.getResponse());
        }
    }

    @Test
    void anUnparseableSuccessBodyThrows() {
        serve(200, "<html>not json</html>");
        assertThrows(IllegalStateException.class, () -> lookup(base(), 0));
    }

    // ======================================================================================
    // FAB-108 F1 — a key that cannot be a header value
    // ======================================================================================

    /**
     * ⛔ A key with a trailing line break — a k8s {@code --from-file} secret, a CRLF env file — is the
     * honest, common case. It is STRIPPED and then sent: the server receives the bare key, which proves
     * the strip happened and is not merely a refusal.
     */
    @Test
    void aKeyWithATrailingLineBreakIsStrippedAndStillTravels() throws Exception {
        serve(200, utxos(output(0, "null", false, SCRIPT)));
        for (String key : new String[]{SENTINEL + "\n", SENTINEL + "\r\n", " " + SENTINEL + "\t"}) {
            path.set(null);
            projectId.set(null);

            Result<PublishedOutput> result = lookup(base(), key, 0);

            String shown = key.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
            assertEquals(SCRIPT, result.getValue().referenceScriptHash(), "key " + shown);
            assertEquals(SENTINEL, projectId.get(), "the stripped key travels as the header: key " + shown);
        }
    }

    /**
     * ⛔ JDK 21's {@code HttpRequest.Builder.header} refuses a value with CR or LF by throwing
     * {@code IllegalArgumentException("invalid header value: \"<value>\"")} — the KEY, in a message the
     * verifier logs up to nine times and FAB-108-3 renders on a page. A line break INSIDE the key cannot be
     * stripped, so the key is never sent and no text names it. The "must still": the same lookup with a
     * clean key reaches the server.
     */
    @Test
    void aKeyWithAnEmbeddedLineBreakIsNeverSentAndNeverEchoed() throws Exception {
        serve(200, utxos(output(0, "null", false, SCRIPT)));
        path.set(null);

        Exception thrown = null;
        try {
            lookup(base(), BROKEN_KEY, 0);
        } catch (Exception e) {
            thrown = e;
        }

        assertNotNull(thrown, "a key that cannot be a header value must not produce an answer");
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            for (String fragment : KEY_FRAGMENTS) {
                assertFalse(String.valueOf(t.getMessage()).contains(fragment), "the key leaked into: " + t);
            }
        }
        assertEquals(IllegalStateException.class, thrown.getClass(), String.valueOf(thrown));
        assertEquals("blockfrost.key contains characters not allowed in an HTTP header (value withheld)",
                thrown.getMessage());
        assertNull(path.get(), "the request must never be sent");

        // must still: one variable apart, a clean key is sent.
        lookup(base(), SENTINEL, 0);
        assertEquals(SENTINEL, projectId.get());
    }

    // ======================================================================================
    // FAB-108 F4 — a response-shape change is not an answer (VH-73)
    // ======================================================================================

    /**
     * A matching output with NO {@code reference_script_hash} field is a provider whose shape changed, not
     * one saying "no reference script" — read as an answer, it would DROP a good coordinate. It throws, so
     * the verifier classifies it UNVERIFIED and keeps the slot. The "must still": a JSON {@code null} hash
     * is a real answer.
     */
    @Test
    void aMatchingOutputWithoutTheReferenceScriptHashFieldIsAShapeChangeNotAnAnswer() throws Exception {
        serve(200, utxos("{\"output_index\": 0, \"collateral\": false, \"consumed_by_tx\": null}"));
        IllegalStateException absent = assertThrows(IllegalStateException.class, () -> lookup(base(), 0));
        assertTrue(absent.getMessage().startsWith("unexpected Blockfrost response shape: "), absent.getMessage());

        serve(200, utxos(output(0, "null", false, null)));
        assertEquals(new PublishedOutput(null, Liveness.UNSPENT, null), lookup(base(), 0).getValue(),
                "must still: a JSON null reference_script_hash is a real answer");
    }

    /** Outputs that carry no {@code output_index} cannot say which one is the coordinate. */
    @Test
    void outputsWithoutAnOutputIndexAreAShapeChangeNotAnAnswer() throws Exception {
        serve(200, utxos("{\"collateral\": false, \"reference_script_hash\": \"" + SCRIPT
                + "\", \"consumed_by_tx\": null}"));
        IllegalStateException absent = assertThrows(IllegalStateException.class, () -> lookup(base(), 0));
        assertTrue(absent.getMessage().startsWith("unexpected Blockfrost response shape: "), absent.getMessage());

        serve(200, utxos(output(0, "null", false, SCRIPT)));
        assertEquals(SCRIPT, lookup(base(), 0).getValue().referenceScriptHash(),
                "must still: the same output with its index is found");
    }

    @Test
    void anUnreachableProviderThrowsWithoutTheProjectId() {
        String dead = base();
        server.stop(0);
        server = null;
        assertThrows(IOException.class, () -> lookup(dead, 0));
    }
}
