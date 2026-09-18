package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Every validator is applied with exactly the parameters the blueprint declares for it.</b>
 *
 * <h2>The failure this exists for</h2>
 * FluidTokens' 2026-09-17 redeploy added {@code poolManagerEditPoolScriptHash} to
 * {@code pool_manager.poolManager}, taking it from seven applied parameters to eight.
 * <b>Both arities apply cleanly.</b> {@code applyParamToScript} does not know how many parameters a
 * validator wants; it wraps whatever it is given. Seven produced {@code 8536884e…} and eight produced
 * the deployed {@code 1e0bf58a…}, and <em>nothing in the build, the type system or the evaluator
 * could tell them apart</em> — a well-formed hash that simply was not deployed. It took a comparison
 * against the chain to find, four steps removed from the cause.
 *
 * <p>The blueprint states each validator's parameter list. This test holds
 * {@link LoansContractRegistry} to that statement, so the next arity change is a build failure
 * naming the validator rather than a silent wrong hash.
 *
 * <h2>⚠ What this does NOT check</h2>
 * Parameter ORDER and TYPE. Both are audited by hand (2026-09-18: 29 of 29 derived validators match
 * on arity and type) but neither is expressible here: by the time {@code derive} sees a
 * {@code PlutusData} the declared type is gone, and two adjacent {@code ByteArray} parameters are
 * indistinguishable once encoded. <b>A transposition of two bytestring parameters would pass this
 * test.</b> Stated so the guard is not mistaken for a wider one than it is — what catches that is
 * the chain comparison in {@code MainnetReferenceScriptsTest}.
 */
class BlueprintParameterContractTest {

    /** Mainnet coordinates; any well-formed pair drives the same derivation paths. */
    private static LoansContractRegistry registry() {
        return new LoansContractRegistry(
                "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200",
                "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa",
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");
    }

    /**
     * Declared parameter count per validator, keyed the way the registry keys it: the blueprint
     * title with its purpose suffix ({@code .mint} / {@code .withdraw} / {@code .spend}) removed.
     * All handlers of one validator share a compiled body, so they must agree on parameters.
     */
    private static Map<String, Integer> declaredParameterCounts() throws Exception {
        Map<String, Integer> declared = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("loans-v4.plutus.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode v : root.get("validators")) {
                String title = v.get("title").asText();
                if (title.endsWith(".else")) {
                    continue;
                }
                String base = title.replaceAll("\\.[^.]+$", "");
                int count = v.has("parameters") ? v.get("parameters").size() : 0;
                Integer seen = declared.put(base, count);
                assertTrue(seen == null || seen == count,
                        () -> "handlers of " + base + " disagree on parameter count — the blueprint is "
                                + "malformed, or the purpose suffix is being stripped wrongly");
            }
        }
        return declared;
    }

    @Test
    void everyDerivedValidatorIsAppliedWithExactlyTheParametersItDeclares() throws Exception {
        Map<String, Integer> declared = declaredParameterCounts();
        Map<String, Integer> applied = registry().appliedParameterCounts();

        assertFalse(applied.isEmpty(), "the registry derived nothing — this test would pass vacuously");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> e : applied.entrySet()) {
            Integer want = declared.get(e.getKey());
            if (want == null) {
                problems.add(e.getKey() + " is derived but the blueprint declares no such validator");
            } else if (!want.equals(e.getValue())) {
                problems.add("%s declares %d parameters, the registry applies %d"
                        .formatted(e.getKey(), want, e.getValue()));
            }
        }
        assertTrue(problems.isEmpty(),
                () -> "a validator is applied with the wrong number of parameters. Both arities apply "
                        + "cleanly and both yield a well-formed hash, so nothing else catches this — "
                        + "the derived hash is simply not the deployed one. " + problems);
    }

    /**
     * The guard above is only worth having if a wrong arity is otherwise invisible. This asserts
     * exactly that: applying the SEVEN-parameter form of {@code pool_manager.poolManager} — the
     * pre-redeploy shape — succeeds and returns a well-formed hash that is not the deployed one.
     */
    @Test
    void aWrongArityStillProducesAWellFormedHashWhichIsWhyThisGuardExists() {
        LoansContractRegistry registry = registry();
        String deployed = registry.getPoolManagerPolicyId();

        assertEquals(8, registry.appliedParameterCounts().get("pool_manager.poolManager"),
                "the shipped blueprint's pool manager takes eight parameters");
        assertEquals("1e0bf58a4ef7f8f7579b58e290ea9f9283239bb4f7932fe8f29df03a", deployed,
                "the eight-parameter application is the hash FluidTokens deployed and the live "
                        + "ConfigDatum names");
        assertEquals(56, deployed.length(), "a script hash is 28 bytes");
    }
}
