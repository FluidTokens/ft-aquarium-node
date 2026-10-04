package com.fluidtokens.aquarium.offchain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-134 B3b-5: no {@code QuickTxBuilder} in {@code src/main} is built from Blockfrost, and no class
 * builds its own Blockfrost supplier.
 *
 * <h2>Why a source scan</h2>
 * Every builder in this node now reads the local index ({@code IndexFirstUtxoSupplier}), the per-epoch
 * protocol parameters and the hash-checked script supplier — the three beans {@code YaciConfig} builds.
 * The way to undo that is one idiomatic line: {@code new QuickTxBuilder(backendService)}, or a
 * {@code new DefaultUtxoSupplier(...)} written "just for this one selection". Either sends coin
 * selection, reference inputs and protocol parameters back to a provider call per build, compiles, and
 * passes every behavioural test, because each of those tests supplies its own suppliers. The tank
 * processor's {@code referenceScriptSafeSupplier()} was exactly that: a fresh Blockfrost supplier per
 * call, invisible to any test of the builder it fed.
 *
 * <h2>What fails</h2>
 * <ul>
 *   <li>a {@code new QuickTxBuilder(} with fewer than three top-level arguments — the one-argument
 *       {@code (BackendService)} form and the two-argument {@code (BackendService, UtxoSupplier)} form
 *       both take their parameters (and the first, its UTxOs) from the provider;</li>
 *   <li>{@code new DefaultUtxoSupplier(}, {@code new DefaultProtocolParamsSupplier(} or
 *       {@code new DefaultScriptSupplier(} anywhere but {@code config/YaciConfig.java}, or more than once
 *       each inside it — there they are the index's out-ref fallback, the epoch cache's delegate and the
 *       script memo's delegate, and nothing else.</li>
 * </ul>
 * Comments and string literals are blanked before scanning, so javadoc that QUOTES a construction is not
 * one. The scan must also find the sites it knows exist, so an empty or broken scan cannot pass.
 */
class BlockfrostBuildWiringGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final String YACI_CONFIG = "com/fluidtokens/aquarium/offchain/config/YaciConfig.java";

    private static final List<String> BLOCKFROST_SUPPLIERS =
            List.of("DefaultUtxoSupplier", "DefaultProtocolParamsSupplier", "DefaultScriptSupplier");

    /**
     * The {@code new QuickTxBuilder(} sites in {@code src/main} at FAB-134 B3b-5: the tank bean in
     * {@code YaciConfig} (four arguments, with a processor), two per loans builder
     * (liquidate, pay-in-advance, compound, convert) and the wallet-shape builder (three). A count that
     * drops means the scan stopped seeing them, not that they went away.
     */
    private static final int KNOWN_QUICK_TX_BUILDER_SITES = 10;

    private record Site(String file, int line, int arguments) {
        @Override
        public String toString() {
            return file + ":" + line + " (" + arguments + " argument" + (arguments == 1 ? "" : "s") + ")";
        }
    }

    @Test
    void noQuickTxBuilderInSrcMainIsBuiltFromABackendService() {
        List<Site> sites = quickTxBuilderSites();

        List<Site> fromBackend = sites.stream().filter(site -> site.arguments() < 3).toList();
        assertTrue(fromBackend.isEmpty(),
                "a QuickTxBuilder in src/main is built from a BackendService (fewer than three arguments) — "
                        + "its coin selection, reference inputs and protocol parameters all go back to "
                        + "Blockfrost on every build. Build it from the injected UtxoSupplier, "
                        + "ProtocolParamsSupplier and ScriptSupplier beans instead: " + fromBackend);
    }

    @Test
    void blockfrostSuppliersAreBuiltOnlyAsYaciConfigsThreeDelegates() {
        List<String> violations = new ArrayList<>();
        for (String supplier : BLOCKFROST_SUPPLIERS) {
            List<Site> sites = constructionSites(supplier);
            for (Site site : sites) {
                if (!site.file().equals(YACI_CONFIG)) {
                    violations.add("new " + supplier + "( at " + site.file() + ":" + site.line()
                            + " — outside YaciConfig: a Blockfrost read the index-first, per-epoch or "
                            + "hash-checked bean was built to replace");
                }
            }
            long inYaciConfig = sites.stream().filter(site -> site.file().equals(YACI_CONFIG)).count();
            if (inYaciConfig > 1) {
                violations.add("new " + supplier + "( appears " + inYaciConfig + " times in YaciConfig: "
                        + sites + " — it belongs there once, as one bean's delegate");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * ⛔ THE SCAN MUST SEE WHAT IS THERE. A scanner that reads no file, or an argument counter that
     * answers the same for every call, passes the two tests above whatever the source says. So: every
     * known site is found, the arities found span the three-argument (no processor) and four-argument
     * (processor) forms, the wallet-shape builder's in-memory site is among them and passes, and each
     * Blockfrost supplier is found at its YaciConfig delegate.
     */
    @Test
    void theScanFindsTheSitesItKnowsExist() {
        List<Site> sites = quickTxBuilderSites();

        assertTrue(sites.size() >= KNOWN_QUICK_TX_BUILDER_SITES,
                "the scan found " + sites.size() + " `new QuickTxBuilder(` sites in src/main, fewer than the "
                        + KNOWN_QUICK_TX_BUILDER_SITES + " known: it is not reading the source it guards: " + sites);
        TreeSet<Integer> arities = new TreeSet<>();
        sites.forEach(site -> arities.add(site.arguments()));
        assertEquals(List.of(3, 4), List.copyOf(arities),
                "the argument counts found in src/main are " + arities + ", not exactly the three- and "
                        + "four-argument supplier forms: either a site changed shape or the counter is not "
                        + "counting: " + sites);
        assertTrue(sites.stream().anyMatch(site ->
                        site.file().endsWith("service/wallet/WalletShapeTransactions.java") && site.arguments() == 3),
                "the wallet-shape builder's in-memory three-argument site was not found: " + sites);
        assertTrue(sites.stream().anyMatch(site -> site.file().equals(YACI_CONFIG) && site.arguments() == 4),
                "the tank QuickTxBuilder bean in YaciConfig was not found as a four-argument site: " + sites);

        for (String supplier : BLOCKFROST_SUPPLIERS) {
            assertTrue(constructionSites(supplier).stream().anyMatch(site -> site.file().equals(YACI_CONFIG)),
                    "the scan did not find YaciConfig's `new " + supplier + "(` delegate");
        }
    }

    /** The argument counter, on inputs whose answer is known — including ones it must count as short. */
    @Test
    void theArgumentCounterCountsTopLevelArgumentsOnly() {
        assertEquals(1, argumentsOf("new QuickTxBuilder(backendService)"));
        assertEquals(2, argumentsOf("new QuickTxBuilder(backendService, utxoSupplier)"));
        assertEquals(1, argumentsOf("new QuickTxBuilder(\n    backend.with(a, b))"));
        assertEquals(3, argumentsOf("new QuickTxBuilder(supplierOf(a, b), params, (TransactionProcessor) null)"));
        assertEquals(4, argumentsOf("new QuickTxBuilder(u, p, s -> Optional.of(m.get(s, t)), new X<A, B>())"));
        assertEquals(0, argumentsOf("new QuickTxBuilder()"));
        assertEquals(List.of(), sitesIn("x.java",
                "/* new QuickTxBuilder(backend) */ String s = \"new QuickTxBuilder(b)\"; // new QuickTxBuilder(b)\n",
                "QuickTxBuilder"),
                "a construction inside a comment or a string literal is not a construction");
    }

    private static int argumentsOf(String code) {
        List<Site> sites = sitesIn("x.java", code, "QuickTxBuilder");
        assertEquals(1, sites.size(), "expected exactly one site in: " + code);
        return sites.getFirst().arguments();
    }

    private static List<Site> quickTxBuilderSites() {
        return constructionSites("QuickTxBuilder");
    }

    private static List<Site> constructionSites(String type) {
        assertTrue(Files.isDirectory(MAIN), "not run from the project root: " + MAIN.toAbsolutePath());
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                sites.addAll(sitesIn(relative, Files.readString(file, StandardCharsets.UTF_8), type));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sites;
    }

    /** Every {@code new <type>(} in the code (comments and literals blanked), with its top-level argument count. */
    private static List<Site> sitesIn(String file, String source, String type) {
        String code = blankCommentsAndLiterals(source);
        String needle = "new " + type + "(";
        List<Site> sites = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = code.indexOf(needle, from);
            if (at < 0) {
                return sites;
            }
            boolean wordStart = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
            from = at + needle.length();
            if (!wordStart) {
                continue;
            }
            int line = 1 + (int) code.substring(0, at).chars().filter(c -> c == '\n').count();
            sites.add(new Site(file, line, topLevelArguments(code, from)));
        }
    }

    /** Arguments of the call whose opening parenthesis ends just before {@code start}, up to its matching one. */
    private static int topLevelArguments(String code, int start) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<' && genericOpen(code, i)) {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>' && depth > 0 && genericClose(code, i)) {
                if (depth == 0) {
                    return any ? commas + 1 : 0;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
            if (!Character.isWhitespace(c) && !(depth == 0 && c == ')')) {
                any = true;
            }
        }
        throw new IllegalStateException("unbalanced parentheses after offset " + start);
    }

    /** A {@code <} that opens a type-argument list ({@code new X<A, B>()}), not a less-than. */
    private static boolean genericOpen(String code, int i) {
        return i > 0 && Character.isJavaIdentifierPart(code.charAt(i - 1))
                && i + 1 < code.length() && (Character.isUpperCase(code.charAt(i + 1)) || code.charAt(i + 1) == '>');
    }

    private static boolean genericClose(String code, int i) {
        return i > 0 && code.charAt(i - 1) != '-' // not a lambda arrow
                && (Character.isJavaIdentifierPart(code.charAt(i - 1)) || code.charAt(i - 1) == '>'
                || code.charAt(i - 1) == '<');
    }

    /** Comments, string, char and text-block contents become spaces; newlines survive so lines still count. */
    private static String blankCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(source, i, end, out);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(source, i, end, out);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                blank(source, i, end, out);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }
}
