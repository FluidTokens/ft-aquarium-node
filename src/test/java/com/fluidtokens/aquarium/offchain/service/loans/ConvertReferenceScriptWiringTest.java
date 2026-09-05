package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>{@code ConvertLiquidationRouter} passed {@code Map.of()} to the builder, and that literal was
 * the whole reason a convert could not be submitted.</b>
 *
 * <h2>What it cost</h2>
 * Every other path supplies the operator's coordinates — {@code LiquidationExecutor:1048}, the
 * pay-in-advance router, {@code CompoundExecutor:312}. <b>Convert alone discarded them</b>, so all six
 * of its validators travelled inline: <b>20,270 bytes of script against a 16,384 {@code max_tx_size}</b>,
 * from the published mainnet sizes — {@code loan} 2,547 · {@code loan-spend} 1,158 ·
 * {@code lender-manager} 968 · {@code lender-manager-spend} 1,158 · {@code loan-claim-action} 8,662 ·
 * {@code lm-liquidate-and-convert-action} 5,777.
 *
 * <p>⚠ <b>Nothing constructed this router in the whole suite</b>, which is exactly how a literal
 * survives: the builders were tested, the economics were tested, and the seam between them was not.
 *
 * <h2>Why the map is keyed by the REGISTRY's hashes</h2>
 * {@code LoansReferenceScriptVerifier} resolves every configured coordinate at startup and hard-fails
 * on a mismatch, so by the time this runs, "the slot named {@code loan-spend} publishes the loan-spend
 * script" is already proven. The compound path reads hashes off chain instead because its coordinates
 * are an unnamed list, where a mislabelled entry would otherwise be inexpressible.
 */
class ConvertReferenceScriptWiringTest {

