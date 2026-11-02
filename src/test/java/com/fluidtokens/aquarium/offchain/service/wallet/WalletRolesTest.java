package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-185 — the roles one liquidation check resolves from one wallet read: C, the ada-only output of exactly
 * the collateral; U, the LARGEST other ada-only output; and per unit the output holding the most of it. Each
 * rule, its ties (the smallest ref — txHash, then index — wins), its exclusions, and that the resolved value
 * does not move when the list it came from does.
 */
class WalletRolesTest {

    static final String WALLET = LoanFixtures.botAddress();
    static final BigInteger C = BigInteger.valueOf(5_000_000L);

    // Hex units built by hand — Amount.asset(policy, name, qty) would hex-encode a hex name (CCL trap 3).
    static final String UNIT_A = "aa".repeat(28) + "41";
    static final String UNIT_B = "bb".repeat(28) + "42";

    /** An output at {@code txByte * 32}#{@code index}, holding {@code lovelace} and the given unit/quantity pairs. */
    static Utxo utxo(String txByte, int index, long lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        return LoanFixtures.utxo(txByte.repeat(32), index, WALLET, amounts, null);
    }

    static Utxo withInlineDatum(Utxo utxo) {
        utxo.setInlineDatum("d87980");
        return utxo;
    }

    static Utxo withDataHash(Utxo utxo) {
        utxo.setDataHash("ab".repeat(32));
        return utxo;
    }

    static Utxo withReferenceScript(Utxo utxo) {
        utxo.setReferenceScriptHash("9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d");
        return utxo;
    }

    static String ref(Optional<Utxo> utxo) {
        return utxo.map(WalletRoles::ref).orElse("none");
    }

    // ---- C ----------------------------------------------------------------------------------------

    @Test
    void cIsTheAdaOnlyOutputOfExactlyTheCollateral() {
        Utxo c = utxo("11", 0, 5_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(utxo("22", 0, 5_000_001L), c, utxo("33", 0, 4_999_999L)), C);

        assertEquals(WalletRoles.ref(c), ref(roles.c()));
        assertEquals(C, roles.collateralLovelace());
    }

    /** Two exact-C outputs: the smallest ref is C, and the other is an ordinary ada-only output, eligible for U. */
    @Test
    void ofTwoExactCOutputsTheSmallestRefIsCAndTheOtherIsEligibleForU() {
        Utxo laterTx = utxo("22", 0, 5_000_000L);
        Utxo sameTxHigherIndex = utxo("11", 1, 5_000_000L);
        Utxo smallestRef = utxo("11", 0, 5_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(laterTx, sameTxHigherIndex, smallestRef), C);

        assertEquals(WalletRoles.ref(smallestRef), ref(roles.c()), "txHash first, then index");
        assertEquals(WalletRoles.ref(sameTxHigherIndex), ref(roles.u()),
                "the other exact-C outputs tie for U, and the smallest ref of them wins");
    }

    /** A datum- or reference-script-bearing output of exactly C is never C (WalletInputSelection.nominable). */
    @Test
    void aDatumOrReferenceScriptExactCOutputIsNeverC() {
        for (Utxo dirty : List.of(withInlineDatum(utxo("11", 0, 5_000_000L)), withDataHash(utxo("11", 0, 5_000_000L)),
                withReferenceScript(utxo("11", 0, 5_000_000L)))) {
            WalletRoles roles = WalletRoles.resolve(List.of(dirty, utxo("99", 0, 50_000_000L)), C);
            assertEquals(Optional.empty(), roles.c(), "never C: " + dirty);
            assertEquals("99".repeat(32) + "#0", ref(roles.u()), "nor U: " + dirty);
        }
        // and a clean one beside it still is
        Utxo clean = utxo("22", 0, 5_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(withInlineDatum(utxo("11", 0, 5_000_000L)), clean), C);
        assertEquals(WalletRoles.ref(clean), ref(roles.c()));
    }

    /** A token-bearing output of exactly C lovelace is not ada-only, so it is never C. */
    @Test
    void aTokenBearingExactCOutputIsNeverC() {
        WalletRoles roles = WalletRoles.resolve(List.of(utxo("11", 0, 5_000_000L, UNIT_A, 1L)), C);
        assertEquals(Optional.empty(), roles.c());
    }

    // ---- U ----------------------------------------------------------------------------------------

