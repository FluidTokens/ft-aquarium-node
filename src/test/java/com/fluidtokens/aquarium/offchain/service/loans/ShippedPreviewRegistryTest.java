package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinates anything on-chain must use — <b>read from what ships, not typed here.</b>
 *
 * <h2>What this exists to stop</h2>
 * {@code LoanFactoryOnChainRunnerTest} builds real loans on preview and was pointed, through
 * {@code LoanFixtures.registry()}, at the <b>THIRD</b> deployment — superseded on 2026-08-25.
 * Every derived credential hangs off the config policy id, so a loan it created would have landed
 * at addresses {@code TankUtxoStorage} does not index. <b>The bot would have reported
 * {@code 0 live bonds}, and the two natural readings of that are "the experiment failed" and "the
 * indexer is broken" — neither of which is true.</b> A false negative that points at the wrong
 * subsystem costs more than a plain failure.
 *
 * <h2>⚠ Why the value is parsed rather than pinned</h2>
 * A constant typed in a test drifts at the next redeploy exactly as the last one did, and nothing
 * fails when it does. <b>A value read from the shipped config cannot disagree with what ships.</b>
 * The reminder is the fallback; the generator is the fix.
 */
class ShippedPreviewRegistryTest {

    private static final LoansContractRegistry SHIPPED = LoanFixtures.shippedPreviewRegistry();

    /**
     * ⛔ THE ASSERTION THAT MATTERS: the parsed policy id is the one the running pod is pinned to.
     * Checked against the value in {@code application.yaml}'s preview profile, which is what the
     * image carries — not against a constant re-typed in this test, which could drift with it.
     */
    @Test
    void theShippedRegistryIsTheFourthDeploymentThePodRuns() {
        assertEquals("d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e",
                SHIPPED.getConfigPolicyId(),
                "the parsed config policy id is not the fourth deployment — either application.yaml "
                        + "moved (in which case this expectation must move with a redeploy note) or "
                        + "the parser picked up the wrong profile");
        assertEquals("a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13",
                SHIPPED.getLmConfigPolicyId());
    }

    /**
     * ⛔ <b>AND THE ONE THAT KEEPS THE TWO HONEST — inverted on 2026-09-04, when Giovanni ruled the
     * re-point.</b>
     *
     * <h2>What it used to assert, and why that reversed rather than disappeared</h2>
     * Until the re-point this asserted the two registries were <b>different</b>: the fixture registry
     * was pinned to the THIRD deployment while this parsed the FOURTH, and the risk being guarded was
     * that the on-chain runner silently fell back onto the dead one. <b>Now they are the same
     * deployment, and the risk points the other way</b> — the constant can go stale at the next
     * redeploy while the parsed value moves by itself, and nothing would fail.
     *
     * <p>⇒ <b>So the assertion is that they AGREE</b>, and it is the same guard doing the same job
     * from the other side: {@link LoanFixtures#shippedPreviewRegistry()} is a <b>generator</b> that
     * cannot disagree with what ships, {@link LoanFixtures#registry()} is a <b>constant</b>, and this
     * is what makes the constant's staleness loud instead of silent. <b>Do not "simplify" it by
     * deleting one of them</b>: a constant compared against nothing is exactly what drifted last time.
     */
    @Test
    void theFixtureRegistryAgreesWithWhatShips() {
        assertEquals(SHIPPED.getConfigPolicyId(), LoanFixtures.registry().getConfigPolicyId(),
                "the fixture registry and the shipped config have diverged. If application.yaml was "
                        + "re-pointed at a FIFTH deployment, LoanFixtures.CONFIG_POLICY_ID and the "
                        + "recorded config datums must move with it — otherwise every dry-eval rig is "
                        + "back to validating a blueprint against a datum from a different build, "
                        + "which is findings §53 all over again");
        assertEquals(SHIPPED.getLmConfigPolicyId(), LoanFixtures.registry().getLmConfigPolicyId());
    }

    /**
     * ⚠ And the THIRD deployment is still reachable, deliberately — the pool-origination rigs replay
     * reference-script UTxOs that FluidTokens published for that deployment and never republished.
     * See {@link LoanFixtures#thirdDeploymentRegistry()}. <b>It must not silently become the fourth</b>,
     * or those rigs would look for coordinates that do not exist.
     */
    @Test
    void theThirdDeploymentRegistryIsStillDistinctAndStillTheThird() {
        assertNotEquals(LoanFixtures.thirdDeploymentRegistry().getConfigPolicyId(),
                SHIPPED.getConfigPolicyId(),
                "the third-deployment fixture registry must stay distinct from what ships");
        assertEquals("c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9",
                LoanFixtures.thirdDeploymentRegistry().getConfigPolicyId());
    }
}
