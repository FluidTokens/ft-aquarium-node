package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-056 — a loan carrying an asset nobody pays out is refused at the door.
 *
 * <h2>This is a griefing vector, and the refusal is a blast-radius reduction</h2>
 * {@code assetManagerAmounts} pays out <b>only</b> the loan's declared collateral. Anything else on
 * the loan UTxO is emitted by no output and flows to the bot's change — which
 * {@code adaOnlyWalletUtxo()} then correctly refuses, leaving the wallet with no usable output and
 * <b>stopping the whole bot.</b> That is the 2026-08-25 outage, reproduced by a stranger for the price
 * of one min-UTxO and a fee.
 *
 * <p><b>After this refusal, that one loan is skipped and the bot keeps running.</b> It is route 8 of
 * {@code docs/change-output-enumeration.md} — the one route pay-by-name does not close, and the reason
 * the change split could be deleted without leaving a hole.
 *
 * <p>⚠ The refusal must NAME the offending unit. <i>"Something is wrong with that loan"</i> and
 * <i>"someone sent this token to that address"</i> lead to different actions.
 */
class UndeclaredLoanAssetRefusalTest {

    private static final String COLLATERAL = "0b77d150c275bd0a6006" + "74464c4454";
    private static final String LOAN_NFT = "2f1aa941f437e351e387" + "6c6f616e3031";
    private static final String STRAY = "beefbeefbeefbeefbeef" + "7374726179";

    private static Amount of(String unit, long qty) {
        return Amount.builder().unit(unit).quantity(BigInteger.valueOf(qty)).build();
    }

    private static void check(Amount... amounts) {
        LiquidateTransactionBuilder.refuseUndeclaredAssets(
                List.of(amounts), COLLATERAL, LOAN_NFT, "loan01", "49743a1e…#6");
    }

    /** The measured shape of the real liquidation's loan UTxO — must pass untouched. */
    @Test
    void theSHAPEWEACTUALLYSAWOnChainIsAccepted() {
        assertDoesNotThrow(() -> check(
                of("lovelace", 3_000_000), of(COLLATERAL, 100_000_000), of(LOAN_NFT, 1)));
    }

    @Test
    void aStrayAssetIsREFUSED() {
        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                () -> check(of("lovelace", 3_000_000), of(COLLATERAL, 100_000_000),
                        of(LOAN_NFT, 1), of(STRAY, 1)));

        assertEquals(LiquidateTransactionBuilder.Refusal.LOAN_UTXO_CARRIES_UNDECLARED_ASSET,
                thrown.getReason());
    }

    /** ⚠ Actionability: the message must identify WHICH token, not merely that there is one. */
    @Test
    void theRefusalNAMESTheOffendingUnitAndBothPermittedOnes() {
        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                () -> check(of("lovelace", 1), of(STRAY, 7)));

        assertTrue(thrown.getMessage().contains(STRAY),
                "the stray unit must be named — that is what turns a diagnosis into an action: "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(COLLATERAL) && thrown.getMessage().contains(LOAN_NFT),
                "and what WAS permitted must be shown, or the reader cannot tell why it was rejected");
        assertTrue(thrown.getMessage().contains("loan01") && thrown.getMessage().contains("49743a1e"),
                "the loan and the utxo must be identified");
    }

    @Test
    void aLoanWithNoCollateralYetIsStillFine() {
        assertDoesNotThrow(() -> check(of("lovelace", 3_000_000), of(LOAN_NFT, 1)));
    }

    @Test
    void adaCollateralLoansCarryNoTokenBesidesTheirNFT() {
        LiquidateTransactionBuilder.refuseUndeclaredAssets(
                List.of(of("lovelace", 50_000_000), of(LOAN_NFT, 1)),
                "lovelace", LOAN_NFT, "loan02", "aa…#0");
    }

    @Test
    void caseDoesNotDecideTheAnswer() {
        assertDoesNotThrow(() -> check(of("lovelace", 1), of(COLLATERAL.toUpperCase(), 5)));
    }
}