    /** U is the LARGEST ada-only output that is not C — not the smallest that would suffice for anything. */
    @Test
    void uIsTheLargestAdaOnlyOutputThatIsNotC() {
        Utxo small = utxo("11", 0, 10_000_000L);
        Utxo large = utxo("22", 0, 58_384_544L);
        Utxo middling = utxo("33", 0, 38_839_574L);
        Utxo c = utxo("44", 0, 5_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(small, c, large, middling), C);

        assertEquals(WalletRoles.ref(large), ref(roles.u()));
        assertEquals(WalletRoles.ref(c), ref(roles.c()));
    }

    @Test
    void uTiesGoToTheSmallestRef() {
        Utxo higherIndex = utxo("11", 3, 60_000_000L);
        Utxo laterTx = utxo("22", 0, 60_000_000L);
        Utxo smallestRef = utxo("11", 2, 60_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(laterTx, higherIndex, smallestRef), C);

        assertEquals(WalletRoles.ref(smallestRef), ref(roles.u()));
    }

    /** A larger datum-, data-hash-, reference-script- or token-bearing output is never U. */
    @Test
    void uIsNeverANonAdaOnlyOutputHoweverLarge() {
        Utxo modest = utxo("55", 0, 60_000_000L);
        WalletRoles roles = WalletRoles.resolve(List.of(withInlineDatum(utxo("11", 0, 900_000_000L)),
                withDataHash(utxo("22", 0, 900_000_000L)), withReferenceScript(utxo("33", 0, 900_000_000L)),
                utxo("44", 0, 900_000_000L, UNIT_A, 1L), modest), C);

        assertEquals(WalletRoles.ref(modest), ref(roles.u()));
    }

    /** C is never U, even when it is the only ada-only output. */
    @Test
    void cAloneLeavesNoU() {
        WalletRoles roles = WalletRoles.resolve(List.of(utxo("11", 0, 5_000_000L)), C);
        assertTrue(roles.c().isPresent());
        assertEquals(Optional.empty(), roles.u());
    }

    // ---- token outputs ----------------------------------------------------------------------------

    @Test
    void theTokenOutputIsTheOneHoldingTheMostOfThatUnit() {
        Utxo less = utxo("11", 0, 10_000_000L, UNIT_A, 100L);
        Utxo most = utxo("22", 0, 2_000_000L, UNIT_A, 300L);
        Utxo other = utxo("33", 0, 50_000_000L, UNIT_B, 900L);
        WalletRoles roles = WalletRoles.resolve(List.of(less, most, other), C);

        assertEquals(WalletRoles.ref(most), ref(roles.tokenOutput(UNIT_A)), "the most of the unit, not the most ada");
        assertEquals(WalletRoles.ref(other), ref(roles.tokenOutput(UNIT_B)));
        assertEquals(WalletRoles.ref(most), ref(roles.tokenOutput(UNIT_A.toUpperCase())), "units compare lower-cased");
        assertEquals(Optional.empty(), roles.tokenOutput("ee".repeat(28) + "45"), "no output holds it");
    }

    @Test
    void tokenOutputTiesGoToTheSmallestRef() {
        Utxo laterTx = utxo("22", 0, 10_000_000L, UNIT_A, 300L);
        Utxo smallestRef = utxo("11", 1, 10_000_000L, UNIT_A, 300L);
        WalletRoles roles = WalletRoles.resolve(List.of(laterTx, smallestRef), C);

        assertEquals(WalletRoles.ref(smallestRef), ref(roles.tokenOutput(UNIT_A)));
    }

    /** An output that also holds other tokens is eligible; a datum or reference-script one is not, however much it holds. */
    @Test
    void aMixedOutputIsEligibleButADatumOrReferenceScriptOneIsNot() {
        Utxo mixed = utxo("11", 0, 10_000_000L, UNIT_A, 100L, UNIT_B, 5L);
        WalletRoles roles = WalletRoles.resolve(List.of(mixed,
                withInlineDatum(utxo("22", 0, 10_000_000L, UNIT_A, 900L)),
                withDataHash(utxo("33", 0, 10_000_000L, UNIT_A, 900L)),
                withReferenceScript(utxo("44", 0, 10_000_000L, UNIT_A, 900L))), C);

        assertEquals(WalletRoles.ref(mixed), ref(roles.tokenOutput(UNIT_A)));
        assertEquals(WalletRoles.ref(mixed), ref(roles.tokenOutput(UNIT_B)));
    }

