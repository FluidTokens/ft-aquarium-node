package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import java.util.function.Function;
import java.util.Optional;
import java.math.BigInteger;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * The routing seam for a <em>convert</em> liquidation — a loan whose lender bond carries
 * {@code shouldLiquidationConvertToPrincipal == True}, which the plain {@code Liquidate} path refuses
 * ({@code lm_liquidate_action.ak:143}) and the {@code LiquidateAndPayInAdvance} action requires.
 * {@link LiquidationExecutor} selects between the plain builder and this seam by that one datum flag.
 *
 * <h2>Assembly only — no arming, no submitting</h2>
 * This class assembles a {@link LiquidatePayInAdvanceTransactionBuilder.Request} and calls
 * {@code build(request)}. That builder is submit-incapable (a {@code null} transaction processor; see
 * its class javadoc), so what comes back is an unsigned {@link Transaction} that flows into the
 * executor's unchanged pricing + eight-veto chain exactly as a plain-path transaction does. Nothing
 * here signs, submits, or flips a veto.
 *
 * <h2>Refusal is a clean REFUSED row, not a crash</h2>
 * The promoted builder models only the real {@code f855d1b4…} shape: an <b>ada principal</b> priced
 * through the collateral oracle, and a <b>strictly positive equity</b> (it throws
 * {@code IllegalStateException} on equity ≤ 0). A convert loan outside that shape is not an error to
 * quarantine — it is a candidate this seam cannot yet model — so both preconditions are checked
 * <em>before</em> the builder is ever called and signalled with {@link PayInAdvanceNotModelledException},
 * which the executor maps to a {@code REFUSED} decision. The builder is never handed a shape it would
 * throw on, and no {@link Transaction} is produced for one.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class PayInAdvanceLiquidationRouter {

    /**
     * A convert loan the promoted pay-in-advance builder cannot yet model — a non-ada principal, or a
     * non-positive equity. Deliberately declared <em>here</em>, not as a
     * {@link LiquidateTransactionBuilder.Refusal} constant: this is the routing seam's own clean
     * refusal, it never reaches the plain builder, and {@link LiquidationExecutor} turns it into a
     * {@code REFUSED} row whose reason is this exception's message.
     */
    /**
     * The market gate refused: the market is disabled, or the principal this candidate requires the
     * bot to front exceeds the operator's stated cap. A clean REFUSED row and a separate type from
     * {@code PayInAdvanceNotModelledException} — <b>"we will not" and "we cannot yet" are different
     * statements about a candidate</b>, and collapsing them would hide a policy decision inside a
     * capability gap.
     */
    public static final class MarketGateRefusedException extends RuntimeException {

        MarketGateRefusedException(String message) {
            super(message);
        }
    }

    static final class PayInAdvanceNotModelledException extends RuntimeException {

        PayInAdvanceNotModelledException(String message) {
            super(message);
        }
    }

    /**
     * No nominable wallet UTxO can fund this convert liquidation's lender payout. A <b>refusal</b>,
     * not a machinery failure: it is a true statement about this candidate against this wallet, it is
     * reproducible next cycle, and it becomes buildable the moment the wallet is topped up — so it is
     * neither quarantined nor logged as an error.
     */
    public static class WalletInputTooSmallException extends RuntimeException {
        public WalletInputTooSmallException(String message) {
            super(message);
        }
    }

    private final LoansContractRegistry registry;

    private final CardanoConverters converters;

    private final AppConfig.LiquidationConfiguration configuration;

    private final LiquidatePayInAdvanceTransactionBuilder builder;

    /**
     * Parsed lazily from the configured string so a test can drive the router without a Spring
     * context, and rebuilt per call rather than cached — the configuration object is the single
     * source of truth and nothing here should be able to hold a staler copy of it than it does.
     */
    private MarketGate marketGate() {
        return new MarketGate(configuration == null ? null : configuration.getMarkets());
    }

    public PayInAdvanceLiquidationRouter(LoansContractRegistry registry,
                                         CardanoConverters converters,
                                         AppConfig.LiquidationConfiguration configuration,
                                         LiquidatePayInAdvanceTransactionBuilder builder) {
        this.registry = registry;
        this.converters = converters;
        this.configuration = configuration;
        this.builder = builder;
    }

    /**
     * Assembles and builds the pay-in-advance liquidation for one already-resolved convert candidate,
     * or refuses it cleanly.
     *
     * @param validFromMillis the requested window lower bound — the same {@code now - backdate} the
     *                        plain path passes; clamped inwards to a whole slot here
     * @param validToMillis   the requested window upper bound — the same {@code now + window} the plain
     *                        path passes
     * @throws WalletInputTooSmallException      when no nominable wallet utxo covers the lender
     *                                          payout this liquidation must fund
     * @throws PayInAdvanceNotModelledException when the principal is not ada, or the equity is not
     *                                          strictly positive — a clean refusal, no transaction built
     */
    Transaction buildConvertLiquidation(LiquidationAssessment assessment,
                                        Utxo loanUtxo,
                                        Utxo bondUtxo,
                                        Utxo configUtxo,
                                        Utxo lmConfigUtxo,
                                        Map<String, OracleEntry> oraclesByUnit,
                                        Function<BigInteger, Optional<Utxo>> walletSelector,
                                        long validFromMillis,
                                        long validToMillis) {
        LoanDatum datum = assessment.loan().datum();

        // Precondition guards, before the builder is touched. The promoted builder prices the
        // principal leg as ada and throws IllegalStateException on equity <= 0; a convert loan outside
        // that shape is a candidate this seam cannot yet model, so it is a CLEAN refusal here rather
        // than a crash or a quarantine downstream.
        if (!datum.principalAsset().isAda()) {
            throw new PayInAdvanceNotModelledException(
                    "pay-in-advance not yet modelled for non-ada principal");
        }
        if (assessment.equity() == null || assessment.equity().signum() <= 0) {
            throw new PayInAdvanceNotModelledException(
                    "pay-in-advance not yet modelled for non-positive equity");
        }

        // The collateral oracle is found by the oracle NFT the loan datum names — the same key the
        // executor's snapshot and the plain builder use, never the priced asset.
        OracleEntry collateralOracle =
                oraclesByUnit.get(datum.collateral().oracleTokenAsset().toUnit());

        // The window and the redeemer's validFrom are derived EXACTLY as the plain path
        // (LiquidateTransactionBuilder.build ~578-580) and the dry-eval fixture do: the requested
        // millisecond window is clamped inwards to whole slots, and the redeemer's validFromMillis is
        // the POSIX time slotFrom converts back to — the instant loan_claim_action recomputes the debt
        // at, so the on-chain equality holds.
        long[] slots = validitySlots(validFromMillis, validToMillis);
        long slotFromMillis = millisOf(converters.slot().slotToTime(slots[0]));
        // The upper bound the same way: the builder's V3 oracle-window check must be made against the
        // window the LEDGER will see, which is the slot-derived pair, not the caller's raw millis.
        long slotToMillis = millisOf(converters.slot().slotToTime(slots[1]));

        // T-052 — THE WALLET INPUT IS CHOSEN TO COVER THIS LIQUIDATION, NOT PICKED BLIND.
        //
        // This is the principal-repaying path: the collateral is a token, so the ada the lender is
        // paid comes out of the BOT'S OWN WALLET. That amount is
        // convertedLoanCollateralToPrincipalAmount, and it is knowable here because numbers() reads
        // the loan, the bond, the oracle and the instant — NEVER the wallet. The caller hands in a
        // selector rather than a UTxO, so the choice is made where the amount is known, and the
        // builder still knows nothing about liquidation types.
        //
        // ⚠ remainingDebt is NOT used as a proxy for this figure, though it is the intuitive one and
        // the executor already holds it. LoanFinance.redeemerEquity returns ZERO OUTRIGHT when
        // partialLiquidationPenaltyPerMille is negative, so equity is not always the surplus and the
        // lender's converted share is not bounded by the debt. The exact number costs nothing here;
        // a wrong bound costs the candidate, and fails at evaluation with an EMPTY ScriptFailures map
        // that reads as "a script refused" rather than "you are short" (measured 2026-08-24).
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers =
                builder.numbers(assessment.loan(), assessment.bond(), collateralOracle, slotFromMillis);
        BigInteger lenderPayout = numbers.convertedLoanCollateralToPrincipalAmount();

        // ⛔ THE MARKET GATE — before a wallet utxo is chosen and before anything is built.
        //
        // `lenderPayout` IS the principal this candidate would have the bot anticipate, so this is
        // Giovanni's rule applied to the number it is about: anticipatable = min(balance, cap), and
        // only when the market is enabled. It sits here rather than beside the profitability floors
        // because it is a POLICY question — "will we take this exposure at all" — and it is settled
        // before any economics, exactly as a disabled market should be.
        MarketGate.Decision market = marketGate().decide(datum.principalAsset(), lenderPayout);
        if (!market.allowed()) {
            throw new MarketGateRefusedException(
                    "%s: %s (anticipatable %s of a required %s)".formatted(
                            market.refusal(), market.detail(), market.anticipatable(), market.required()));
        }
        Utxo walletUtxo = walletSelector.apply(lenderPayout)
                .orElseThrow(() -> new WalletInputTooSmallException(
                        ("no ada-only wallet utxo can fund this convert liquidation: it repays the "
                                + "lender %s lovelace on top of the fee, and no single nominable utxo "
                                + "covers that. Fund the wallet with one ada-only utxo of at least "
                                + "that amount plus fee headroom.").formatted(lenderPayout)));

        LiquidatePayInAdvanceTransactionBuilder.Request request =
                new LiquidatePayInAdvanceTransactionBuilder.Request(
                        assessment.loan(), loanUtxo, assessment.bond(), bondUtxo, walletUtxo,
                        configUtxo, lmConfigUtxo, collateralOracle, slotFromMillis, slotToMillis,
                        slots[0], slots[1],
                        // The bot keeps the collateral and pays change back to itself: the fee/collateral
                        // wallet UTxO is one of its own, so its address is the change address — the same
                        // identity account.baseAddress() carries on the plain path.
                        walletUtxo.getAddress(),
                        // Published reference scripts, exactly as the plain path threads them
                        // (LiquidationExecutor.java:479-485): an unset coordinate leaves that validator
                        // inline; preview sets only loan-claim-action, which brings the convert
                        // liquidation under maxTxSize.
                        configuration.getReferenceScripts(),
                        // Same operator setting the plain path uses, threaded the same way. Without it
                        // this path builds transactions the oracle feed cannot cover and the validator
                        // aborts with an empty trace — see the builder's V3 comment.
                        configuration.getOracleWindowMarginSeconds() * 1000L);
        return builder.build(request);
    }

    /**
     * Slots for the requested millisecond window, clamped <em>inwards</em> — the same rule
     * {@code LiquidateTransactionBuilder.validitySlots} applies, so the interval the transaction claims
     * is contained by the one the guards proved safe.
     */
    private long[] validitySlots(long validFromMillis, long validToMillis) {
        long slotFrom = converters.time().toSlot(utc(validFromMillis));
        if (millisOf(converters.slot().slotToTime(slotFrom)) < validFromMillis) {
            slotFrom += 1;
        }
        long slotTo = converters.time().toSlot(utc(validToMillis));
        if (millisOf(converters.slot().slotToTime(slotTo)) > validToMillis) {
            slotTo -= 1;
        }
        return new long[]{slotFrom, slotTo};
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
