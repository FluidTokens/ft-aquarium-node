package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>S-15 — what an ANTICIPATE liquidation leaves the wallet, measured off the built transaction.</b>
 *
 * <h2>The gap this fills</h2>
 * {@code docs/change-output-enumeration.md} enumerates every route by which a native asset reaches the
 * bot's change output and concludes <b>seven of eight close</b>. That enumeration is <b>complete for
 * the PLAIN path and silent about this one</b>: every line reference in it is to
 * {@code LiquidateTransactionBuilder}, where the bot's take is the liquidation-fee slice and is
 * therefore <i>computable and payable by name</i>. <b>On the pay-in-advance path there is no by-name
 * payout of the bot's take at all</b> — the outputs are the bond echo, the borrower's equity and the
 * lender's ada, and <i>"the bot keeps the collateral"</i> is implemented by letting it fall to change.
 *
 * <h2>⚠ THE MEASUREMENT CORRECTED THE OBVIOUS PREDICTION, so it is stated as measured</h2>
 * The natural expectation is <i>"change is multi-asset, therefore nothing nominable comes back,
 * therefore the bot dies after one liquidation"</i>. <b>That is wrong.</b> Two outputs return to the
 * bot's address, and they are not the same kind of thing:
 *
 * <pre>
 *   out#0  1,000,000 lovelace                                   ada-only  → NOMINABLE
 *   out#4 30,038,621 lovelace + 91,080,816 collateral tokens    multi     → NOT nominable
 * </pre>
 *
 * out#0 is <b>CCL trap 1's dummy output</b>, which any withdrawal-carrying transaction gets at the
 * change address — so a nominable UTxO does come back, and the wallet is not bricked. <b>But it is one
 * ada</b>, while all the real ada change is trapped beside the token.
 *
 * <p>⇒ <b>The true failure mode is not "no nominable UTxO", it is a collapsing nominable BALANCE.</b>
 * Each anticipate liquidation turns one large ada-only input into ~1 ada that can be spent again and
 * ~30 ada that cannot. The next candidate is refused with {@code WALLET_INPUT_TOO_SMALL} — a different
 * message, a different diagnosis, and one that reads like "top up the wallet" rather than "your ada is
 * stuck". <b>Getting that distinction wrong costs an operator the whole diagnosis</b>, which is why
 * this class asserts the shape rather than the conclusion.
 *
 * <p>⚠ On mainnet the trapped side is larger: findings §54.4 measures 100,000,000 FLDT and ~20.9 ada
 * fronted per liquidation. <b>A wallet reshape is not a one-time repair here — the trap re-forms every
 * time the bot succeeds.</b>
 *
 * <h2>What this class does NOT do</h2>
 * <b>It does not fix it.</b> The fix is an output paying the bot's collateral out by name, and adding
 * an output <b>shifts the absolute output indexes the validators are handed</b>
 * ({@code assetOutputIndexes}, {@code lenderBondOutputIndex}, {@code loan_claim_action}'s forced slot).
 * That is a change to the on-chain-visible layout with an evaluation cost — a design decision, not a
 * hardening tweak. This class exists so the property is measured and cannot regress silently while
 * that decision is open.
 */
class PayInAdvanceChangeShapeTest {

    /** Outputs located by ADDRESS, never by index — trap 1 is exactly why an index may not be assumed. */
    private static List<TransactionOutput> outputsTo(Transaction tx, String address) {
        return tx.getBody().getOutputs().stream().filter(o -> address.equals(o.getAddress())).toList();
    }

    private static Utxo asUtxo(TransactionOutput output) {
        return Utxo.builder()
                .txHash("00".repeat(32)).outputIndex(0).address(output.getAddress())
                .amount(Stream.concat(
                        Stream.of(Amount.lovelace(output.getValue().getCoin())),
                        output.getValue().getMultiAssets() == null ? Stream.<Amount>of()
                                : output.getValue().getMultiAssets().stream()
                                        .flatMap(ma -> ma.getAssets().stream().map(a -> Amount.asset(
                                                ma.getPolicyId() + HexUtil.encodeHexString(a.getNameAsBytes()),
                                                a.getValue()))))
                        .toList())
                .build();
    }

    private static BigInteger collateralIn(TransactionOutput output, String policyId, String assetNameHex) {
        if (output.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return output.getValue().getMultiAssets().stream()
                .filter(ma -> policyId.equals(ma.getPolicyId()))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> assetNameHex.equals(HexUtil.encodeHexString(a.getNameAsBytes())))
                .map(a -> a.getValue())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * ⛔ THE SHAPE: the bot gets back exactly one nominable output and one that is not, and the
     * collateral it acquired is on the second.
     */
    @Test
    void theBotGetsBackOneTinyNominableOutputAndItsRealAdaIsTrappedWithTheCollateral() {
        var fixture = LiquidatePayInAdvanceDryEvalTest.fixture();
        Transaction tx = LiquidatePayInAdvanceDryEvalTest.build(fixture);

        var collateral = fixture.loan().datum().collateral().assetType();
        List<TransactionOutput> toBot = outputsTo(tx, fixture.request().changeAddress());
        assertEquals(2, toBot.size(),
                "the bot's address receives the withdrawal dummy (CCL trap 1) AND the change; if this "
                        + "moved, every figure below describes a transaction that no longer exists");

        List<TransactionOutput> nominable = toBot.stream()
                .filter(o -> WalletInputSelection.nominable(asUtxo(o))).toList();
        List<TransactionOutput> trapped = toBot.stream()
                .filter(o -> !WalletInputSelection.nominable(asUtxo(o))).toList();

        assertEquals(1, nominable.size(),
                "exactly one output comes back spendable. ⚠ Not zero — the obvious prediction that a "
                        + "multi-asset change bricks the wallet outright is WRONG, and an operator told "
                        + "that would look for the wrong symptom");
        assertEquals(1, trapped.size(), "and exactly one is trapped");

        assertEquals(BigInteger.ZERO,
                collateralIn(nominable.get(0), collateral.policyId(), collateral.assetName()),
                "the nominable output is the withdrawal dummy and holds no collateral");
        assertTrue(collateralIn(trapped.get(0), collateral.policyId(), collateral.assetName()).signum() > 0,
                "the anticipate path is DEFINED by the bot acquiring the collateral, so a zero here "
                        + "means the path changed rather than that the trap closed");
    }

    /**
     * ⛔ THE CONSEQUENCE: the ada the bot can spend NEXT cycle is a rounding error beside the ada it
     * just committed.
     *
     * <p>The assertion is a ratio rather than a pinned constant, because the constant is fixture-bound
     * and the <b>ratio is the operational claim</b>: an anticipate liquidation drains the wallet's
     * <i>nominable</i> balance almost entirely, whatever the sizes.
     */
    @Test
    void theNominableBalanceThatComesBackIsANegligibleFractionOfWhatWentIn() {
        var fixture = LiquidatePayInAdvanceDryEvalTest.fixture();
        Transaction tx = LiquidatePayInAdvanceDryEvalTest.build(fixture);

        BigInteger spent = fixture.request().walletUtxo().getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity).reduce(BigInteger.ZERO, BigInteger::add);

        List<TransactionOutput> toBot = outputsTo(tx, fixture.request().changeAddress());
        BigInteger nominableBack = toBot.stream().filter(o -> WalletInputSelection.nominable(asUtxo(o)))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger trappedAda = toBot.stream().filter(o -> !WalletInputSelection.nominable(asUtxo(o)))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);

        assertTrue(trappedAda.compareTo(nominableBack) > 0,
                "more of the bot's returned ada must be trapped than spendable for this finding to "
                        + "hold; spendable=" + nominableBack + " trapped=" + trappedAda);

        // ⛔ Under a twentieth of the ada committed comes back in spendable form.
        assertTrue(nominableBack.multiply(BigInteger.valueOf(20)).compareTo(spent) < 0,
                ("⛔ THE OPERATIONAL CLAIM. Wallet input %s lovelace; spendable next cycle %s; trapped "
                        + "beside the collateral %s. The next candidate is refused with "
                        + "WALLET_INPUT_TOO_SMALL — which reads as 'top up the wallet' when the truth "
                        + "is 'your ada is stuck with the tokens you just earned'. If this ever fails "
                        + "because MORE comes back spendable, the layout changed and this whole class "
                        + "should be re-derived rather than relaxed.")
                        .formatted(spent, nominableBack, trappedAda));
    }
}
