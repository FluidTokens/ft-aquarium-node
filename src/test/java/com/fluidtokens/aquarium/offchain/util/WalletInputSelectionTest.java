package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-052 — the wallet input is chosen to cover the transaction, smallest that suffices.
 *
 * <p>⚑ FAB-185: the ada selectors ({@code smallestSufficient}, {@code largest}, {@code largestNominable}) and
 * the compound-funding API are deleted with their tests — ada funding is by wallet role now
 * ({@code WalletRolesTest}). What is pinned here is the ada-only predicate, the token-principal rules and the
 * token-holding split.
 */
class WalletInputSelectionTest {

    private static Utxo ada(String hash, int ix, long lovelace) {
        Utxo utxo = new Utxo();
        utxo.setTxHash(hash);
        utxo.setOutputIndex(ix);
        utxo.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))));
        return utxo;
    }

    /** The four clauses, each rejected on its own so no single one can silently stop mattering. */
    @Test
    void ineligibleUtxosAreNotNominable() {
        Utxo twoAssets = ada("dd", 0, 90_000_000L);
        twoAssets.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(90_000_000L)),
                Amount.asset("policy" + "name", BigInteger.ONE)));
        Utxo refScript = ada("ee", 0, 90_000_000L);
        refScript.setReferenceScriptHash("deadbeef");
        Utxo inlineDatum = ada("ff", 0, 90_000_000L);
        inlineDatum.setInlineDatum("d87980");
        Utxo datumHash = ada("00", 0, 90_000_000L);
        datumHash.setDataHash("cafebabe");

        for (Utxo bad : List.of(twoAssets, refScript, inlineDatum, datumHash)) {
            assertFalse(WalletInputSelection.nominable(bad), bad.getTxHash() + " must not be nominable");
        }
        assertTrue(WalletInputSelection.nominable(ada("aa", 0, 5_000_000L)));
    }

    /**
     * (d3) FAB-188: ada-only means the one amount entry IS lovelace. A UTxO whose single entry is a token is not
     * nominable, however it got that way.
     */
    @Test
    void aSingleNonLovelaceAmountIsNotNominable() {
        Utxo tokenOnly = ada("ab", 0, 90_000_000L);
        tokenOnly.setAmount(List.of(Amount.asset("aa".repeat(28) + "41", BigInteger.valueOf(90_000_000L))));
        assertFalse(WalletInputSelection.nominable(tokenOnly), "a single token entry is not ada-only");
        assertTrue(WalletInputSelection.nominable(ada("ac", 0, 90_000_000L)), "positive control");
        Utxo upperCase = ada("ad", 0, 90_000_000L);
        upperCase.setAmount(List.of(new Amount("LOVELACE", BigInteger.valueOf(90_000_000L))));
        assertTrue(WalletInputSelection.nominable(upperCase), "the lovelace unit is compared case-insensitively");
    }

    /** (d4) A null amount list is not nominable, and asking does not throw. */
    @Test
    void aNullAmountListIsNotNominableAndDoesNotThrow() {
        Utxo noAmount = ada("ae", 0, 1L);
        noAmount.setAmount(null);
        assertFalse(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> WalletInputSelection.nominable(noAmount)), "a null amount list is not nominable");
    }

    // ---- token-principal nomination (Part 2, token-principals slice) -------------------------------

    private static final AssetType USDM =
            new AssetType("c48cbb3d000000000000000000000000000000000002da47ad", "0014df105553444d");

    private static Utxo token(String hash, int ix, long usdmBaseUnits, long lovelace) {
        Utxo utxo = new Utxo();
        utxo.setTxHash(hash);
        utxo.setOutputIndex(ix);
        utxo.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace)),
                Amount.asset(USDM.policyId() + USDM.assetName(), BigInteger.valueOf(usdmBaseUnits))));
        return utxo;
    }

    /**
     * ⛔ THE FIXTURE GIOVANNI'S FUNDING TRANSACTION ACTUALLY PRODUCED — USDM sitting in a MULTI-ASSET
     * UTxO alongside its min-ada. {@code nominable} (ada-only) filters this out by construction; a
     * test built on a PURE-USDM UTxO (no such thing exists on a real ledger — every UTxO carries some
     * ada) would pass today's bug and fail production. This is the positive control the contract
     * names explicitly.
     */
    @Test
    void aMultiAssetUsdmUtxoIsNominatedForATokenPrincipal() {
        Utxo usdmAndAda = token("usdm1", 0, 1_100_000_000L, 1_400_000L);
        List<Utxo> wallet = List.of(ada("plain", 0, 60_000_000L), usdmAndAda);

        assertTrue(WalletInputSelection.nominableForToken(usdmAndAda, USDM),
                "a token + min-ada utxo must be nominable for that token");
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficientToken(
                wallet, USDM, BigInteger.valueOf(1_033_850_765L), BigInteger.valueOf(1_000_000L));
        assertTrue(chosen.isPresent());
        assertEquals("usdm1", chosen.get().getTxHash());
    }

    /**
     * The ada-only rule is UNTOUCHED — a token-carrying utxo must never be selected by the plain
     * ada-only path, exactly as {@link #ineligibleUtxosAreNotNominable} already pins. Restated here
     * against a REAL token-and-min-ada shape (not a throwaway "policy"+"name" pair), so the two rules'
     * independence is visible from this class alone.
     */
    @Test
    void theMultiAssetUsdmUtxoIsNeverNominableOnThePlainAdaOnlyPath() {
        Utxo usdmAndAda = token("usdm1", 0, 1_100_000_000L, 1_400_000L);
        assertFalse(WalletInputSelection.nominable(usdmAndAda),
                "the token rule is ADDITIVE — it must never loosen the ada-only rule");
    }

    /** A pure-ada wallet cannot fund a USDM payout, however large — nothing to nominate. */
    @Test
    void aPureAdaWalletIsRefusedForAUsdmPayout() {
        List<Utxo> pureAda = List.of(ada("aa", 0, 5_000_000L), ada("bb", 1, 500_000_000L));
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficientToken(
                pureAda, USDM, BigInteger.valueOf(1_033_850_765L), BigInteger.valueOf(1_000_000L));
        assertTrue(chosen.isEmpty(), "a pure-ada wallet holds none of the principal token");
        assertTrue(WalletInputSelection.largestNominableToken(pureAda, USDM).isEmpty());
    }

    /** The utxo must carry ADA alongside the token too — the fee ceiling and min-ada rider. */
    @Test
    void aTokenUtxoWithTooLittleAdaAlongsideItIsRefused() {
        Utxo thinAda = token("thin", 0, 5_000_000_000L, 900_000L);
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficientToken(
                List.of(thinAda), USDM, BigInteger.valueOf(1_033_850_765L), BigInteger.valueOf(1_000_000L));
        assertTrue(chosen.isEmpty(), "1,000,000 required ada > 900,000 held, however much USDM sits alongside it");
    }

    /** Smallest-that-suffices applies to the PRINCIPAL quantity too, not just ada. */
    @Test
    void smallestSufficientTokenPicksTheSmallestTokenQuantityThatCoversTheRequirement() {
        Utxo small = token("small", 0, 1_100_000_000L, 2_000_000L);
        Utxo large = token("large", 0, 5_000_000_000L, 2_000_000L);
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficientToken(
                List.of(large, small), USDM, BigInteger.valueOf(1_033_850_765L), BigInteger.valueOf(1_000_000L));
        assertEquals("small", chosen.orElseThrow().getTxHash(),
                "the smaller token-sufficient utxo must be preferred, leaving the larger for a candidate that needs it");
    }

    @Test
    void tokenQuantityOfSumsMatchingUnitsAndIgnoresOthers() {
        Utxo usdmAndAda = token("usdm1", 0, 1_100_000_000L, 1_400_000L);
        assertEquals(BigInteger.valueOf(1_100_000_000L), WalletInputSelection.tokenQuantityOf(usdmAndAda, USDM));
        assertEquals(BigInteger.ZERO, WalletInputSelection.tokenQuantityOf(ada("aa", 0, 5_000_000L), USDM));
    }

    // ---- FAB-128 — the token the balance gate does NOT count, and why ------------------------------

    private static final Function<BigInteger, String> IN_USDM = q -> q + " USDM";

    private static final String VARIANT_A_WORDING = "Consolidate the token into one UTxO carrying at least";
    private static final String VARIANT_B_WORDING = "Even counted,";

    /**
     * Each field against hand-summed values, over one UTxO of every shape the rule distinguishes. A
     * floor of 4,000,000 lovelace: {@code spendable} counts the 5 ADA one and the one EXACTLY at 4 ADA
     * (the comparison is {@code >=}, the same one {@code smallestSufficientToken} applies), {@code
     * belowAdaFloor} the 1.18 ADA one, and the datum-carrying one is in {@code total} alone — it is not
     * {@code nominableForToken}, so it is neither spendable nor "short of ada".
     */
    @Test
    void tokenHoldingSplitsTheTokenByWhetherItsUtxoCouldBeSpent() {
        Utxo spendable = token("sp", 0, 100L, 5_000_000L);
        Utxo atTheFloor = token("at", 0, 20L, 4_000_000L);
        Utxo belowTheFloor = token("lo", 0, 3L, 1_180_000L);
        Utxo withDatum = token("dt", 0, 7_000L, 9_000_000L);
        withDatum.setInlineDatum("d87980");
        Utxo noToken = ada("aa", 0, 50_000_000L);

        WalletInputSelection.TokenHolding holding = WalletInputSelection.tokenHolding(
                List.of(spendable, atTheFloor, belowTheFloor, withDatum, noToken), USDM,
                BigInteger.valueOf(4_000_000L));

        assertEquals(BigInteger.valueOf(100L + 20L + 3L + 7_000L), holding.total(),
                "total is every unit of the token in the wallet, datum-carrying UTxO included");
        assertEquals(BigInteger.valueOf(100L + 20L), holding.spendable(),
                "spendable is nominable-for-token AND at least the floor — exactly at it counts");
        assertEquals(BigInteger.valueOf(3L), holding.belowAdaFloor(),
                "belowAdaFloor is nominable-for-token and short of the floor — never the datum one");
        assertEquals(BigInteger.valueOf(4_000_000L), holding.requiredAda());
    }

    /** The executor's own formula, moved here: fee ceiling plus the 2 ADA min-ada rider. */
    @Test
    void tokenPrincipalRequiredAdaIsTheFeeCeilingPlusTheMinAdaRider() {
        assertEquals(BigInteger.valueOf(2_000_000L), WalletInputSelection.TOKEN_PRINCIPAL_MIN_ADA_CEILING);
        assertEquals(BigInteger.valueOf(2_405_077L + 2_000_000L),
                WalletInputSelection.tokenPrincipalRequiredAda(Optional.of(BigInteger.valueOf(2_405_077L))));
        assertEquals(BigInteger.valueOf(2_000_000L),
                WalletInputSelection.tokenPrincipalRequiredAda(Optional.empty()),
                "an unavailable fee ceiling leaves only the rider — the formula the executor always used");
    }

    @Test
    void noNoteWhenNothingIsExcludedByTheAdaFloor() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(500L), BigInteger.valueOf(500L),
                BigInteger.ZERO, BigInteger.valueOf(4_405_077L));
        assertNull(holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_000L), true),
                "with no token stuck behind the floor there is nothing to explain — \"holds X\" says it all");
    }

    @Test
    void noNoteWhenTheSpendableBalanceAlreadyCovers() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(1_003L), BigInteger.valueOf(1_000L),
                BigInteger.valueOf(3L), BigInteger.valueOf(4_405_077L));
        assertNull(holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_000L), true),
                "the excluded token is not why anything was refused when the spendable balance covers");
    }

    /** Variant A — consolidation alone would cure it, so the remedy says only that. */
    @Test
    void consolidationIsTheRemedyWhenTheExcludedTokenWouldCover() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(3_000_000L), BigInteger.ZERO,
                BigInteger.valueOf(3_000_000L), BigInteger.valueOf(4_405_077L));
        String note = holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_600_000L), true);
        assertEquals("the wallet has 3000000 USDM in total, but 3000000 USDM of it sits in UTxOs carrying "
                + "less than 4405077 lovelace (4.405077 ADA) each — the ada a build needs beside the token, "
                + "the fee ceiling plus a 2 ADA min-ada rider — so the bot cannot spend it and does not count "
                + "it. Consolidate the token into one UTxO carrying at least 4405077 lovelace; the wallet "
                + "rebalancer does this automatically when it is enabled and this market is action: "
                + "ANTICIPATE.", note);
        assertFalse(note.contains(VARIANT_B_WORDING), note);
    }

    /** Variant A's boundary: spendable + excluded exactly equal to the requirement does cover it. */
    @Test
    void consolidationIsTheRemedyWhenTheExcludedTokenCoversExactly() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(1_600L), BigInteger.valueOf(600L),
                BigInteger.valueOf(1_000L), BigInteger.valueOf(4_405_077L));
        String note = holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_600L), true);
        assertTrue(note.contains(VARIANT_A_WORDING), note);
        assertFalse(note.contains(VARIANT_B_WORDING), note);
    }

    /**
     * Variant B — consolidating would still leave it short, so the remedy must not claim it suffices:
     * it says how much must be ADDED, and that the rebalancer cannot do that part.
     */
    @Test
    void theRemedyNamesTheShortfallWhenEvenTheExcludedTokenWouldNotCover() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(2_500L), BigInteger.valueOf(1_000L),
                BigInteger.valueOf(500L), BigInteger.valueOf(4_000_000L));
        String note = holding.adaFloorNote(IN_USDM, BigInteger.valueOf(2_000L), true);
        assertEquals("the wallet has 2500 USDM in total, but 500 USDM of it sits in UTxOs carrying less "
                + "than 4000000 lovelace (4 ADA) each — the ada a build needs beside the token, the fee "
                + "ceiling plus a 2 ADA min-ada rider — so the bot cannot spend it and does not count it. "
                + "Even counted, 1500 USDM would be short of the 2000 USDM required: consolidate the token "
                + "into one UTxO carrying at least 4000000 lovelace and add at least 500 USDM; the wallet "
                + "rebalancer consolidates automatically when it is enabled and this market is action: "
                + "ANTICIPATE, but it cannot add funds.", note);
        assertFalse(note.contains(VARIANT_A_WORDING), note);
    }

    /** Without a fee ceiling the floor stated is a lower bound, and the note must say so. */
    @Test
    void anUnavailableFeeCeilingIsDeclaredInTheNote() {
        var holding = new WalletInputSelection.TokenHolding(BigInteger.valueOf(3_000L), BigInteger.ZERO,
                BigInteger.valueOf(3_000L), BigInteger.valueOf(2_000_000L));
        String unknown = holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_000L), false);
        assertTrue(unknown.contains("carrying less than 2000000 lovelace (2 ADA) each — the ada a build needs "
                + "beside the token, the fee ceiling plus a 2 ADA min-ada rider (the fee ceiling was "
                + "unavailable this cycle, so this floor leaves it out and the real one is higher) — so the "
                + "bot cannot spend it"), unknown);
        String known = holding.adaFloorNote(IN_USDM, BigInteger.valueOf(1_000L), true);
        assertFalse(known.contains("unavailable"), known);
    }

    /** N_ADA is a plain decimal with trailing zeros stripped — never "1E+1" and never "2.500000". */
    @Test
    void theAdaFloorIsStatedAsAPlainDecimal() {
        var ten = new WalletInputSelection.TokenHolding(BigInteger.TEN, BigInteger.ZERO, BigInteger.TEN,
                BigInteger.valueOf(10_000_000L));
        assertTrue(ten.adaFloorNote(IN_USDM, BigInteger.ONE, true).contains("(10 ADA)"));
        var twoAndAHalf = new WalletInputSelection.TokenHolding(BigInteger.TEN, BigInteger.ZERO, BigInteger.TEN,
                BigInteger.valueOf(2_500_000L));
        assertTrue(twoAndAHalf.adaFloorNote(IN_USDM, BigInteger.ONE, true).contains("(2.5 ADA)"));
    }
}
