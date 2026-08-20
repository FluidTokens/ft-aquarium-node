package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
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
    static final class PayInAdvanceNotModelledException extends RuntimeException {

        PayInAdvanceNotModelledException(String message) {
            super(message);
        }
    }

    private final LoansContractRegistry registry;

    private final CardanoConverters converters;

    private final AppConfig.LiquidationConfiguration configuration;

    private final LiquidatePayInAdvanceTransactionBuilder builder;

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
     * @throws PayInAdvanceNotModelledException when the principal is not ada, or the equity is not
     *                                          strictly positive — a clean refusal, no transaction built
     */
    Transaction buildConvertLiquidation(LiquidationAssessment assessment,
                                        Utxo loanUtxo,
                                        Utxo bondUtxo,
                                        Utxo configUtxo,
                                        Utxo lmConfigUtxo,
                                        Map<String, OracleEntry> oraclesByUnit,
                                        Utxo walletUtxo,
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

        LiquidatePayInAdvanceTransactionBuilder.Request request =
                new LiquidatePayInAdvanceTransactionBuilder.Request(
                        assessment.loan(), loanUtxo, assessment.bond(), bondUtxo, walletUtxo,
                        configUtxo, lmConfigUtxo, collateralOracle, slotFromMillis, slots[0], slots[1],
                        // The bot keeps the collateral and pays change back to itself: the fee/collateral
                        // wallet UTxO is one of its own, so its address is the change address — the same
                        // identity account.baseAddress() carries on the plain path.
                        walletUtxo.getAddress(),
                        // Published reference scripts, exactly as the plain path threads them
                        // (LiquidationExecutor.java:479-485): an unset coordinate leaves that validator
                        // inline; preview sets only loan-claim-action, which brings the convert
                        // liquidation under maxTxSize.
                        configuration.getReferenceScripts());
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
