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

    /**
     * The convert block and the config asset name, moved out of inline defaults on 2026-09-09.
     * ⚠ These use the NO-DEFAULT form {@code ${key}} rather than {@code ${key:}} — an empty string
     * cannot convert to a boolean or a BigInteger, so the safe-looking empty default would fail at
     * startup with a conversion error. No default at all says what is meant: the yaml must state it,
     * and a node whose configuration omits it does not boot.
     */
    private static final java.util.Map<String, String> NO_DEFAULT_KEYS = java.util.Map.of(
            "configAssetName", "loans.config.asset-name");

    @Test
    @DisplayName("the convert block and asset name carry NO default either — nothing inlined")
    void theConvertBlockIsAlsoPerProfile() throws Exception {
        for (var e : NO_DEFAULT_KEYS.entrySet()) {
            Field f = AppConfig.LoansConfiguration.class.getDeclaredField(e.getKey());
            assertEquals("${" + e.getValue() + "}", f.getAnnotation(Value.class).value(),
                    e.getKey() + " must carry no inline default at all");
        }
        // ⚑ TWO KEYS LEFT (2026-09-09). `profitMarginLovelace` was DELETED, not re-pointed: the
        // margin convert answers to is the shared loans.liquidation.profit-margin-lovelace. Both
        // survivors keep the same no-inline-default rule, because the reason for it did not change —
        // convert is a money path.
        // ⛔ The SHARED margin too, since 2026-09-10. It shipped 1,500,000 in the annotation while
        // the yaml shipped 5,000,000 — one documented knob with two answers, and after the margin
        // merge the wrong one governed convert as well.
        assertEquals("${loans.liquidation.profit-margin-lovelace}",
                AppConfig.LiquidationConfiguration.class.getDeclaredField("profitMarginLovelace")
                        .getAnnotation(Value.class).value(),
                "the shared margin must carry NO inline default: the yaml is its single source, and "
                        + "an inline one is a second answer that only non-Spring paths ever read");

        // ⛔ THREE KEYS SINCE 2026-09-09: the order cost joined them when the Minswap order's ada
        // became an explicit configurable EXPENSE, split from the builder constant that says what the
        // order must carry. Same rule, same reason — convert is a money path.
        for (var e : java.util.Map.of(
                "enabled", "loans.liquidation.convert.enabled",
                "dexCostFloorLovelace", "loans.liquidation.convert.dex-cost-floor-lovelace",
                "minswapOrderCostLovelace",
                "loans.liquidation.convert.minswap-order-cost-lovelace").entrySet()) {
            Field f = AppConfig.ConvertConfiguration.class.getDeclaredField(e.getKey());
            assertEquals("${" + e.getValue() + "}", f.getAnnotation(Value.class).value(),
                    e.getKey() + " must carry no inline default; convert is a money path and an "
                            + "inline default is a value no profile and no chart can reach");
        }

        String yaml = Files.readString(YAML);
        int preview = yaml.indexOf("on-profile: preview");
        // ⛔ THE ARMING DEFAULT. Exposed, documented, and ON — the documented exception to "nothing
        // ships armed", restored 2026-09-10 after a day at false: "convert should remain but under
        // liquidation and be enabled by default. there is a case we want to disable conversions."
        assertTrue(yaml.substring(0, preview)
                        .contains("enabled: ${LOANS_LIQUIDATION_CONVERT_ENABLED:true}"),
                "convert must ship ARMED on mainnet with its env override intact; the case for "
                        + "disabling conversions is what the key exists for, not what it defaults to");
        // ⛔ PARSED, not positional. `yaml.indexOf("enabled: true", preview)` matched ANY later
        // `enabled: true` — including an unrelated key's — so it passed with the convert flag set to
        // false. It read as the strongest line here and exercised nothing.
        for (Object document : new org.yaml.snakeyaml.Yaml().loadAll(yaml)) {
            java.util.Map<?, ?> convert = convertBlock(document);
            if (convert == null || !isPreview(document)) {
                continue;
            }
            assertEquals(Boolean.TRUE, convert.get("enabled"),
                    "the preview document must state the convert flag, and state it TRUE: a document "
                            + "that omits a no-default key does not start, and preview is prevented "
                            + "from converting by the blank Minswap pool address — one honest reason, "
                            + "not two");
        }

        // ⛔ AND THE COST FLOOR MUST BE STATED IN BOTH DOCUMENTS, for the same reason.
        assertTrue(yaml.substring(0, preview)
                        .contains("dex-cost-floor-lovelace: ${LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE:5000000}"),
                "the mainnet document must declare the DEX cost floor with its env override");
        assertTrue(yaml.indexOf("dex-cost-floor-lovelace: 5000000", preview) > preview,
                "the preview document must state the DEX cost floor as a literal — every key exists "
                        + "on every profile, or the profile that omits it fails to start");

        // ⛔ AND THE MINSWAP ORDER COST, in both documents, for the same reason.
        assertTrue(yaml.substring(0, preview).contains(
                        "minswap-order-cost-lovelace: ${LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE:4000000}"),
                "the mainnet document must declare the Minswap order COST with its env override — it "
                        + "is what a conversion spends, and an operator who believes it costs more "
                        + "than the order carries has no other way to say so");
        assertTrue(yaml.indexOf("minswap-order-cost-lovelace: 4000000", preview) > preview,
                "the preview document must state the Minswap order cost as a literal");

        // ⛔ THE DELETED KEY MUST NOT COME BACK, in either document. A chart still passing
        // LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE sets nothing and warns nowhere
        // (catalogue §6.7); a yaml key resurrected under the same name would look like it works.
        //
        // ⚠ PARSED, not grepped. The convert block's COMMENT names the dead env var on purpose — so
        // an operator reading the file learns it is dead — and a text search cannot tell a warning
        // about a key from the key itself. Only the parsed key set can.
        for (Object document : new org.yaml.snakeyaml.Yaml().loadAll(yaml)) {
            java.util.Map<?, ?> convert = convertBlock(document);
            if (convert == null) {
                continue;
            }
            assertEquals(java.util.Set.of("enabled", "dex-cost-floor-lovelace",
                            "minswap-order-cost-lovelace"),
                    new java.util.LinkedHashSet<>(convert.keySet()),
                    "the convert block holds exactly the global switch and the TWO cost inputs — the "
                            + "Minswap order cost and the DEX cost floor. profit-margin-lovelace was "
                            + "deleted on 2026-09-09 — the margin is the shared "
                            + "loans.liquidation.profit-margin-lovelace, for every mode — and a new "
                            + "key here is a knob nothing documents");
        }
    }

    /** {@code loans.liquidation.convert} in one yaml document, or {@code null} if it has none. */
    @SuppressWarnings("unchecked")
    /** True for the {@code on-profile: preview} document only. */
    private static boolean isPreview(Object document) {
        if (!(document instanceof java.util.Map<?, ?> m)) {
            return false;
        }
        Object spring = m.get("spring");
        if (!(spring instanceof java.util.Map<?, ?> sm)) {
            return false;
        }
        Object config = sm.get("config");
        if (!(config instanceof java.util.Map<?, ?> cm)) {
            return false;
        }
        Object activate = cm.get("activate");
        return activate instanceof java.util.Map<?, ?> am
                && "preview".equals(am.get("on-profile"));
    }

    private static java.util.Map<?, ?> convertBlock(Object document) {
        Object cursor = document;
        for (String segment : new String[]{"loans", "liquidation", "convert"}) {
            if (!(cursor instanceof java.util.Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            cursor = map.get(segment);
        }
        return (java.util.Map<Object, Object>) cursor;
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