    @Test
    void aZeroQuantityOfAUnitIsNotHoldingIt() {
        WalletRoles roles = WalletRoles.resolve(List.of(utxo("11", 0, 10_000_000L, UNIT_A, 0L)), C);
        assertEquals(Optional.empty(), roles.tokenOutput(UNIT_A));
    }

    // ---- the value ----------------------------------------------------------------------------------

    @Test
    void anEmptyWalletHasNoRoles() {
        for (List<Utxo> empty : java.util.Arrays.asList(List.<Utxo>of(), null)) {
            WalletRoles roles = WalletRoles.resolve(empty, C);
            assertEquals(Optional.empty(), roles.c());
            assertEquals(Optional.empty(), roles.u());
            assertEquals(Optional.empty(), roles.tokenOutput(UNIT_A));
        }
    }

    /** The roles are resolved ONCE: mutating the list they came from afterwards changes nothing. */
    @Test
    void theResolvedRolesDoNotChangeWhenTheInputListIsMutated() {
        Utxo c = utxo("11", 0, 5_000_000L);
        Utxo u = utxo("22", 0, 60_000_000L);
        Utxo token = utxo("33", 0, 10_000_000L, UNIT_A, 100L);
        List<Utxo> wallet = new ArrayList<>(List.of(c, u, token));
        WalletRoles roles = WalletRoles.resolve(wallet, C);

        wallet.clear();
        wallet.add(utxo("44", 0, 5_000_000L));
        wallet.add(utxo("55", 0, 900_000_000L));
        wallet.add(utxo("66", 0, 10_000_000L, UNIT_A, 900L));

        assertEquals(WalletRoles.ref(c), ref(roles.c()));
        assertEquals(WalletRoles.ref(u), ref(roles.u()));
        assertEquals(WalletRoles.ref(token), ref(roles.tokenOutput(UNIT_A)));
        assertEquals(Optional.of("C"), roles.roleOf(WalletRoles.ref(c)));
        assertEquals(Optional.of("U"), roles.roleOf(WalletRoles.ref(u)));
        assertEquals(Optional.of("TOKEN " + UNIT_A), roles.roleOf(WalletRoles.ref(token)));
        assertEquals(Optional.empty(), roles.roleOf("55".repeat(32) + "#0"));
    }

    // ---- the pre-build ceilings ---------------------------------------------------------------------

    @Test
    void theRequirementCeilingsAreTheStatedSums() {
        BigInteger fee = BigInteger.valueOf(2_405_077L);
        BigInteger order = BigInteger.valueOf(4_000_000L);
        BigInteger payout = BigInteger.valueOf(28_000_147L);

        assertEquals(fee, WalletRoles.plainRequired(fee));
        assertEquals(order.add(fee), WalletRoles.convertRequired(order, fee));
        assertEquals(payout.add(fee).add(BigInteger.valueOf(3_500_000L)), WalletRoles.adaAnticipateRequired(payout, fee));
        assertEquals(fee.add(BigInteger.valueOf(5_500_000L)), WalletRoles.tokenOutputRequiredAda(fee),
                "fee + 2 ADA lender min-ada + 1.5 ADA S-15 rider + 2 ADA change min-ada");
        assertEquals(BigInteger.valueOf(1_500_000L), WalletRoles.S15_COLLATERAL_RIDER_CEILING);
        assertEquals(BigInteger.valueOf(2_000_000L), WalletRoles.CHANGE_MIN_ADA_CEILING);
    }

    /**
     * (h) A shaped token output — exactly {@link WalletShape#SHAPED_TOKEN_LOVELACE} — must clear the token
     * route's own ceiling, or the rebalancer's shape could never fund the route it exists for.
     */
    @Test
    void aShapedTokenOutputClearsTheTokenRoutesAdaCeiling() {
        BigInteger required = WalletRoles.tokenOutputRequiredAda(
                LedgerCeilings.maxPossibleFee(LoanFixtures.protocolParams().getProtocolParams()));
        assertTrue(required.compareTo(WalletShape.SHAPED_TOKEN_LOVELACE) <= 0,
                required + " lovelace must fit inside the shaped token output's " + WalletShape.SHAPED_TOKEN_LOVELACE);
    }
}
