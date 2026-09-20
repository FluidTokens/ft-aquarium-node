package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The node must not refuse to boot over a validator it never calls.</b>
 *
 * <p>2026-09-19, mainnet: FluidTokens updated the ConfigDatum in place, moving three pool-side
 * hashes. Every operator's node then failed startup — including the scheduled-transaction half,
 * which has nothing to do with lending — over validators with <b>zero call sites in this
 * repository</b>. Their deployment was mid-flight while it happened: the config named
 * {@code pool_sell_lender_position} {@code fc16ccaa…}, which did not exist on chain in any form.
 *
 * <p>{@link LoansConfigVerifier.Findings} splits the check. This class holds the split honest from
 * both ends: the classification must match what the code actually uses, and the severities must
 * actually behave differently.
 */
class AdvisoryConfigFieldsTest {

    /**
     * ⚠ <b>The whole point: these are advisory BECAUSE nothing uses them.</b> Not because they feel
     * unimportant. {@link #anAdvisoryFieldHasNoCallSiteOutsideTheRegistryAndVerifier()} re-derives
     * that from the sources on every run, so wiring one of these up fails here and tells you to
     * promote it — rather than leaving a newly load-bearing field degraded to a warning.
     */
    private static final List<String> ADVISORY_ACCESSORS = List.of(
            "getRequestPolicyId",
            "getLoanRepayActionScriptHash",
            "getLoanChangeCollateralActionScriptHash",
            "getLoanRecastActionScriptHash",
            "getPoolCancelActionScriptHash",
            "getPoolBorrowActionScriptHash",
            "getPoolSellLenderPositionActionScriptHash",
            "getPoolEditActionScriptHash",
            "getLmWithdrawBondsActionScriptHash",
            "getLmLiquidatePayInAdvanceAndCompoundActionScriptHash",
            "getLmLiquidateConvertAndCompoundActionScriptHash");

    /** Derived and compared, never invoked — so a wrong value costs this node nothing. */
    private static final List<String> OWNS_THE_CLASSIFICATION = List.of(
            "LoansContractRegistry.java", "LoansConfigVerifier.java");

    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static String fixture(String name) throws IOException {
        try (InputStream in = AdvisoryConfigFieldsTest.class.getResourceAsStream("/loans-v4/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static LoansConfigVerifier verifier() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return new LoansConfigVerifier(
                new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, null, null, null),
                SMART, network, null, true);
    }

    @Test
    void anAdvisoryFieldHasNoCallSiteOutsideTheRegistryAndVerifier() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> wired = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            List<Path> sources = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> OWNS_THE_CLASSIFICATION.stream()
                            .noneMatch(owner -> f.getFileName().toString().equals(owner)))
                    .toList();
            for (String accessor : ADVISORY_ACCESSORS) {
                for (Path source : sources) {
                    if (Files.readString(source).contains("." + accessor + "()")) {
                        wired.add(accessor + " is now called from " + source.getFileName());
                    }
                }
            }
        }
        assertTrue(wired.isEmpty(),
                "These fields are classified ADVISORY — a chain mismatch in them only WARNS — on the "
                        + "grounds that nothing reads them. Something now does, so a wrong value can "
                        + "reach a transaction while the node boots cleanly. Move each one back to the "
                        + "enforced map in LoansConfigVerifier and delete it from ADVISORY_ACCESSORS: "
                        + wired);
    }

    @Test
    void aMismatchInAValidatorWeNeverCallIsAdvisoryAndDoesNotGroundTheNode() throws IOException {
        // ConfigDatum[23], pool_borrow_action -- one of the three FluidTokens actually moved.
        String published = "6ee66a5e77cad44471b366644586daae9c48a78ee4cf08d790cc03d4";
        String corrupted = "6ee66a5e77cad44471b366644586daae9c48a78ee4cf08d790cc0000";
        String mutant = fixture("mainnet-config-datum.hex").replace(published, corrupted);

        LoansConfigVerifier.Findings findings =
                verifier().verifyAgainstBySeverity(mutant, fixture("mainnet-lm-config-datum.hex"));

        assertEquals(List.of(), findings.enforced(),
                "a pool-side validator this node never invokes must not be fatal: " + findings.enforced());
        assertEquals(1, findings.advisory().size(), "expected exactly one advisory finding: " + findings.advisory());
        assertTrue(findings.advisory().getFirst().contains("ConfigDatum[23]"),
                "the warning must name the field: " + findings.advisory());
    }

    @Test
    void aMismatchInSomethingWeDoUseIsStillFatal() throws IOException {
        // ConfigDatum[2], the pool policy id -- five call sites, and it decides what gets indexed.
        String published = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce0f7";
        String corrupted = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce000";
        String mutant = fixture("mainnet-config-datum.hex").replace(published, corrupted);

        LoansConfigVerifier.Findings findings =
                verifier().verifyAgainstBySeverity(mutant, fixture("mainnet-lm-config-datum.hex"));

        assertEquals(List.of(), findings.advisory(),
                "the pool policy is not advisory: " + findings.advisory());
        assertEquals(1, findings.enforced().size(), "expected exactly one enforced finding: " + findings.enforced());
        assertTrue(findings.enforced().getFirst().contains("ConfigDatum[2]"),
                "the failure must name the field: " + findings.enforced());
    }

    /**
     * ⛔ <b>The actual incident, as a test.</b> This is the live datum that grounded every operator's
     * node on 2026-09-19 — not a mutation of ours, but the bytes FluidTokens put on chain. The node
     * must start against it, reporting the three pool-side fields and nothing else.
     */
    @Test
    void theLiveDatumThatGroundedEveryNodeIsAdvisoryOnly() throws IOException {
        LoansConfigVerifier.Findings findings = verifier().verifyAgainstBySeverity(
                fixture("mainnet-config-datum-2026-09-19.hex"), fixture("mainnet-lm-config-datum.hex"));

        assertEquals(List.of(), findings.enforced(),
                "FluidTokens' 2026-09-19 in-place config update touched only validators this node "
                        + "never invokes, so it must NOT stop the node booting: " + findings.enforced());
        assertEquals(3, findings.advisory().size(),
                "expected exactly the three pool-side fields: " + findings.advisory());
        for (String field : List.of("ConfigDatum[23]", "ConfigDatum[24]", "ConfigDatum[26]")) {
            assertTrue(findings.advisory().stream().anyMatch(m -> m.contains(field)),
                    field + " must be reported: " + findings.advisory());
        }
    }

    @Test
    void theUnmutatedMainnetDatumsAreCleanInBothBuckets() throws IOException {
        LoansConfigVerifier.Findings findings = verifier().verifyAgainstBySeverity(
                fixture("mainnet-config-datum.hex"), fixture("mainnet-lm-config-datum.hex"));
        assertTrue(findings.isEmpty(), "the shipped mainnet fixtures must verify clean: " + findings);
    }
}
