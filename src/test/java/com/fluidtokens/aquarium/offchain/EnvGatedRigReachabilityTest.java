package com.fluidtokens.aquarium.offchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skipped rig must not be able to hide behind a credential it can never be handed.
 * <p>
 * Twenty-three test classes here are gated with {@code @EnabledIfEnvironmentVariable}. That is the
 * right shape — they need a key, a mnemonic, or a deliberate opt-in, and a keyless run must not go
 * red. But it makes <b>a skip and a pass indistinguishable</b>: JUnit reports "disabled", the build
 * says BUILD SUCCESSFUL, and nobody notices that the verification never happened. The failure mode
 * this class exists to prevent is the sharpest form of that: the developer <em>has</em> the
 * credential, follows the documented route to supply it, and the rig skips anyway because the file
 * defines it under one name and the gate names another.
 * <p>
 * That is not hypothetical. {@code ConvertLiveDryEvalTest} and
 * {@code LiquidatePayInAdvanceLiveDryEvalTest} gate on {@code BLOCKFROST_MAINNET_KEY}; {@code
 * .env.mainnet} defined only {@code BLOCKFROST_KEY}. Sourcing the mainnet credential file and
 * running the suite left both mainnet dry-eval rigs SKIPPED, which reads as "not failing". Three
 * separate sessions were served a silently-absent verification by it in a single day.
 *
 * <h2>The invariant</h2>
 * For every test class that talks to a Blockfrost network — mainnet or preview, established from
 * the source itself (the {@code cardano-<net>.blockfrost.io} URL, or cardano-client-lib's
 * {@code BLOCKFROST_<NET>_URL} constant) — every Blockfrost credential it depends on, whether named
 * in an {@code @EnabledIfEnvironmentVariable} gate or read with {@code System.getenv}, must be
 * <b>defined by {@code .env.<net>}</b>: the file a developer following {@code CLAUDE.md} sources to
 * run exactly those rigs. A class that reaches both networks is satisfied by either file.
 *
 * <h2>⛔ Values are never read</h2>
 * The {@code .env.*} files hold live credentials and are untracked. This class parses <b>names
 * only</b>: everything right of the first {@code =} is discarded unless it is an alias of the exact
 * shape {@code NAME=$OTHER} (or {@code "${OTHER}"}), in which case the referenced <em>name</em> is
 * extracted so that an alias pointing at a variable defined nowhere earlier in the file — which
 * expands to the empty string and therefore still fails a {@code matches = ".+"} gate — is caught.
 * No value reaches an assertion message, a log, or this file.
 *
 * <h2>What CI can and cannot check here</h2>
 * CI holds no secrets and no {@code .env.*} files, by design. {@link
 * #sourcingTheNetworkEnvFileEnablesEveryRigThatTalksToThatNetwork()} therefore has nothing to
 * cross-check there and passes on the source-side half alone; it is a <b>developer-local</b> guard.
 * The two structural checks — the pinned credential-variable set and the per-network gate names —
 * run everywhere, so a new credential name cannot appear without this class being updated.
 */
class EnvGatedRigReachabilityTest {

    /**
     * The Blockfrost credential variables this repo knows about. Deliberately two, not one:
     * one variable per network. A preview key pointed at mainnet answers HTTP 403, which reads as a
     * code failure rather than a config problem — that cost a debugging round once already, and
     * collapsing these onto a single name would buy it back. Adding a third means adding it to the
     * matching {@code .env.*} file in the same commit.
     */
    private static final Set<String> KNOWN_BLOCKFROST_CREDENTIALS =
            Set.of("BLOCKFROST_KEY", "BLOCKFROST_MAINNET_KEY");

    /** Anything named like a Blockfrost credential. Deliberately wider than the pinned set above. */
    private static final Pattern BLOCKFROST_CREDENTIAL = Pattern.compile("BLOCKFROST[A-Z0-9_]*KEY");

    private static final Pattern GATE =
            Pattern.compile("@EnabledIfEnvironmentVariable\\s*\\(\\s*named\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern GETENV =
            Pattern.compile("getenv\\s*\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)");

    /** An alias line, and nothing else: {@code NAME=$OTHER}, {@code NAME="${OTHER}"}. */
    private static final Pattern ALIAS_RHS =
            Pattern.compile("^[\"']?\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?[\"']?$");

    /** One test class's dependency on Blockfrost credentials, and the networks it reaches. */
    private record Rig(String className, Set<String> credentials, Set<String> networks) {
    }

    // ---- the checks -------------------------------------------------------------------------------

    /**
     * The scan found what it is supposed to be scanning, and every Blockfrost credential in the test
     * tree is one of the two known names.
     * <p>
     * The first half is the harness guard: a source scan that quietly matches nothing passes every
     * assertion built on it. The second half is what stops the problem recurring under a new name.
     */
    @Test
    void everyBlockfrostCredentialInTheTestTreeIsOneOfTheKnownNames() {
        List<Path> sources = testSources();
        assertTrue(sources.size() >= 100,
                "source scan found only " + sources.size() + " test files under src/test/java — "
                        + "it is not scanning what it thinks it is scanning");

        List<Rig> rigs = rigs(sources);
        assertTrue(rigs.size() >= 15,
                "source scan found only " + rigs.size() + " Blockfrost-dependent test classes — "
                        + "the gate/getenv patterns have stopped matching");

        Set<String> found = new TreeSet<>();
        rigs.forEach(r -> found.addAll(r.credentials()));
        assertEquals(new TreeSet<>(KNOWN_BLOCKFROST_CREDENTIALS), found,
                "a Blockfrost credential variable appeared or vanished. Every one of them must be "
                        + "defined by the .env.<network> file a developer sources, and listed in "
                        + "KNOWN_BLOCKFROST_CREDENTIALS, in the same commit.");
    }

    /**
     * ⚠ <b>The A3 check.</b> Sourcing {@code .env.<network>} must be enough to make every rig that
     * talks to that network actually run. A rig whose gate names a variable the file does not define
     * skips, and a skip reads as "not failing".
     */
    @Test
    void sourcingTheNetworkEnvFileEnablesEveryRigThatTalksToThatNetwork() {
        Map<String, Set<String>> defined = new LinkedHashMap<>();
        for (String network : List.of("mainnet", "preview")) {
            Path envFile = repoRoot().resolve(".env." + network);
            if (Files.exists(envFile)) {
                defined.put(network, effectivelyDefinedNames(envFile));
            }
        }

        List<String> unreachable = new ArrayList<>();
        for (Rig rig : rigs(testSources())) {
            for (String credential : new TreeSet<>(rig.credentials())) {
                List<String> checkable = rig.networks().stream().filter(defined::containsKey).sorted().toList();
                if (checkable.isEmpty()) {
                    continue; // no credential file for this network here (CI): nothing to cross-check
                }
                boolean supplied = checkable.stream().anyMatch(n -> defined.get(n).contains(credential));
                if (!supplied) {
                    unreachable.add(rig.className() + " needs " + credential
                            + " but " + checkable.stream().map(n -> ".env." + n).toList()
                            + " does not define it — the rig skips, and a skip reads as a pass");
                }
            }
        }

        assertTrue(unreachable.isEmpty(),
                "rigs a developer cannot reach by following CLAUDE.md:\n  " + String.join("\n  ", unreachable));
    }

    /**
     * The two mainnet dry-eval rigs still gate on the mainnet-specific key.
     * <p>
     * The mismatch this class was written for has two possible fixes, and only one of them is
     * correct: define the alias in {@code .env.mainnet}, or rename the gates to {@code
     * BLOCKFROST_KEY}. The rename is the tempting one and it is wrong — it points whatever key
     * happens to be in the environment (a preview key, if {@code .env.preview} was sourced) at
     * mainnet, which is the HTTP 403 that reads as a code failure. This pins the separation so the
     * wrong fix cannot grow back quietly.
     */
    @Test
    void theMainnetDryEvalRigsGateOnTheMainnetSpecificKey() {
        for (String className : List.of("ConvertLiveDryEvalTest", "LiquidatePayInAdvanceLiveDryEvalTest")) {
            Rig rig = rigs(testSources()).stream()
                    .filter(r -> r.className().equals(className))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(className + " no longer scans as a Blockfrost rig"));

            assertEquals(Set.of("BLOCKFROST_MAINNET_KEY"), rig.credentials(),
                    className + " must depend on BLOCKFROST_MAINNET_KEY and nothing else: one variable "
                            + "per network is what keeps a preview key from being pointed at mainnet");
            assertEquals(Set.of("mainnet"), rig.networks(), className + " must talk to mainnet only");
        }
    }

    /**
     * The {@code .env} parser above, driven over a synthetic file, because the real one cannot prove
     * it: {@code .env.mainnet} happens to satisfy every branch, so a parser that simply never
     * rejected anything would pass {@link
     * #sourcingTheNetworkEnvFileEnablesEveryRigThatTalksToThatNetwork()} just as happily. This pins
     * the two shapes that expand to the empty string when sourced — and therefore still fail a
     * {@code matches = ".+"} gate — as NOT defined.
     */
    @Test
    void theEnvParserResolvesAliasesAndRejectsTheOnesThatExpandToNothing(@TempDir Path dir) {
        Path envFile = dir.resolve(".env.synthetic");
        write(envFile, List.of(
                "# a comment, and a blank line follow",
                "",
                "BLOCKFROST_KEY=placeholder-not-a-credential",
                "export EXPORTED_KEY=placeholder-not-a-credential",
                "GOOD_ALIAS=\"$BLOCKFROST_KEY\"",
                "BRACED_ALIAS=${EXPORTED_KEY}",
                "DANGLING_ALIAS=$NEVER_DEFINED",
                "TOO_EARLY_ALIAS=$DEFINED_BELOW",
                "DEFINED_BELOW=placeholder-not-a-credential"));

        assertEquals(
                Set.of("BLOCKFROST_KEY", "EXPORTED_KEY", "GOOD_ALIAS", "BRACED_ALIAS", "DEFINED_BELOW"),
                effectivelyDefinedNames(envFile),
                "an alias whose target is undefined, or defined only further down the file, expands to "
                        + "the empty string when sourced and must not count as supplying the variable");
    }

    // ---- source scanning --------------------------------------------------------------------------

    private static List<Path> testSources() {
        Path root = repoRoot().resolve("src/test/java");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Rig> rigs(List<Path> sources) {
        List<Rig> rigs = new ArrayList<>();
        for (Path source : sources) {
            String text = read(source);

            Set<String> credentials = new LinkedHashSet<>();
            for (Pattern p : List.of(GATE, GETENV)) {
                Matcher m = p.matcher(text);
                while (m.find()) {
                    if (BLOCKFROST_CREDENTIAL.matcher(m.group(1)).matches()) {
                        credentials.add(m.group(1));
                    }
                }
            }
            if (credentials.isEmpty()) {
                continue;
            }

            Set<String> networks = new LinkedHashSet<>();
            if (text.contains("cardano-mainnet.blockfrost.io") || text.contains("BLOCKFROST_MAINNET_URL")) {
                networks.add("mainnet");
            }
            if (text.contains("cardano-preview.blockfrost.io") || text.contains("BLOCKFROST_PREVIEW_URL")) {
                networks.add("preview");
            }

            String className = source.getFileName().toString().replace(".java", "");
            rigs.add(new Rig(className, credentials, networks));
        }
        return rigs;
    }

    // ---- .env parsing: NAMES ONLY -----------------------------------------------------------------

    /**
     * The variable names an {@code .env.*} file supplies with a <b>non-empty</b> value when sourced.
     * <p>
     * A plain {@code NAME=<literal>} qualifies and its value is discarded unread. An alias of the
     * exact shape {@code NAME=$OTHER} qualifies only if {@code OTHER} itself qualifies earlier in the
     * same file — an alias of an undefined variable expands to the empty string and still fails a
     * {@code matches = ".+"} gate, which is the same silent skip in a different disguise. Any other
     * right-hand side is treated as an opaque literal and never inspected, so no fragment of a
     * credential can reach an assertion message.
     */
    private static Set<String> effectivelyDefinedNames(Path envFile) {
        Set<String> defined = new LinkedHashSet<>();
        for (String rawLine : readLines(envFile)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).strip();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            Optional<String> aliasOf = aliasTarget(line.substring(eq + 1).strip());
            if (aliasOf.isPresent()) {
                if (defined.contains(aliasOf.get())) {
                    defined.add(name);
                }
            } else {
                defined.add(name);
            }
        }
        return defined;
    }

    /** The referenced name if the right-hand side is nothing but a variable reference, else empty. */
    private static Optional<String> aliasTarget(String rhs) {
        Matcher m = ALIAS_RHS.matcher(rhs);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new AssertionError("cannot locate the repo root from " + System.getProperty("user.dir"));
        }
        return dir;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, List<String> lines) {
        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
