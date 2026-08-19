package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@link LiquidateTransactionBuilder.Refusal} constants that
 * {@link UnreachableFromScannedBatch} documents as unreachable when a batch comes from a real
 * {@link LiquidationCandidateScanner} scan. All three assertions have to hold together for the
 * marking to mean anything: the set has to be exactly right (a), the marked check has to still
 * exist in the builder (b), and the claimed reason has to point at something real (c).
 */
class UnreachableVetoMarkingTest {

    /**
     * The constants currently marked. Deliberately spelled out as literals rather than derived from
     * the source, so an accidental annotation addition or removal changes this test's answer
     * independently of the production code.
     * <p>
     * <b>It was six; it is now four.</b> Positive-equity liquidation removed two of them, and for two
     * different reasons:
     * <ul>
     *   <li>{@code POSITIVE_EQUITY_UNSUPPORTED} — the constant no longer exists. It was the V8 veto,
     *       and V8 is gone.</li>
     *   <li>{@code REPAYMENT_RECEIPTS_WITH_EQUITY} — the constant is still there and still refuses,
     *       but it is <b>no longer unreachable</b>. Its marking claimed the scanner filtered it out
     *       via {@code LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED}; that exclusion is gone, so a
     *       {@code repaymentReceipts = True} loan with a positive equity now reaches the builder for
     *       real. Leaving the annotation would have been a claim of unreachability that the scanner no
     *       longer backs.</li>
     * </ul>
     */
    private static final Set<String> EXPECTED_ANNOTATED = Set.of(
            "EQUITY_IN_PRINCIPAL_CURRENCY",
            "CONVERSION_TO_PRINCIPAL_REQUIRED",
            "UNSUPPORTED_ORACLE_VARIANT",
            "CHARLIE_PROVIDER_REFERENCE_INPUT_MISSING");

    private static final Path BUILDER_SOURCE =
            Path.of("src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidateTransactionBuilder.java");

    @Test
    void exactlyTheSixDocumentedConstantsAreAnnotated() {
        Set<String> actuallyAnnotated = new LinkedHashSet<>();
        for (LiquidateTransactionBuilder.Refusal refusal : LiquidateTransactionBuilder.Refusal.values()) {
            Field field = enumConstantField(refusal);
            if (field.isAnnotationPresent(UnreachableFromScannedBatch.class)) {
                actuallyAnnotated.add(refusal.name());
            }
        }
        assertEquals(EXPECTED_ANNOTATED, actuallyAnnotated,
                "the set of Refusal constants carrying @UnreachableFromScannedBatch changed; either "
                        + "this test's expected set or the annotations on LiquidateTransactionBuilder.Refusal "
                        + "are out of sync");
    }

    @Test
    void everyAnnotatedConstantIsStillCheckedInVet() throws IOException {
        String source = stripComments(Files.readString(BUILDER_SOURCE));
        for (String constantName : EXPECTED_ANNOTATED) {
            // refuse(Refusal.X, ...) — allow any whitespace between the tokens, but require the
            // constant to be in refuse()'s first argument position, not merely mentioned somewhere.
            // Matched against comment-stripped source, so a decoy comment mentioning the call site
            // cannot stand in for the call site actually existing.
            Pattern refuseCallSite = Pattern.compile(
                    "refuse\\(\\s*Refusal\\." + Pattern.quote(constantName) + "\\s*,");
            assertTrue(refuseCallSite.matcher(source).find(),
                    ("Refusal.%s is annotated @UnreachableFromScannedBatch but LiquidateTransactionBuilder.java "
                            + "no longer has a refuse(Refusal.%s, ...) call site — the check it claims is "
                            + "unreachable-but-present has been deleted").formatted(constantName, constantName));
        }
    }

    /**
     * Removes {@code // ...} line comments and {@code /* ... *}{@code /} block comments from Java
     * source, leaving string and character literals — including any {@code //} or {@code /*} they
     * happen to contain — untouched. Without this, a decoy comment naming a deleted call site (e.g.
     * {@code // formerly refuse(Refusal.X, ...) here}) would make
     * {@link #everyAnnotatedConstantIsStillCheckedInVet()} pass even though the enforcement code is
     * gone — exactly the failure mode task 6(b) exists to catch.
     */
    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        result.append('\n');
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                result.append(c);
                i++;
                while (i < length && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\' && i + 1 < length) {
                        result.append(source.charAt(i));
                        i++;
                    }
                    if (i < length) {
                        result.append(source.charAt(i));
                        i++;
                    }
                }
                if (i < length) {
                    result.append(source.charAt(i));
                    i++;
                }
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    @Test
    void everyScannerFilterNamesSomethingReal() {
        Set<String> exclusionNames = Arrays.stream(LiquidationExclusion.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());

        for (LiquidateTransactionBuilder.Refusal refusal : LiquidateTransactionBuilder.Refusal.values()) {
            Field field = enumConstantField(refusal);
            UnreachableFromScannedBatch annotation = field.getAnnotation(UnreachableFromScannedBatch.class);
            if (annotation == null) {
                continue;
            }
            String scannerFilter = annotation.scannerFilter();
            if (scannerFilter.equals("OracleEntry.usableForLiquidation")) {
                continue;
            }
            String prefix = "LiquidationExclusion.";
            assertTrue(scannerFilter.startsWith(prefix),
                    "Refusal.%s's scannerFilter %s is neither \"OracleEntry.usableForLiquidation\" nor a "
                            + "LiquidationExclusion.* reference".formatted(refusal.name(), scannerFilter));
            String exclusionName = scannerFilter.substring(prefix.length());
            assertTrue(exclusionNames.contains(exclusionName),
                    "Refusal.%s's scannerFilter names LiquidationExclusion.%s, which does not exist"
                            .formatted(refusal.name(), exclusionName));
        }
    }

    private static Field enumConstantField(LiquidateTransactionBuilder.Refusal refusal) {
        try {
            return LiquidateTransactionBuilder.Refusal.class.getField(refusal.name());
        } catch (NoSuchFieldException e) {
            throw new AssertionError("enum constant field vanished for " + refusal, e);
        }
    }
}
