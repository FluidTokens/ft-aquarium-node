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

    @Test
    @DisplayName("the @Value carries NO inline default — a hardcoding no profile can reach")
    void thereIsNoInlineDefault() throws Exception {
        Field f = AppConfig.LoansConfiguration.class.getDeclaredField("minswapPoolAddress");
        String expression = f.getAnnotation(Value.class).value();

        assertEquals("${loans.minswap.pool-address:}", expression,
                "an inline default here cannot be overridden by any profile document — that is what "
                        + "made a preview node resolve the mainnet address");
        assertFalse(expression.contains(MAINNET_POOL),
                "the mainnet address is inlined again; the preview profile cannot blank it");
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
