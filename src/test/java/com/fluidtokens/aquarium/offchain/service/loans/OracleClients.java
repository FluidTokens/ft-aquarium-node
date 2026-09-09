package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

/**
 * Test support: builds a {@link FluidOracleClient} loaded from a captured registry payload.
 * <p>
 * {@code FluidOracleClient.load} is package-private on purpose — replacing the held prices is not
 * something production code outside this package should do — so this lives here and is public only
 * so tests in other packages can reach it.
 */
public final class OracleClients {

    private OracleClients() {
    }

    public static FluidOracleClient fromFixture(String resource) throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        try (InputStream in = OracleClients.class.getResourceAsStream(resource)) {
            client.load(new ObjectMapper().readTree(in));
        }
        return client;
    }

    /** The preview registry, which is what the node actually polls. */
    public static FluidOracleClient preview() throws Exception {
        return fromFixture("/loans-v4/oracle-registry-preview.json");
    }
}
