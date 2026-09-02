package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production wiring, asserted on the production wiring.
 *
 * <h2>Why this test exists</h2>
 * Every other test of the script-cost evaluator supplies its own. That leaves the one thing that
 * actually protects the armed bot — the argument {@link YaciConfig#liquidateTransactionBuilder} passes
 * — asserted nowhere: drop it, and the whole suite stays green while the running bot goes back to
 * declaring 10000-mem redeemers, landing transactions that exhaust their budget on chain and forfeit
 * the borrower's collateral. A defect whose removal no test notices is a defect waiting to be
 * reintroduced by the next refactor.
 * <p>
 * So this calls the {@code @Bean} method directly and reads the field it wrote. No Spring context, no
 * {@code @SpringBootTest}: the bean method is an ordinary method and is treated as one.
 *
 * <h2>Why it needs no network</h2>
 * {@link BFBackendService}'s constructor only builds Retrofit clients — it performs no request — so an
 * unreachable base URL and a dummy project id are enough to construct one. Nothing here calls it: the
 * assertion is about which object the builder was handed, not about what that object answers. The URL
 * is deliberately unresolvable so that a future change which does make this path talk to a backend
 * fails loudly here instead of quietly reaching the real Blockfrost.
 */
class YaciConfigWiringTest {

    /** Unresolvable on purpose: constructing a backend service must not be constructing a connection. */
    private static final String OFFLINE_BLOCKFROST = "https://example.invalid/api/v0/";

    @Test
    void theProductionBuilderBeanIsGivenAScriptCostEvaluator() throws Exception {
        LiquidateTransactionBuilder builder = new YaciConfig().liquidateTransactionBuilder(
                LoanFixtures.registry(),
                previewNetwork(),
                LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = LiquidateTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);

        assertNotNull(evaluator,
                "YaciConfig built the liquidation transaction builder WITHOUT a script-cost evaluator. "
                        + "Its redeemers would carry cardano-client-lib's 10000-mem placeholders against "
                        + "a measured 2.26M, the mempool would accept the transaction anyway, and it "
                        + "would fail on chain in phase 2 — forfeiting collateral. Pass the evaluator.");
        assertTrue(evaluator instanceof TransactionEvaluator,
                "the builder was given a " + evaluator.getClass().getName()
                        + " where a TransactionEvaluator was asked for");
    }

    /**
     * ⛔ The COMPOUND builder's production bean, asserted the same way and for a sharper reason.
     *
     * <p>The operator's stated case for arming this path is that the exposure is the transaction fee
     * per execution — nothing advanced, nothing acquired. <b>That sentence is true only while the
     * ex-units are measured.</b> Placeholder ex-units move the exposure to the collateral, which is
     * the one way the risk analysis becomes false, so this assertion is the thing keeping it true.
     */
    @Test
    void theProductionCompoundBuilderBeanIsGivenAScriptCostEvaluator() throws Exception {
        CompoundTransactionBuilder builder = new YaciConfig().compoundTransactionBuilder(
                LoanFixtures.shippedPreviewRegistry(),
                previewNetwork(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = CompoundTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);

        assertNotNull(evaluator,
                "YaciConfig built the compound transaction builder WITHOUT a script-cost evaluator. "
                        + "Its redeemers would carry placeholder ex-units against a measured 2.58M mem / "
                        + "941M steps, the mempool would accept it, and it would fail in phase 2 — "
                        + "forfeiting collateral, and falsifying the risk case the path was armed on.");
        assertTrue(evaluator instanceof TransactionEvaluator,
                "the builder was given a " + evaluator.getClass().getName()
                        + " where a TransactionEvaluator was asked for");
    }

    /**
     * The compound builder must also be handed the BackendService, not a bare supplier trio: a
     * transaction carrying reference scripts can only be priced by something that can fetch them
     * (CCL trap 9), and the offline three-argument form cannot.
     */
    @Test
    void theProductionCompoundBuilderCanReachAScriptSupplier() throws Exception {
        CompoundTransactionBuilder builder = new YaciConfig().compoundTransactionBuilder(
                LoanFixtures.shippedPreviewRegistry(), previewNetwork(),
                LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = CompoundTransactionBuilder.class.getDeclaredField("backendService");
        field.setAccessible(true);
        assertNotNull(field.get(builder),
                "the compound builder holds no BackendService, so QuickTxBuilder gets the offline "
                        + "three-argument form and cannot price a referenced script");
    }

    /**
     * And the same wiring must not have smuggled in a submission path. The evaluator the bean builds is
     * a lambda over {@code BFBackendService.getTransactionService().evaluateTx}, and a lambda's own type
     * implements exactly the functional interface — so if this ever became "pass the backend service
     * itself", or "pass the DefaultTransactionProcessor", this assertion is what notices.
     */
    @Test
    void theEvaluatorTheBeanBuildsCannotSubmit() throws Exception {
        LiquidateTransactionBuilder builder = new YaciConfig().liquidateTransactionBuilder(
                LoanFixtures.registry(),
                previewNetwork(),
                LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = LiquidateTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);
        assertNotNull(evaluator, "no evaluator was wired at all — see the sibling test");
        Class<?> evaluatorType = evaluator.getClass();

        assertTrue(java.util.Arrays.stream(evaluatorType.getMethods())
                        .noneMatch(method -> method.getName().toLowerCase().contains("submit")),
                "the object wired as the script-cost evaluator exposes a submit method: " + evaluatorType);
    }

    /**
     * {@link AppConfig.Network} reads its network name from an {@code @Value}-injected private field, so
     * outside a Spring context it has to be set the way Spring would set it.
     */
    private static AppConfig.Network previewNetwork() throws Exception {
        AppConfig.Network network = new AppConfig.Network();
        Field field = AppConfig.Network.class.getDeclaredField("network");
        field.setAccessible(true);
        field.set(network, "preview");
        return network;
    }
}
