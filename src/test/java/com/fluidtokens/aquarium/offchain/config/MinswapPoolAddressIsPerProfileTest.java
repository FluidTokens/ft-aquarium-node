package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ {@code loans.minswap.pool-address} must be resolvable PER PROFILE.
 *
 * <p>It used to exist only as an inline {@code @Value} default carrying the MAINNET address, with no
 * key in {@code application.yaml} at all. <b>A profile can only override a key that exists</b>, so
 * the preview document had nothing to blank: a preview node resolved the mainnet address, sent it to
 * a preview provider, and took {@code "Invalid address for this network"} back at ERROR once per
 * scheduling cycle — observed running on 2026-09-08.
 *
 * <p>⚠ <b>The assertion that carries the property is the ABSENCE of an inline default.</b> Re-adding
 * one would restore the exact defect while every other test still passed, because the mainnet
 * document supplies the same value and nothing on the mainnet path would change.
 */
class MinswapPoolAddressIsPerProfileTest {

    private static final Path YAML = Path.of("src/main/resources/application.yaml");
    private static final String MAINNET_POOL =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    /** field name -> the property key it must read, with NO default after the colon. */
    private static final java.util.Map<String, String> ALL_FOUR = java.util.Map.of(
            "minswapPoolPolicyId", "loans.minswap.pool-policy-id",
            "minswapPoolSpendScriptHash", "loans.minswap.pool-spend-script-hash",
            "minswapOrderSpendScriptHash", "loans.minswap.order-spend-script-hash",
            "minswapPoolAddress", "loans.minswap.pool-address");

    @Test
    @DisplayName("NONE of the four @Values carries an inline default — a hardcoding no profile reaches")
    void noneOfThemHasAnInlineDefault() throws Exception {
        for (var e : ALL_FOUR.entrySet()) {
            Field f = AppConfig.LoansConfiguration.class.getDeclaredField(e.getKey());
            String expression = f.getAnnotation(Value.class).value();

            assertEquals("${" + e.getValue() + ":}", expression,
                    e.getKey() + " carries an inline default; no profile document can override a key "
                            + "that resolves before the yaml is consulted, which is exactly how a "
                            + "preview node came to resolve MAINNET Minswap credentials");
        }
        // The address is the one that reached a provider and produced the visible failure.
        assertFalse(AppConfig.LoansConfiguration.class.getDeclaredField("minswapPoolAddress")
                        .getAnnotation(Value.class).value().contains(MAINNET_POOL),
                "the mainnet address is inlined again; the preview profile cannot blank it");
    }

    @Test
    @DisplayName("all four are declared in mainnet and blanked in preview")
    void allFourAreStatedPerProfile() throws Exception {
        String yaml = Files.readString(YAML);
        int preview = yaml.indexOf("on-profile: preview");
        assertTrue(preview > 0, "the preview document moved");

        for (String key : new String[]{"pool-policy-id", "pool-spend-script-hash",
                "order-spend-script-hash", "pool-address"}) {
            int mainnet = yaml.indexOf(key + ": ${");
            assertTrue(mainnet > 0 && mainnet < preview,
                    key + " must be declared in the MAINNET document with an env override");
            assertTrue(yaml.indexOf(key + ": \"\"", preview) > preview,
                    key + " must be blanked in the PREVIEW document — Minswap V2 has no preview "
                            + "deployment, so every one of these credentials is absent there");
        }
    }

    @Test
    @DisplayName("mainnet declares the address; preview blanks it explicitly")
    void eachProfileStatesItsOwn() throws Exception {
        String yaml = Files.readString(YAML);

        assertTrue(yaml.contains("pool-address: ${LOANS_MINSWAP_POOL_ADDRESS:" + MAINNET_POOL + "}"),
                "the mainnet document must declare the address, with an env override");
        assertTrue(yaml.contains("pool-address: \"\""),
                "the preview document must blank it — Minswap V2 has no preview deployment, and a "
                        + "blank is what makes convert SKIP rather than call a provider it cannot "
                        + "succeed against");

        // ⛔ The blank must be in the PREVIEW document, not merely somewhere in the file.
        int preview = yaml.indexOf("on-profile: preview");
        assertTrue(preview > 0, "the preview document moved");
        assertTrue(yaml.indexOf("pool-address: \"\"") > preview,
                "the blank sits before the preview document, so it is not the preview override");
    }
}
