package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>{@code loans.enabled} must stay gone, and a deleted flag is exactly the kind of thing that
 * grows back.</b>
 *
 * <h2>Why the flag was removed rather than defaulted to true</h2>
 * Giovanni, 2026-09-04: <i>"we must index loans; if the flag is flipped later we won't see old
 * loans."</i> Defaulting it on would have fixed a FRESH install and left the defect intact for any
 * node that ever toggled it — and the defect is not a gap in a report, it is unrecoverable data loss:
 * <ul>
 *   <li>{@code TankUtxoStorage} builds its payment-credential set <b>once, in its constructor</b>.
 *       With the flag off the v4 credentials are absent, so v4 UTxOs are offered to
 *       {@code saveUnspent} and <b>discarded at write time with no trace they were ever seen</b>,
 *       while Yaci Store's cursor advances regardless.</li>
 *   <li>Turning it back on widens the filter only for blocks indexed <b>from then on</b>. The blocks
 *       that passed meanwhile are never re-read.</li>
 *   <li>⚠ And {@code saveSpent} keeps working while the flag is off — so a loan indexed BEFORE the
 *       off-window that MOVES during it has its old row marked spent and its new output dropped.
 *       <b>The loan vanishes from the index while remaining live on chain.</b></li>
 * </ul>
 * Recovery is a cursor delete and a full re-sync, which is the operation the yaci-store 0.1.7
 * cursor-cleanup OOM makes hazardous. <b>No flag, no flip, no gap.</b>
 *
 * <h2>⚠ Why a source scan and not a wiring assertion</h2>
 * {@code ConvertBeansResolveTest} asserts no {@code @Bean} carries the conditional, which catches the
 * annotation. <b>It cannot catch the property being read some other way</b> — a {@code @Value}, a
 * {@code @ConditionalOnExpression}, an {@code Environment} lookup, or a fresh line in
 * {@code application.yaml} — and any of those reintroduces a switch whose "off" position silently
 * loses history. <b>The flag's danger is in the STRING, so the string is what this pins.</b>
 */
class LoansEnabledFlagIsGoneTest {

    /**
     * ⚠ Both spellings. Spring's relaxed binding means {@code loans.enabled} and
     * {@code LOANS_ENABLED} are the same switch, and pinning only one leaves the other as a way back.
     */
    private static final List<String> FORBIDDEN = List.of("loans.enabled", "LOANS_ENABLED");

    @Test
    void neitherTheJavaNorTheShippedConfigurationMentionsTheFlagAnyMore() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".yaml"))
                        .toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    // The prose that RECORDS the removal is allowed to name it; a line that READS it
                    // is not. Comment markers are the only reliable separator available to a scan.
                    int lineNo = 0;
                    for (String line : text.split("\n", -1)) {
                        lineNo++;
                        String trimmed = line.strip();
                        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("#")
                                || trimmed.startsWith("/*")) {
                            continue;
                        }
                        for (String flag : FORBIDDEN) {
                            if (line.contains(flag)) {
                                hits.add(file + ":" + lineNo + "  " + trimmed);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "loans.enabled is READ again in " + hits.size()
                + " place(s). It was removed on 2026-09-04 because its OFF position discards v4 UTxOs "
                + "at write time and its ON position never re-reads them — unrecoverable short of a "
                + "cursor delete and a full re-sync. If it is coming back, solve that first:\n"
                + String.join("\n", hits));
    }

    /**
     * ⛔ Proof of harness. A scan that finds nothing is indistinguishable from a scan that looked
     * nowhere, and this one walks a directory tree by relative path — which is exactly the kind of
     * thing that silently reads zero files under a different working directory.
     */
    @Test
    void theScanActuallyReadsTheSourcesItClaimsTo() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            long javaFiles = files.filter(f -> f.toString().endsWith(".java")).count();
            assertTrue(javaFiles > 50,
                    "the scan found only " + javaFiles + " java files under src/main/java; a test "
                            + "that reads nothing passes for free");
        }
        String appConfig = Files.readString(
                Path.of("src/main/java/com/fluidtokens/aquarium/offchain/config/AppConfig.java"),
                StandardCharsets.UTF_8);
        assertTrue(appConfig.contains("loans.liquidation.mode"),
                "the reader must be able to see a property that IS still there, or its inability to "
                        + "find the forbidden one proves nothing");
    }
}