    /**
     * The mainnet deployment, so the derived hashes are the ones an operator's node really uses.
     *
     * <p>⚠ <b>The three Minswap coordinates are not optional here.</b> Without them
     * {@code lmLiquidateAndConvertActionScriptHash} is {@code null} — the convert action is the one
     * hash that needs parameters from OUTSIDE this deployment — and its slot would then be dropped
     * from the map silently, referencing five scripts where six were configured. That is asserted
     * on its own below rather than left as a fixture detail.
     */
    private static LoansContractRegistry registry() {
        return new LoansContractRegistry(
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa",
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");
    }

    /** A registry with NO Minswap coordinates — the convert action cannot be derived at all. */
    private static LoansContractRegistry registryWithoutMinswap() {
        return new LoansContractRegistry(
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa");
    }

    private static TransactionInput at(String tag) {
        return new TransactionInput(tag.repeat(64).substring(0, 64), 0);
    }

    /** All nine slots configured, so the test can assert which SIX are selected. */
    private static AppConfig.LiquidationConfiguration allNineConfigured() {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30,
                BigInteger.ZERO, 200, 30,
                new LiquidateTransactionBuilder.ReferenceScripts(
                        at("1"), at("2"), at("3"), at("4"), at("5"), at("6"), at("7"), at("8"), at("9")));
    }

    private static ConvertLiquidationRouter router(AppConfig.LiquidationConfiguration configuration) {
        return router(registry(), configuration);
    }

    private static ConvertLiquidationRouter router(LoansContractRegistry registry,
                                                   AppConfig.LiquidationConfiguration configuration) {
        return new ConvertLiquidationRouter(registry, new AppConfig.LoansConfiguration(),
                configuration, null, null, null, null, null);
    }

    // ==========================================================================================

    /**
     * ⛔ THE REGRESSION GUARD. With coordinates configured the map must not be empty — which is all
     * {@code Map.of()} ever produced, whatever the operator set.
     */
    @Test
    void theConfiguredCoordinatesReachTheBuilder() {
        Map<String, TransactionInput> refs = router(allNineConfigured()).referenceScripts();

        assertFalse(refs.isEmpty(),
                "the router discarded the operator's reference scripts, so every validator travels "
                        + "inline: 20,270 bytes against a 16,384 max_tx_size, and the convert cannot "
                        + "be submitted however it is configured");
    }

    /**
     * ⛔ <b>AND THAT THE MAP IS ACTUALLY PASSED — because every assertion above survives a revert of
     * the one word that mattered.</b>
     *
     * <p>Measured while writing this: restoring {@code Map.of()} at the builder call site left the
     * rest of this class GREEN. It exercises {@code referenceScripts()} and says nothing about
     * whether anyone calls it — <b>the same blindness that let the literal live in the first place</b>,
     * reproduced one layer up. A method that builds the right answer and a request that carries it are
     * two claims, and only the second is the bug.
     *
     * <p>⚠ Asserted on the SOURCE, and deliberately: driving the real call site needs a built
     * transaction, which needs a live evaluator, a resolved pool and an oracle feed — a rig that
     * supplies what production must earn. The precedent is this package's own convert-routing check.
     * <b>A source assertion is a weak instrument used where it is the honest one</b>: it cannot see
     * whether the map is correct — the tests above do that — only that it is not thrown away.
     */
    @Test
    void theBuilderCallSitePassesTheMapRatherThanAnEmptyLiteral() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/"
                        + "ConvertLiquidationRouter.java"));

        int request = source.indexOf("new ConvertTransactionBuilder.Request(");
        assertTrue(request > 0, "the builder call site moved; this assertion is now about nothing");
        String call = source.substring(request, source.indexOf("));", request));

        assertTrue(call.contains("referenceScripts()"),
                "the convert builder is not being given the configured reference scripts. Every "
                        + "validator then travels inline — 20,270 bytes against a 16,384 max_tx_size "
                        + "— and no configuration can make a convert submittable. The call site reads:\n"
                        + call);
        assertFalse(call.contains("Map.of()"),
                "Map.of() is back at the builder call site. That literal is the defect this class "
                        + "exists for:\n" + call);
    }

    /**
     * ⚠ EXACTLY the six {@code ConvertTransactionBuilder.referencedScripts} can reference — no more.
     * Anything else would still be added as a reference input by {@code referenceInputs()} and charged
     * the Conway per-byte reference-script fee for a script no redeemer invokes. The three that belong
     * to the other routes are asserted ABSENT, not merely unlisted.
     */
    @Test
    void exactlyTheSixTheConvertBuilderCanReference() {
        LoansContractRegistry registry = registry();
        Map<String, TransactionInput> refs = router(allNineConfigured()).referenceScripts();

        assertEquals(Set.of(
                        registry.getLoanPolicyId(),
                        registry.getLoanSpendScriptHash(),
                        registry.getLenderManagerWithdrawScriptHash(),
                        registry.getLenderManagerSpendScriptHash(),
                        registry.getLoanClaimActionScriptHash(),
                        registry.getLmLiquidateAndConvertActionScriptHash()),
                refs.keySet(),
                "the map must be keyed by SCRIPT HASH and hold exactly the six the convert builder "
                        + "considers");

        for (String otherRoute : new String[]{registry.getAssetManagerSpendScriptHash(),
                registry.getLmLiquidateActionScriptHash(),
                registry.getLmLiquidateAndPayInAdvanceActionScriptHash()}) {
            assertFalse(refs.containsKey(otherRoute),
                    "a script convert never invokes must not be referenced: it is charged by the byte "
                            + "and buys nothing (" + otherRoute + ")");
        }
    }

    /**
     * ⚑ Each slot maps to ITS OWN validator's hash. A transposition — {@code loanSpend}'s coordinate
     * filed under the loan policy's hash — produces a map of the right size and shape whose entries
     * are all wrong, and the builder would then reference a script for the wrong redeemer.
     */
    @Test
    void everySlotIsFiledUnderItsOwnValidatorsHash() {
        LoansContractRegistry registry = registry();
        Map<String, TransactionInput> refs = router(allNineConfigured()).referenceScripts();

        assertEquals(at("1"), refs.get(registry.getLoanPolicyId()));
        assertEquals(at("2"), refs.get(registry.getLoanSpendScriptHash()));
        assertEquals(at("3"), refs.get(registry.getLenderManagerWithdrawScriptHash()));
        assertEquals(at("4"), refs.get(registry.getLenderManagerSpendScriptHash()));
        assertEquals(at("5"), refs.get(registry.getLoanClaimActionScriptHash()));
        assertEquals(at("9"), refs.get(registry.getLmLiquidateAndConvertActionScriptHash()));
    }

    /**
     * ⛔ An UNCONFIGURED slot is absent, never present-but-null. A referenced-but-absent script is
     * {@code RequiredRedeemersMismatch} (CCL trap 13); travelling inline is correct and merely larger.
     */
    @Test
    void anUnconfiguredSlotIsAbsentRatherThanNull() {
        AppConfig.LiquidationConfiguration none = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30,
                BigInteger.ZERO, 200, 30,
                LiquidateTransactionBuilder.ReferenceScripts.none());

        Map<String, TransactionInput> refs = router(none).referenceScripts();

        assertTrue(refs.isEmpty(),
                "nothing configured must mean nothing referenced — not a map of null values");
    }

    /**
     * ⛔ <b>NO Minswap coordinates ⇒ the convert action cannot be derived ⇒ its slot is dropped, even
     * though the operator configured it.</b> The result is five referenced where six were asked for,
     * and the convert action travels inline — 5,777 bytes that were meant to be referenced. Silent,
     * because a null hash is exactly what an unconfigured slot looks like from here.
     *
     * <p>⇒ {@code LoansConfigVerifier} reports this at boot as {@code CONVERT UNAVAILABLE}. Asserted
     * so the coupling is recorded: <b>the Minswap configuration is a prerequisite for the convert
     * reference script, not only for the swap.</b>
     */
    @Test
    void withoutMinswapCoordinatesTheConvertActionSlotCannotBeFiled() {
        Map<String, TransactionInput> refs =
                router(registryWithoutMinswap(), allNineConfigured()).referenceScripts();

        assertEquals(5, refs.size(),
                "the convert action's hash is null without the Minswap parameters, so its configured "
                        + "coordinate has nowhere to be filed: " + refs.keySet());
    }

    /**
     * ⚠ And the partial case, which is the one an operator actually reaches: the convert action is the
     * only slot with no {@code AQUARIUM_LIQUIDATION_REF_*} alias, so it is the one most likely left
     * unset. The other five must still be referenced rather than the map collapsing to empty.
     */
    @Test
    void theConvertActionAloneBeingUnsetStillReferencesTheOtherFive() {
        AppConfig.LiquidationConfiguration partial = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30,
                BigInteger.ZERO, 200, 30,
                new LiquidateTransactionBuilder.ReferenceScripts(
                        at("1"), at("2"), at("3"), at("4"), at("5"), at("6"), at("7"), at("8"), null));

        Map<String, TransactionInput> refs = router(partial).referenceScripts();

        assertEquals(5, refs.size(), "five referenced, the convert action inline: " + refs.keySet());
        assertFalse(refs.containsKey(registry().getLmLiquidateAndConvertActionScriptHash()));
    }
}
