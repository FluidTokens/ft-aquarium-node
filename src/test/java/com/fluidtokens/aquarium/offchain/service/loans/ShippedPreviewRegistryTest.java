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
     * ⛔ AND THE ONE THAT PROVES THE RE-POINT ACTUALLY MOVED SOMETHING. If these ever coincide, the
     * shipped registry has silently become the fixture registry again and the on-chain runner is
     * back on a dead deployment with nothing failing.
     */
    @Test
    void theShippedRegistryIsNotTheThirdDeploymentFixtureRegistry() {
        assertNotEquals(LoanFixtures.registry().getConfigPolicyId(), SHIPPED.getConfigPolicyId(),
                "the on-chain registry must NOT be the third-deployment fixture registry");
        assertEquals("c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9",
                LoanFixtures.registry().getConfigPolicyId(),
                "and the fixture registry is deliberately still the THIRD deployment — 25 test files "
                        + "replay recorded third-deployment data through it, and re-pointing it is the "
                        + "open decision about the 37 that belongs to Giovanni, not to this change");
    }

    /**
     * The derived hashes must actually differ between the two, or the distinction is cosmetic. This
     * is the measurable form of "25 of 28 derived script hashes move on a re-mint alone".
     */
    @Test
    void theTwoDeploymentsDeriveDifferentCredentials() {
        assertNotEquals(LoanFixtures.registry().getLoanSpendScriptHash(),
                SHIPPED.getLoanSpendScriptHash(),
                "if the loan spend credential is identical the re-point changes nothing that matters");
        assertNotEquals(LoanFixtures.registry().getLenderManagerSpendScriptHash(),
                SHIPPED.getLenderManagerSpendScriptHash());
        assertTrue(SHIPPED.indexedPaymentCredentials().size() >= 6,
                "a usable registry must derive the credentials the indexer scopes on");
    }
}
