package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.OutputRole;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.PlannedOutput;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.Reason;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The detection matrix: one fixture per rule, each built to fire EXACTLY that rule (asserted as set
 * equality, so a fixture that fires a neighbour as well cannot pass by accident), a shaped wallet that
 * fires nothing, and the plan order.
 */
class WalletShapeTest {

    static final String WALLET = LoanFixtures.botAddress();
    static final BigInteger C = BigInteger.valueOf(5_000_000L);
    static final BigInteger TEN_ADA = BigInteger.valueOf(10_000_000L);

    // Hex units built by hand — Amount.asset(policy, name, qty) would hex-encode a hex name (CCL trap 3).
    static final String UNIT_A = "aa".repeat(28) + "41";
    static final String UNIT_B = "bb".repeat(28) + "42";
    static final String JUNK_1 = "cc".repeat(28) + "4a31";
    static final String JUNK_2 = "dd".repeat(28) + "4a32";
    static final Set<String> RELEVANT = Set.of(UNIT_A, UNIT_B);

    private static int counter = 0;

    static Utxo utxo(BigInteger lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(lovelace));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        Utxo u = new Utxo();
        u.setTxHash(String.format("%064x", ++counter));
        u.setOutputIndex(0);
        u.setAddress(WALLET);
        u.setAmount(amounts);
        return u;
    }

    static BigInteger ada(long whole) {
        return BigInteger.valueOf(whole * 1_000_000L);
    }

    /** The target shape: token A, the junk bundle, collateral, one change. */
    private static List<Utxo> shaped() {
        return List.of(
                utxo(TEN_ADA, UNIT_A, 500L),
                utxo(TEN_ADA, JUNK_1, 7L, JUNK_2, 9L),
                utxo(C),
                utxo(ada(100)));
    }

    private static EnumSet<Reason> reasons(List<Utxo> utxos) {
        return WalletShape.reasons(utxos, RELEVANT, C);
    }

    @Test
    void shapedWalletFiresNothing() {
        assertEquals(EnumSet.noneOf(Reason.class), reasons(shaped()));
    }

    @Test
    void shapedWalletWithNoChangeAndNoTokensFiresNothing() {
        assertEquals(EnumSet.noneOf(Reason.class), reasons(List.of(utxo(C))));
    }

    @Test
    void r1_noAdaOnlyUtxoEqualToCollateral() {
        // The only ADA-only UTxO is LARGER than C: "at least C" is not "exactly C".
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L), utxo(TEN_ADA, JUNK_1, 7L), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.NO_EXACT_COLLATERAL), reasons(utxos));
    }

    @Test
    void r1_aTokenUtxoHoldingCLovelaceIsNotCollateral() {
        List<Utxo> utxos = List.of(utxo(C, JUNK_1, 7L), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.NO_EXACT_COLLATERAL, Reason.NON_RELEVANT_NOT_ONE_EXACT_UTXO), reasons(utxos));
    }

    @Test
    void r2_twoAdaOnlyUtxosBesidesCollateral() {
        List<Utxo> utxos = new ArrayList<>(shaped());
        utxos.add(utxo(ada(50)));
        assertEquals(EnumSet.of(Reason.ADA_FRAGMENTED), reasons(utxos));
    }

    @Test
    void r3_twoRelevantUnitsShareAUtxo() {
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L, UNIT_B, 3L), utxo(TEN_ADA, JUNK_1, 7L), utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.RELEVANT_UNITS_SHARE_A_UTXO), reasons(utxos));
    }

    @Test
    void r4_relevantUtxoWithMoreThanTenAda() {
        List<Utxo> utxos = List.of(utxo(ada(12), UNIT_A, 500L), utxo(TEN_ADA, JUNK_1, 7L), utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.RELEVANT_UTXO_LOVELACE_NOT_EXACT), reasons(utxos));
    }

    @Test
    void r4_relevantUtxoWithLessThanTenAda() {
        List<Utxo> utxos = List.of(utxo(ada(2), UNIT_A, 500L), utxo(TEN_ADA, JUNK_1, 7L), utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.RELEVANT_UTXO_LOVELACE_NOT_EXACT), reasons(utxos));
    }

    @Test
    void r5_nonRelevantTokensSpreadOverTwoUtxos() {
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L), utxo(TEN_ADA, JUNK_1, 7L), utxo(TEN_ADA, JUNK_2, 9L),
                utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.NON_RELEVANT_NOT_ONE_EXACT_UTXO), reasons(utxos));
    }

    @Test
    void r5_theOneNonRelevantUtxoDoesNotHoldExactlyTenAda() {
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L), utxo(ada(11), JUNK_1, 7L, JUNK_2, 9L), utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.NON_RELEVANT_NOT_ONE_EXACT_UTXO), reasons(utxos));
    }

    @Test
    void r6_relevantUnitMixedWithNonRelevant() {
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L, JUNK_1, 7L), utxo(C), utxo(ada(100)));
        assertEquals(EnumSet.of(Reason.RELEVANT_MIXED_WITH_NON_RELEVANT), reasons(utxos));
    }

    @Test
    void unitsAreComparedCaseInsensitively() {
        List<Utxo> utxos = List.of(utxo(TEN_ADA, UNIT_A, 500L), utxo(C));
        assertEquals(EnumSet.noneOf(Reason.class), WalletShape.reasons(utxos, Set.of(UNIT_A.toUpperCase()), C));
    }

    @Test
    void relevantUnitsAreTheAnticipateMarketsLowerCasedWithoutLovelace() {
        Market anticipateA = market(UNIT_A.toUpperCase(), Action.ANTICIPATE);
        Market convertB = market(UNIT_B, Action.CONVERT);
        Market anticipateAda = market("lovelace", Action.ANTICIPATE);
        Market anticipateAdaUpper = market("LOVELACE", Action.ANTICIPATE);
        Market defaulted = market(JUNK_1, null);

        assertEquals(Set.of(UNIT_A),
                WalletShape.relevantUnits(List.of(anticipateA, convertB, anticipateAda, anticipateAdaUpper, defaulted)));
        assertEquals(Set.of(), WalletShape.relevantUnits(null));
    }

    private static Market market(String unit, Action action) {
        Market m = new Market();
        m.setUnit(unit);
        m.setAction(action);
        return m;
    }

    @Test
    void planOrdersRelevantTokensByUnitThenTheJunkBundleThenCollateral() {
        List<Utxo> utxos = List.of(
                utxo(ada(3), UNIT_B, 4L, JUNK_2, 9L),
                utxo(ada(40), UNIT_A, 500L),
                utxo(ada(2), UNIT_A, 1L, JUNK_1, 7L),
                utxo(ada(60)));

        WalletShape.Plan plan = WalletShape.plan(utxos, RELEVANT, C);

        assertEquals(List.of(
                new PlannedOutput(OutputRole.RELEVANT_TOKEN, TEN_ADA, Map.of(UNIT_A, BigInteger.valueOf(501))),
                new PlannedOutput(OutputRole.RELEVANT_TOKEN, TEN_ADA, Map.of(UNIT_B, BigInteger.valueOf(4))),
                new PlannedOutput(OutputRole.NON_RELEVANT_TOKENS, TEN_ADA,
                        Map.of(JUNK_1, BigInteger.valueOf(7), JUNK_2, BigInteger.valueOf(9))),
                new PlannedOutput(OutputRole.COLLATERAL, C, Map.of())), plan.outputs());
        assertEquals(ada(105), plan.inputLovelace());
        assertEquals(ada(105).subtract(ada(30)).subtract(C), plan.changeAndFeeLovelace());
    }

    @Test
    void planWithoutTokensIsCollateralOnly() {
        WalletShape.Plan plan = WalletShape.plan(List.of(utxo(ada(20)), utxo(ada(7))), RELEVANT, C);
        assertEquals(List.of(new PlannedOutput(OutputRole.COLLATERAL, C, Map.of())), plan.outputs());
    }

    @Test
    void collateralIsTheCclFloorWhenTheLedgerCeilingIsBelowIt() {
        // Fixture params: maxPossibleCollateral = 3,607,616 < 5 ADA.
        assertEquals(C, WalletShape.collateralLovelace(LoanFixtures.protocolParams().getProtocolParams()));
    }
}
