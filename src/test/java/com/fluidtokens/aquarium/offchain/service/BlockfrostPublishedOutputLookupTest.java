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
            assertFalse(text != null && text.contains(SENTINEL), "the project id leaked into: " + text);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0";
    }

    private Result<PublishedOutput> lookup(String baseUrl, int index) throws Exception {
        try {
            Result<PublishedOutput> result = new BlockfrostPublishedOutputLookup(baseUrl, SENTINEL).lookup(TX, index);
            produced.add(result.getResponse());
            produced.add(String.valueOf(result.getValue()));
            return result;
        } catch (Exception e) {
            produced.add(e.getMessage());
            produced.add(String.valueOf(e));
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

    @Test
    void anUnreachableProviderThrowsWithoutTheProjectId() {
        String dead = base();
        server.stop(0);
        server = null;
        assertThrows(IOException.class, () -> lookup(dead, 0));
    }
}
