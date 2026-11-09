package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.ScheduledTransactionService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * ⛔ FAB-38: <b>one {@link TransactionEvaluator} bean, and every path that prices a script transaction
 * holds THAT instance</b> — asserted through the container, not by calling bean methods.
 *
 * <h2>Why through the container</h2>
 * Script-cost evaluation used to be wired at six sites: a lambda in each of the four loans-builder bean
 * methods, the tank {@code QuickTxBuilder}'s processor slot (which cardano-client-lib also uses as the
 * evaluator, CCL trap 8), and {@code ScheduledTransactionService}'s dump wrapper. Collapsing them into
 * one bean is only true if the container hands that bean to all of them; {@code YaciConfigWiringTest}
 * calls the bean methods directly and so cannot see which instance the container injects.
 *
 * <ul>
 *   <li><b>(i) the default graph</b>: exactly one {@code TransactionEvaluator} bean, no
 *       {@code TransactionProcessor} bean at all (no submission path can be injected), and the same
 *       instance held by the four builders' {@code scriptCostEvaluator}, the tank processor slot's
 *       {@code evaluator} and {@code ScheduledTransactionService}'s {@code transactionEvaluator}.</li>
 *   <li><b>(ii) the swap</b>: replace the bean through the context, prove the replacement took, then
 *       drive one evaluation through what each of the five paths holds — the replacement must see all
 *       five, in order.</li>
 * </ul>
 * Each case first asserts the container started and the beans it inspects EXIST (counted), so an
 * empty context cannot pass as a correct one (spring-boot-config-binding-traps §2).
 */
class TransactionEvaluatorWiringTest {

    /** The four loans builders that hold the evaluator as {@code scriptCostEvaluator}. */
    private static final List<Class<?>> LOANS_BUILDERS = List.of(
            LiquidateTransactionBuilder.class,
            CompoundTransactionBuilder.class,
            ConvertTransactionBuilder.class,
            LiquidatePayInAdvanceTransactionBuilder.class);

    /**
     * {@link ContainerWiringTest#runner()} — {@code YaciConfig} and every collaborator it needs — plus the
     * tank processor, registered through the context with the collaborators that runner lacks mocked.
     */
    private static ApplicationContextRunner runner() {
        return ContainerWiringTest.runner()
                .withPropertyValues("scheduling.transaction-processor.enabled=true",
                        // ⚠ The container still injects @Value fields into a mock it is handed, and
                        // AquariumConfiguration's have no inline defaults: state them, as a node must.
                        "aquarium.staking.token.policy=", "aquarium.staking.token.name=",
                        "aquarium.genesis.tx-hash=", "aquarium.genesis.output-index=0",
                        "aquarium.tank.ref-input.txHash=", "aquarium.tank.ref-input.outputIndex=0")
                .withBean(Account.class, () -> mock(Account.class))
                .withBean(AppConfig.AquariumConfiguration.class, () -> mock(AppConfig.AquariumConfiguration.class))
                .withBean(StakerService.class, () -> mock(StakerService.class))
                .withBean(ParametersService.class, () -> mock(ParametersService.class))
                .withBean(TankContractService.class, () -> mock(TankContractService.class))
                .withBean(AppUtxoService.class, () -> mock(AppUtxoService.class))
                .withBean(BlockEventListener.class, () -> mock(BlockEventListener.class))
                .withBean(ScheduledTransactionService.class);
    }

