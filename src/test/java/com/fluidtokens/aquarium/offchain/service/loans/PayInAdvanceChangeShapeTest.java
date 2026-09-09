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
     * ⛔ <b>THE SHAPE, INVERTED 2026-09-04 — and the inversion is the receipt for the fix.</b>
     *
     * <p>This class was written to pin the S-15 trap: <i>the bot's real ada comes back trapped beside
     * the collateral, and the only nominable thing returned is CCL's ~1-ada withdrawal dummy.</i> Its
     * own javadoc said <i>"IF THIS EVER GOES NON-ZERO THE TRAP IS CLOSED and this test should be
     * inverted"</i>. The builder now pays the bot's collateral out BY NAME, so it has, and this is
     * what it turned into.
     *
     * <p>⇒ <b>What it pins now:</b> the collateral arrives in its own output, and <b>ada-only output(s)
     * come back that the next liquidation can actually spend.</b> The measured shape:
     *
     * <pre>
     *   dummy      ~1,000,000 lovelace                        ada-only  → NOMINABLE (CCL trap 1)
     *   collateral    min-ada + 91,080,816 tokens             multi     → not nominable, by design
     *   change     the rest                                   ada-only  → NOMINABLE  ← the fix
     * </pre>
     *
     * ⚠ <b>The middle line is a real cost, not a free win.</b> A token-bearing output needs its own
     * min-ada (~1.6 ADA), funded by the bot on every liquidation and locked with the tokens. The trade
     * is that against ~30 ADA that was previously trapped and <b>compounding</b> — recoverable only by
     * a manual wallet reshape, which the 2026-08-25 incident showed is a real operation someone has to
     * notice they need. {@code LiquidationExecutor} subtracts the rider from the profit its gate
     * evaluates, so the honesty of that trade is enforced rather than asserted.
     */
    @Test
    void theBotsCollateralComesBackNamedAndItsAdaComesBackSpendable() {
        var fixture = LiquidatePayInAdvanceDryEvalTest.fixture();
        Transaction tx = LiquidatePayInAdvanceDryEvalTest.build(fixture);

        var collateral = fixture.loan().datum().collateral().assetType();
        List<TransactionOutput> toBot = outputsTo(tx, fixture.request().changeAddress());

        List<TransactionOutput> nominable = toBot.stream()
                .filter(o -> WalletInputSelection.nominable(asUtxo(o))).toList();
        List<TransactionOutput> tokenBearing = toBot.stream()
                .filter(o -> collateralIn(o, collateral.policyId(), collateral.assetName()).signum() > 0)
                .toList();

        assertEquals(1, tokenBearing.size(),
                "the collateral must arrive in exactly ONE named output — zero means CCL merged it back "
                        + "into change (trap 21) and the trap is silently back, more than one means it "
                        + "was split");

        assertTrue(nominable.size() >= 2,
                "⛔ THE S-15 PROPERTY. Before the fix exactly ONE nominable output came back — CCL's "
                        + "~1-ada withdrawal dummy — while ~30 ada sat trapped with the tokens. The "
                        + "bot's real change must now return ada-only and spendable, so the next "
                        + "liquidation has an input. Got " + nominable.size() + " nominable of "
                        + toBot.size() + " outputs to the bot");

        assertFalse(WalletInputSelection.nominable(asUtxo(tokenBearing.get(0))),
                "the token-bearing output is not itself nominable, and is not meant to be — Plutus "
                        + "collateral must be pure ada. That is the ~1.6 ADA rider, and it is the "
                        + "price of the other two lines being clean");
    }

    /**
     * ⛔ THE CONSEQUENCE, inverted with it: the ada the bot can spend NEXT cycle is now the bulk of
     * what it committed, not a rounding error.
     *
     * <p>Before the fix this asserted the opposite — that under a twentieth came back spendable. The
     * ratio is the operational claim in both directions, which is why it is a ratio and not a pinned
     * constant: what matters is that a working liquidation stops draining the wallet's <i>nominable</i>
     * balance, whatever the sizes happen to be.
     */
    @Test
    void theNominableBalanceThatComesBackIsNoLongerNegligible() {
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

        assertTrue(nominableBack.compareTo(trappedAda) > 0,
                ("the spendable half must now exceed the trapped half — spendable %s, trapped %s. If "
                        + "this inverts again the named output stopped being emitted, and the "
                        + "WALLET_INPUT_TOO_SMALL diagnosis returns with it.")
                        .formatted(nominableBack, trappedAda));

        // ⚠ THE RATIO IS OVER WHAT COMES BACK, NOT OVER WHAT WENT IN — and the first draft of this
        // assertion got that wrong, which is worth leaving recorded. Most of the 60,000,000 input does
        // not "come back" at all: it pays the lender ~20.9 ADA in advance and the transaction fee.
        // Measuring the returned ada against the INPUT therefore fails for a reason that has nothing
        // to do with S-15, and would have read as the fix not working.
        //
        // Measured 2026-09-04: of 31,035,761 lovelace returned, 29,859,131 is spendable and 1,176,630
        // is the min-ada rider locked with the tokens — 96.2%. Before the fix the same rig returned
        // 1,000,000 spendable and 30,038,621 trapped.
        BigInteger returned = nominableBack.add(trappedAda);
        assertTrue(nominableBack.multiply(BigInteger.valueOf(10))
                        .compareTo(returned.multiply(BigInteger.valueOf(9))) > 0,
                ("over 90%% of the ada returned to the bot must be spendable. Returned %s, spendable "
                        + "%s, trapped %s — the trapped part is the min-ada rider on the collateral "
                        + "output and nothing else. (Wallet input was %s; most of it went to the "
                        + "lender and the fee, which is why the ratio is over the RETURN.)")
                        .formatted(returned, nominableBack, trappedAda, spent));
    }
}
