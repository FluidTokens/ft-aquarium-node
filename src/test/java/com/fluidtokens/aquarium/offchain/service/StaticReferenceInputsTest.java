package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FAB-134 B5b — the set of STATIC reference inputs, the only out-refs {@code IndexFirstUtxoSupplier}
 * may hold by out-ref. Built through the Spring container from the real configuration beans bound from
 * properties (the production shape), so a source that is wired but never read shows up here.
 *
 * <h2>What each test kills</h2>
 * <ul>
 *   <li>a source that is not enumerated — a liquidation slot, the compound list, the tank input, or one
 *       of the three out-refs an oracle entry carries (the Charli3 provider included): each would keep
 *       reaching Blockfrost on every build;</li>
 *   <li>a malformed compound coordinate that throws instead of being skipped as
 *       {@code CompoundExecutor} skips it;</li>
 *   <li>a hard dependency on {@link FluidOracleClient}, which is {@code @ConditionalOnProperty
 *       (loans.oracle.enabled)}: a node with the oracle off would fail to start (CCL trap 9b).</li>
 * </ul>
 */
class StaticReferenceInputsTest {

    private static String tx(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static TransactionInput in(char c, int index) {
        return TransactionInput.builder().transactionId(tx(c)).index(index).build();
    }

    /** One coordinate per liquidation slot — all nine, the pay-in-advance and convert actions included. */
    private static final String[][] LIQUIDATION_SLOTS = {
            {"loan", "1"}, {"loan-spend", "2"}, {"lender-manager", "3"}, {"lender-manager-spend", "4"},
            {"loan-claim-action", "5"}, {"lm-liquidate-action", "6"}, {"asset-manager", "7"},
            {"lm-liquidate-and-pay-in-advance-action", "8"}, {"lm-liquidate-and-convert-action", "9"}};

    /** An oracle registry that never touches the network: {@code init()} is the {@code @PostConstruct} refresh. */
    static FluidOracleClient oracle(Collection<OracleEntry> entries) {
        return new FluidOracleClient("https://example.invalid/get-oracle-tokens") {
            @Override
            public void init() {
                // no registry fetch in a unit test
            }

            @Override
            public Collection<OracleEntry> entries() {
                return entries;
            }
        };
    }

    private static OracleEntry entry(TransactionInput referenceInput, TransactionInput referenceScript,
                                     TransactionInput charlieProvider) {
        return new OracleEntry(null, null, null, null, referenceInput, referenceScript, List.of(), 0,
                null, List.of(), charlieProvider);
    }

    private static ApplicationContextRunner runner() {
        List<String> properties = new java.util.ArrayList<>(List.of(
                "loans.liquidation.profit-margin-lovelace=5000000",
                // two well-formed compound coordinates, one with no '#', one with a non-numeric index
                "loans.compound.reference-scripts= " + tx('c') + "#0 , not-a-coordinate," + tx('c') + "#x, "
                        + tx('c') + "#1",
                "aquarium.staking.token.policy=" + "ab".repeat(28),
                "aquarium.staking.token.name=464c4454",
                "aquarium.genesis.tx-hash=" + tx('9'),
                "aquarium.genesis.output-index=0",
                "aquarium.tank.ref-input.txHash=" + tx('d'),
                "aquarium.tank.ref-input.outputIndex=3"));
        for (String[] slot : LIQUIDATION_SLOTS) {
            properties.add("loans.liquidation.reference-scripts." + slot[0] + "=" + tx('a') + "#" + slot[1]);
        }
        return new ApplicationContextRunner()
                .withPropertyValues(properties.toArray(String[]::new))
                .withBean(AppConfig.LiquidationConfiguration.class)
                .withBean(AppConfig.CompoundConfiguration.class)
                .withBean(AppConfig.AquariumConfiguration.class)
                .withBean(StaticReferenceInputs.class);
    }

    private static Set<TransactionInput> configOnly() {
        Set<TransactionInput> expected = new HashSet<>();
        for (String[] slot : LIQUIDATION_SLOTS) {
            expected.add(in('a', Integer.parseInt(slot[1])));
        }
        expected.add(in('c', 0));
        expected.add(in('c', 1));
        expected.add(in('d', 3));
        return expected;
    }

    // ------------------------------------------------------------------ (g)

    /**
     * ⛔ Every source, and exactly those: the nine liquidation slots, the two well-formed compound
     * coordinates (the two malformed ones skipped), the tank input, and per oracle entry its feed, its
     * script and — where it has one — its Charli3 provider.
     */
    @Test
    void everySourceIsEnumeratedTheCharli3ProviderIncluded() {
        var withCharli3 = entry(in('e', 0), in('e', 1), in('e', 2));
        var multisig = entry(in('f', 0), in('f', 1), null);

        runner().withBean(FluidOracleClient.class, () -> oracle(List.of(withCharli3, multisig)))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "context failed: " + ctx.getStartupFailure());
                    Set<TransactionInput> expected = configOnly();
                    expected.addAll(Set.of(in('e', 0), in('e', 1), in('e', 2), in('f', 0), in('f', 1)));

                    assertEquals(expected, ctx.getBean(StaticReferenceInputs.class).current());
                });
    }

    /** ⛔ The oracle registry is conditional; its absence is the config-only set, not a startup failure. */
    @Test
    void anAbsentOracleClientYieldsTheConfigOnlySetWithoutThrowing() {
        runner().run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "context failed: " + ctx.getStartupFailure());
            var inputs = ctx.getBean(StaticReferenceInputs.class);

            Set<TransactionInput> first = assertDoesNotThrow(inputs::current);
            assertEquals(configOnly(), first);
            assertEquals(configOnly(), inputs.current(), "recomputed, not a different answer the second time");
        });
    }

    /** A bare node — no configuration bean at all — answers empty rather than throwing. */
    @Test
    void withNoSourcesAtAllTheSetIsEmpty() {
        new ApplicationContextRunner().withBean(StaticReferenceInputs.class).run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "context failed: " + ctx.getStartupFailure());
            assertEquals(Set.of(), ctx.getBean(StaticReferenceInputs.class).current());
        });
    }
}
