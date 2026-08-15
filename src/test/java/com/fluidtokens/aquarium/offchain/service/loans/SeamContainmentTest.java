package com.fluidtokens.aquarium.offchain.service.loans;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Mechanically enforces what {@link LiquidateTransactionBuilder#buildIgnoringPositiveEquityVeto}'s
 * javadoc asks reviewers to remember: the seam that skips V8's positive-equity veto is
 * package-private, has no production caller, and must stay that way. Reaching for it from production
 * code would build a transaction with positive equity in the equity output — a transaction the
 * <em>currently deployed</em> validators cannot both accept, so it dies in phase-2 script evaluation
 * with the fee already spent (see {@link LiquidateTransactionBuilder.Refusal#POSITIVE_EQUITY_UNSUPPORTED}).
 * <p>
 * Scans {@code src/main/java} only — the seam's 13 test-side call sites (across
 * {@code LiquidateDryEvalTest} and {@code LiquidateTransactionBuilderTest}) are the seam working as
 * designed and are out of scope by construction.
 */
class SeamContainmentTest {

    private static final String SEAM_TOKEN = "buildIgnoringPositiveEquityVeto";
    private static final String DECLARING_FILE = "LiquidateTransactionBuilder.java";
    private static final Path MAIN_JAVA_ROOT = Path.of("src/main/java");

    @Test
    void seamIsReferencedOnlyFromItsDeclaringFile() throws IOException {
        List<Path> filesContainingToken = new ArrayList<>();
        boolean[] foundDeclaringFile = {false};

        try (Stream<Path> paths = Files.walk(MAIN_JAVA_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        if (p.getFileName().toString().equals(DECLARING_FILE)) {
                            foundDeclaringFile[0] = true;
                        }
                        if (content.contains(SEAM_TOKEN)) {
                            filesContainingToken.add(p);
                        }
                    });
        }

        // A broken scan root would make the assertion below vacuously pass — it would find zero
        // references and conclude "only the declaring file", having looked at nothing. Prove the
        // walk actually reached the file the seam is declared in before trusting that conclusion.
        assertTrue(foundDeclaringFile[0],
                "scan of " + MAIN_JAVA_ROOT + " never reached " + DECLARING_FILE
                        + " — the scan root is broken and this test would be vacuously green");

        List<String> offendingPaths = filesContainingToken.stream()
                .filter(p -> !p.getFileName().toString().equals(DECLARING_FILE))
                .map(Path::toString)
                .sorted()
                .toList();

        if (!offendingPaths.isEmpty()) {
            fail(("%s must only be referenced from %s, but it also appears in: %s. Reaching for this "
                    + "seam from production code builds a transaction with positive equity that the "
                    + "currently deployed validators cannot both accept — it dies in phase-2 script "
                    + "evaluation with the fee already spent.")
                    .formatted(SEAM_TOKEN, DECLARING_FILE, offendingPaths));
        }
    }
}
