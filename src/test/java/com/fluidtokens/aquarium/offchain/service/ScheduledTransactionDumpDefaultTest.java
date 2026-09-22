package com.fluidtokens.aquarium.offchain.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A TRIPWIRE FOR A DELIBERATE HACK, so that removing it is a build failure rather than a
 * thing someone has to remember.</b>
 *
 * <p>{@code scheduling.transaction-processor.dump-cbor} defaults to {@code true} on this branch.
 * That is sanctioned — Giovanni runs the node on Kubernetes, where adding an environment variable
 * means a Helm chart change, so a diagnostic that must be switched on would never be switched on.
 * While it stands, <b>the processor builds transactions and submits none of them.</b>
 *
 * <p>⚠ <b>The danger is not the flag; it is that the flag looks finished.</b> The code reads as
 * correct, the suite is green, and the one thing wrong with it is invisible in a diff: a node
 * running this default processes nothing at all, silently, forever. A comment saying "revert this"
 * is read by whoever is already looking at the line. <b>This test is read by everyone.</b>
 *
 * <p>⇒ The commit that fixes the decode failure restores {@code :false} and <b>deletes this
 * file</b>. Until then it is a standing refusal to let the branch merge quietly.
 */
class ScheduledTransactionDumpDefaultTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/ScheduledTransactionService.java");

    /**
     * ⛔ <b>THE FILE THAT ACTUALLY DECIDES, and the reason this test exists in its second version.</b>
     *
     * <p>The first version asserted only on the {@code @Value} annotation's default. That default is
     * <b>dead code</b>: {@code application.yaml} declares the same property, so Spring resolves it
     * there and never falls back to the annotation. The hack was flipped in Java, the suite went
     * green, the image shipped — and <b>the node ran with the dump still off</b>, producing exactly
     * the symptom it was meant to explain.
     *
     * <p>⚠ <b>Neither file was wrong on its own.</b> That is what made it invisible: a reviewer
     * reading the annotation sees {@code :true}, a reviewer reading the yaml sees a property that is
     * obviously wired, and only the two together say what the running node does. <b>When a default
     * exists in two places, assert the one with precedence</b> — the other is documentation.
     */
    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");

    @Test
    void theCborDumpDefaultIsStillTheDeliberateBranchHackAndIsStillDeclaredAsOne() throws IOException {
        String source = Files.readString(SERVICE);

        boolean hackIsOn = source.contains("${scheduling.transaction-processor.dump-cbor:true}");
        boolean hackIsOff = source.contains("${scheduling.transaction-processor.dump-cbor:false}");

        assertTrue(hackIsOn ^ hackIsOff,
                "the dump-cbor default must be exactly one of true (the branch hack) or false "
                        + "(production) -- neither found, so the property was renamed or removed "
                        + "and this tripwire is no longer watching anything");

        if (hackIsOff) {
            throw new AssertionError(
                    "dump-cbor is back to its production default, which is the RIGHT change -- but "
                            + "this test exists only to guard the hack, so delete this file in the "
                            + "same commit. Leaving it behind is a test that can never fail again.");
        }

        String config = Files.readString(CONFIG);
        assertTrue(config.contains("SCHEDULING_TRANSACTION_PROCESSOR_DUMP_CBOR:true}"),
                "application.yaml OVERRIDES the @Value default, so the annotation saying :true "
                        + "means nothing on its own -- the yaml default is what the running node "
                        + "uses. Flip it there too, or the dump is off in production while every "
                        + "file involved looks correct");

        assertTrue(source.contains("MUST NOT REACH {@code main}"),
                "the hack is on, so the javadoc must still say plainly that it cannot ship. A "
                        + "diagnostic default that loses its warning is how a node ends up quietly "
                        + "submitting nothing in production");
    }
}