    @Test
    void everyEvaluationPathHoldsTheOneEvaluatorBean() {
        runner().run(ctx -> {
            assertStartedWithEveryHolder(ctx);
            assertEquals(1, ctx.getBeanNamesForType(TransactionEvaluator.class).length,
                    "there must be exactly ONE TransactionEvaluator bean");
            assertEquals(0, ctx.getBeanNamesForType(TransactionProcessor.class).length,
                    "the context holds a TransactionProcessor bean: a submission path anything can inject");

            TransactionEvaluator bean = ctx.getBean(TransactionEvaluator.class);
            for (Class<?> builder : LOANS_BUILDERS) {
                assertSame(bean, fieldOf(ctx.getBean(builder), "scriptCostEvaluator"),
                        builder.getSimpleName() + " does not hold the evaluator bean");
            }
            YaciConfig.EvaluatingTransactionProcessor slot = assertInstanceOf(
                    YaciConfig.EvaluatingTransactionProcessor.class, tankProcessorSlot(ctx),
                    "the tank QuickTxBuilder bean's processor slot is not the EvaluatingTransactionProcessor");
            assertSame(bean, fieldOf(slot, "evaluator"),
                    "the tank QuickTxBuilder bean's processor slot does not evaluate through the evaluator bean");
            assertSame(bean, fieldOf(ctx.getBean(ScheduledTransactionService.class), "transactionEvaluator"),
                    "ScheduledTransactionService's dump wrapper does not wrap the evaluator bean");
        });
    }

    @Test
    void swappingTheEvaluatorThroughTheContextReachesAllFivePaths() {
        List<byte[]> seen = new ArrayList<>();
        TransactionEvaluator counting = (cbor, inputUtxos) -> {
            seen.add(cbor);
            return Result.success("counted");
        };

        runner()
                .withAllowBeanDefinitionOverriding(true)
                .withBean("transactionEvaluator", TransactionEvaluator.class, () -> counting)
                .run(ctx -> {
                    assertStartedWithEveryHolder(ctx);
                    assertEquals(1, ctx.getBeanNamesForType(TransactionEvaluator.class).length,
                            "the swap must REPLACE the evaluator bean, not add a second one");
                    assertSame(counting, ctx.getBean(TransactionEvaluator.class),
                            "the swap did not take: the context's evaluator bean is not the replacement");

                    List<byte[]> sent = new ArrayList<>();
                    for (Class<?> builder : LOANS_BUILDERS) {
                        byte[] cbor = {(byte) sent.size(), 0x1b};
                        sent.add(cbor);
                        ((TransactionEvaluator) fieldOf(ctx.getBean(builder), "scriptCostEvaluator"))
                                .evaluateTx(cbor, Set.of());
                    }
                    byte[] tank = {(byte) sent.size(), 0x7a};
                    sent.add(tank);
                    ((TransactionProcessor) tankProcessorSlot(ctx)).evaluateTx(tank, Set.of());

                    assertEquals(5, seen.size(), "the replacement evaluator saw " + seen.size()
                            + " of the five evaluation paths");
                    for (int i = 0; i < sent.size(); i++) {
                        assertSame(sent.get(i), seen.get(i), "evaluation path " + i
                                + " did not reach the replacement evaluator with its own bytes");
                    }
                    assertSame(counting, fieldOf(ctx.getBean(ScheduledTransactionService.class),
                                    "transactionEvaluator"),
                            "ScheduledTransactionService does not hold the replacement evaluator");
                });
    }

    /** The container started, and every bean this class inspects exists exactly once. */
    private static void assertStartedWithEveryHolder(AssertableApplicationContext ctx) {
        assertNull(ctx.getStartupFailure(), () -> "the container did not start: " + ctx.getStartupFailure());
        for (Class<?> holder : List.of(LiquidateTransactionBuilder.class, CompoundTransactionBuilder.class,
                ConvertTransactionBuilder.class, LiquidatePayInAdvanceTransactionBuilder.class,
                QuickTxBuilder.class, ScheduledTransactionService.class)) {
            assertEquals(1, ctx.getBeanNamesForType(holder).length,
                    holder.getSimpleName() + " is not in the context: an absent bean passes every identity check");
        }
    }

    private static Object tankProcessorSlot(AssertableApplicationContext ctx) {
        try {
            Field field = QuickTxBuilder.class.getDeclaredField("transactionProcessor");
            field.setAccessible(true);
            return field.get(ctx.getBean(QuickTxBuilder.class));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object fieldOf(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
