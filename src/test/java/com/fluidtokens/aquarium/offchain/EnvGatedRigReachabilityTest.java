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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>
 * That promise is only as wide as the scan behind it, which is why {@link
 * #theAnnotationScanAloneSeesTheFullyQualifiedGateForm()} and {@link
 * #theAnnotationScanAloneStillSeesThePlainGateForm()} exist: both spellings of the annotation must
 * be visible to {@link #GATE}, and neither may be rescued by a {@code System.getenv} call that a
 * refactor is free to delete.
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

    /** One rig switched off on purpose: the class, the gate that parks it, and why. */
    private record ParkedRig(String className, String gate, String reason) {
    }

    /**
     * ⛔ <b>The parked rigs — unreachable ON PURPOSE, named one by one.</b>
     * <p>
     * The checks in this class answer "can a developer reach this rig by following CLAUDE.md". For a
     * rig that has been deliberately switched off, the honest answer is <em>no, and that is the
     * decision</em> — but "unreachable by accident" and "unreachable on purpose" look identical from
     * the outside, and the first is a defect while the second is a ruling. This list is where the
     * difference is written down, by name, with its reason attached.
     * <p>
     * A park is expressed as a <b>second class-level gate whose name is not a credential</b>, so the
     * CI run summary reports the rig as waiting on that gate rather than on a key it will never be
     * handed — see {@link #theParkExemptionCoversExactlyTheClassesThatCarryAParkGate()}, which
     * refuses a park named like a credential. The park gate is deliberately <b>absent</b> from every
     * {@code .env.*} file, and {@link
     * #aParkedRigStaysParkedAndCannotBeUnparkedByEditingAnEnvFile()} keeps it absent: adding it there
     * would quietly re-arm a rig somebody switched off, which then goes red for a reason that is not
     * a code fault and invites exactly the "repair" the park exists to prevent.
     * <p>
     * Adding an entry here is a decision, not a maintenance step. Removing one is how a rig comes
     * back.
     */
    private static final List<ParkedRig> PARKED_RIGS = List.of(new ParkedRig(
            "LiquidatePayInAdvanceLiveDryEvalTest",
            "AQUARIUM_ANTICIPATE_RIG_CANDIDATE",
            "FAB-86, ruling of 2026-09-10 — parked, not broken: the loan it is pinned to was "
                    + "liquidated by this bot, so the reference input and the wallet's USDM are gone "
                    + "and the rig's 3 failures are a stale fixture rather than a code fault. It comes "
                    + "back only with a live liquidatable loan big enough to exercise wallet sizing, "
                    + "the balance check and POOL_TOO_THIN — see the class javadoc"));

    /**
     * The qualifier is optional on purpose: {@code @EnabledIfEnvironmentVariable} and
     * {@code @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable} are the same annotation,
     * and both are in this tree. Anchoring {@code @} straight to the simple name made the second form
     * invisible — a gated rig that this class could not see and that CI reported as "waiting on
     * disabled". Keep {@code *}, not {@code +}: of the 45 annotation usages in this tree, across 23
     * classes, 44 carry no qualifier and exactly one is fully qualified.
     */
    private static final Pattern GATE =
            Pattern.compile("@(?:[A-Za-z_][A-Za-z0-9_]*\\.)*EnabledIfEnvironmentVariable"
                    + "\\s*\\(\\s*named\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern GETENV =
            Pattern.compile("getenv\\s*\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)");

    /**
     * Step 3b of {@code .github/workflows/docker-build.yml} carries its own copy of {@link #GATE} in
     * Python, because CI cannot call into this class. This locates it so the two can be pinned
     * textually rather than left to drift.
     */
    private static final Pattern WORKFLOW_GATE_DECLARATION =
            Pattern.compile("GATE\\s*=\\s*re\\.compile\\(r'([^']*)'\\)");

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
     * <p>
     * <b>Scope:</b> this measures <em>credentials</em> — the things a developer is entitled to have
     * and the {@code .env.*} files exist to supply. It deliberately says nothing about the
     * opt-in gates ({@code AQUARIUM_*}, {@code SUBMITTABLE_NETWORK}, {@code BUILD_STAKE_REG} …) that
     * several rigs also carry: those are switches a human is supposed to throw one run at a time,
     * and a credential file that pre-threw them would be a bug. A rig held shut by one of those is
     * therefore invisible here — which is fine when the switch is an opt-in and <em>not</em> fine
     * when it is a park, because a park is permanent. Parks are named in {@link #PARKED_RIGS} and
     * checked by the two tests below.
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
     * <p>
     * ⚠ Read this as a statement about the <b>key</b> only. Since FAB-86,
     * {@code LiquidatePayInAdvanceLiveDryEvalTest} is also parked (see {@link #PARKED_RIGS}), so
     * sourcing {@code .env.mainnet} runs {@code ConvertLiveDryEvalTest} and not the other one. The
     * assertions below stay as they are on purpose: the park must not become an excuse for the
     * mainnet key separation to rot while the rig is off.
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
     * A park is only a park while both halves hold: the gate is still on the class, and no
     * {@code .env.*} file supplies it.
     * <p>
     * The second half is the one with teeth. When {@link
     * #sourcingTheNetworkEnvFileEnablesEveryRigThatTalksToThatNetwork()} reports a rig as
     * unreachable, the reflex fix is to add the missing variable to the credential file — that was
     * the correct fix once and it is the wrong one here, because it re-arms a rig that was switched
     * off deliberately. The rig then fails on a fixture nobody meant to restore, and the next
     * reader "repairs" it. Nothing else in this tree would notice, so it is pinned here.
     */
    @Test
    void aParkedRigStaysParkedAndCannotBeUnparkedByEditingAnEnvFile() {
        for (ParkedRig parked : PARKED_RIGS) {
            assertTrue(gateNames(read(testSource(parked.className()))).contains(parked.gate()),
                    parked.className() + " no longer carries its park gate " + parked.gate()
                            + ". If it was deliberately brought back, delete its PARKED_RIGS entry in "
                            + "the same commit: an entry left behind is a standing exemption for a rig "
                            + "nobody is parking any more. Reason on record — " + parked.reason());

            for (String network : List.of("mainnet", "preview")) {
                Path envFile = repoRoot().resolve(".env." + network);
                if (!Files.exists(envFile)) {
                    continue; // CI holds no .env.* files, by design: nothing to cross-check here
                }
                assertFalse(effectivelyDefinedNames(envFile).contains(parked.gate()),
                        ".env." + network + " now defines " + parked.gate() + ", which silently "
                                + "un-parks " + parked.className() + ": sourcing that file would run a "
                                + "rig that was switched off on purpose, and it would go red for "
                                + "something that is not a code fault. Un-park it by deleting the "
                                + "PARKED_RIGS entry and the gate, deliberately — not by editing a "
                                + "credential file. Reason on record — " + parked.reason());
            }
        }
    }

    /**
     * ⚠ <b>The exemption must stay narrow.</b> {@link #PARKED_RIGS} excuses named classes from being
     * reachable, and an exemption that quietly covers a second rig is how a real defect gets filed
     * under a past decision. So the list and the tree must agree <b>in both directions</b>: every
     * listed class carries a park gate, and every class carrying a listed park gate is on the list.
     * Broadening the list to a class that is not parked fails here; copying a park gate onto another
     * rig without listing it fails here too.
     * <p>
     * And a park gate may never be <em>named</em> like a credential. That is the whole point of the
     * shape: the run summary prints the gate names it finds, so a park called
     * {@code BLOCKFROST_SOMETHING_KEY} would be reported as a rig waiting for a key it will never be
     * handed — indistinguishable from the credential skip this class exists to make visible.
     */
    @Test
    void theParkExemptionCoversExactlyTheClassesThatCarryAParkGate() {
        Set<String> listed = new TreeSet<>();
        Set<String> parkGates = new TreeSet<>();
        for (ParkedRig parked : PARKED_RIGS) {
            assertTrue(listed.add(parked.className()),
                    "PARKED_RIGS lists " + parked.className() + " twice: two reasons for one park "
                            + "means one of them is not being read");
            assertFalse(BLOCKFROST_CREDENTIAL.matcher(parked.gate()).matches(),
                    "the park gate " + parked.gate() + " is named like a Blockfrost credential, so "
                            + "the run summary would report " + parked.className() + " as waiting on "
                            + "a key rather than as parked — the very confusion the second-gate shape "
                            + "exists to remove. Name it for what is actually missing.");
            parkGates.add(parked.gate());
        }

        Set<String> carrying = new TreeSet<>();
        for (Path source : testSources()) {
            if (gateNames(read(source)).stream().anyMatch(parkGates::contains)) {
                carrying.add(source.getFileName().toString().replace(".java", ""));
            }
        }

        assertEquals(listed, carrying,
                "PARKED_RIGS and the test tree disagree about which rigs are parked. A listed class "
                        + "that carries no park gate is an exemption covering a rig nobody switched "
                        + "off; an unlisted class carrying one is a park with no reason written down.");
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

    /**
     * ⚠ <b>The annotation scan alone must see the fully-qualified form.</b> The first cut of this
     * guard anchored {@code @} directly to the simple class name, so
     * {@code @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(...)} never matched.
     * {@code RealLoanDryEvalTest} uses exactly that form, and reached the checks above only
     * incidentally, through the {@code System.getenv} half of the scan — delete that call in some
     * future refactor and the class leaves this guard's world with nothing to notice. Worse, the
     * workflow's copy of the same pattern reported it as <em>"waiting on disabled"</em>: an operator
     * is told the verification can never run, inside the one step whose stated purpose is "a skip is
     * not a pass", when supplying the key would in fact run it.
     * <p>
     * So this asserts on {@link #gateNames(String)} — the annotation pattern by itself, with no
     * getenv fallback available to rescue it.
     */
    @Test
    void theAnnotationScanAloneSeesTheFullyQualifiedGateForm() {
        String text = read(testSource("RealLoanDryEvalTest"));

        assertTrue(text.contains("@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable("),
                "RealLoanDryEvalTest no longer carries the fully-qualified annotation form, and it is "
                        + "the only instance of it in the tree. Point this test at whatever class "
                        + "carries it now — do not delete the assertion, or the scan can go blind to "
                        + "the form again with nothing left to detect it.");

        assertEquals(Set.of("BLOCKFROST_KEY"), gateNames(text),
                "the @EnabledIfEnvironmentVariable scan does not see the fully-qualified form. A rig "
                        + "gated that way is invisible to this class, and the CI step summary reports "
                        + "it as 'waiting on disabled' — the verification reads as off forever.");
    }

    /**
     * And admitting the qualifier must not have cost the plain form. A qualifier made
     * <em>mandatory</em> — {@code +} where the pattern has {@code *} — would miss all 43 plain
     * occurrences in this tree while still satisfying the check above. The rig-level checks would
     * not notice for {@code ConvertLiveDryEvalTest}, because it also reads its credential with
     * {@code System.getenv}; only a gate-only assertion does.
     */
    @Test
    void theAnnotationScanAloneStillSeesThePlainGateForm() {
        assertEquals(Set.of("BLOCKFROST_MAINNET_KEY"), gateNames(read(testSource("ConvertLiveDryEvalTest"))),
                "the @EnabledIfEnvironmentVariable scan has stopped seeing the plain, unqualified "
                        + "annotation form — the form all but one gated class in this tree uses");
    }

    /**
     * The workflow's step 3b holds a second, independent copy of {@link #GATE}. Two copies of one
     * rule drift, and this drift is invisible from either side: this class can report the tree fully
     * scanned while the run summary an operator actually reads says "disabled". Pin them textually.
     */
    @Test
    void theWorkflowsCopyOfTheGatePatternIsTextuallyIdentical() {
        String workflow = read(repoRoot().resolve(".github/workflows/docker-build.yml"));
        Matcher declaration = WORKFLOW_GATE_DECLARATION.matcher(workflow);

        assertTrue(declaration.find(),
                ".github/workflows/docker-build.yml no longer declares GATE = re.compile(r'...'), so "
                        + "step 3b's scanner can no longer be compared with this one and the two are "
                        + "free to drift unseen");
        assertEquals(GATE.pattern(), declaration.group(1),
                "the workflow's gate pattern and this class's have drifted. They answer the same "
                        + "question — which classes are waiting on which credential — in two places, "
                        + "and only one of the two is what an operator reads on the run summary.");
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

    /**
     * The variable names an {@code @EnabledIfEnvironmentVariable} scan yields <b>on its own</b>, with
     * no {@code System.getenv} contribution mixed in. {@link #rigs(List)} deliberately unions the
     * two, which is right for asking "what does this rig depend on" and wrong for asking "can the
     * annotation scan see this gate at all" — the union answers yes for a gate the pattern misses
     * whenever the class happens to read the same name a second way.
     */
    private static Set<String> gateNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = GATE.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** A named test class's source file, or an assertion failure — an absent file is not a pass. */
    private static Path testSource(String className) {
        return testSources().stream()
                .filter(path -> path.getFileName().toString().equals(className + ".java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(className + ".java is no longer under "
                        + "src/test/java: this check has nothing to measure, which is not the same "
                        + "thing as passing"));
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
