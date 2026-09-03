package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

/**
 * The profitability gate for <b>LiquidateAndConvert</b>, and the only place its arithmetic lives.
 *
 * <h2>A fresh model, because the pay-in-advance one does not transfer</h2>
 * The August floor of {@code -27_303_331} was measured on the <em>pay-in-advance</em> path, where the
 * bot fronts the principal and the accounting booked that outlay as pure cost while valuing the
 * collateral it received at nothing. <b>Convert is a different mechanism: the bot fronts no
 * principal.</b> Minswap's batcher supplies it, and both the success and refund receivers are the
 * asset manager owned by the lender bond — the bot never holds the collateral and never holds the
 * proceeds (findings §25.2). Reusing that number here would be the same category error in reverse,
 * so nothing is carried over.
 *
 * <h2>⛔ What the bot actually spends — and the one figure that is easy to miss</h2>
 * <pre>
 *   outlay = txFee + (collateral is ada ? 0 : 2_800_000)
 * </pre>
 * The second term is the validator's, read at the deployed sha {@code e0b818e}: when the collateral
 * is <b>not</b> ada, {@code lm_liquidate_and_convert_action} requires
 * {@code quantity_of(minswapOrderOutput.value, "", "") == 2800000} — the order output must carry
 * exactly 2.8 ada alongside the collateral, and that ada leaves with the order (Minswap's batcher fee
 * is 700,000 of it, and the receiver is the lender's asset manager, not the bot). <b>So a token
 * collateral costs the bot 2.8 ada it does not get back</b>, which is four to five times a typical
 * transaction fee and would dominate any model that omitted it. When the collateral IS ada the
 * validator constrains the order's lovelace to the swappable amount alone and there is no extra term.
 *
 * <p>⚠ <b>Provenance, stated rather than assumed:</b> this counts the whole 2.8 ada as the bot's,
 * which is the conservative reading. The loan input carries its own min-ada and it is not yet
 * measured how much of it {@code loan_claim_action} lets flow into the order output. If a later
 * measurement shows the loan funds part of it, this gate gets <em>less</em> strict, never more —
 * which is the safe direction to be wrong in.
 *
 * <p>The two datum-carrier outputs the action needs (their inline datums must hash to
 * {@code successDatumHash} and {@code refundDatumHash}) have <b>unconstrained addresses</b>, so the
 * builder pays them to the bot itself and their min-ada returns as bot-owned UTxOs in the same
 * transaction. They are working capital, not cost, and are deliberately absent from this arithmetic —
 * <b>on the invariant that the builder self-addresses them.</b> Send them anywhere else and this gate
 * becomes wrong.
 *
 * <h2>⚠ The income is real, in tokens, and unrealised — the mirror of the pay-in-advance bug</h2>
 * {@code liquidationFee = loanCollateralAmount * liquidationFeePerMille / 1000} is the residue the
 * validator leaves unconstrained, and the builder pays it to the bot <b>in the collateral asset</b>
 * (findings §25.3). Comparing it to a lovelace outlay requires the collateral oracle price, so what
 * this gate compares is an <em>oracle valuation of an unrealised token position</em> against ada the
 * bot has genuinely spent. That is a modelling choice and it is named here rather than buried: the
 * pay-in-advance floor went wrong by refusing to value an acquired asset at all, and the opposite
 * mistake — crediting a token position as though it were cash — is just as available.
 * <b>The margin is the lever for that risk:</b> an operator who does not trust the oracle valuation,
 * or the liquidity behind it, raises {@code profit-margin-lovelace} until they do.
 *
 * <h2>Why default-ON is defensible, and where the safety actually comes from</h2>
 * FluidTokens (Matteo, relayed 2026-09-03) confirmed the failure mode first-hand: if the Minswap order
 * does not fill, Minswap returns the <b>original collateral</b> to the asset manager and the lender
 * reclaims it as-is. The bot is finished the moment the order is created, whether or not it fills.
 * <b>And the bot's fee is subtracted before the swap</b>, so its position is identical either way:
 * <b>convert's profitability does not depend on the fill.</b> The worst case is a no-op for everyone,
 * not a loss for anyone — which is what makes a default-ON path honest, and why this gate must still
 * be real from the first boot rather than a placeholder.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class ConvertEconomics {

    private static final BigInteger PER_MILLE = BigInteger.valueOf(1000L);

    /**
     * {@code quantity_of(minswapOrderOutput.value, "", "") == 2800000} — the exact literal in
     * {@code lm_liquidate_and_convert_action.ak} at {@code e0b818e}, for a non-ada collateral.
     * Not a min-ada estimate and not ours to tune: a different figure fails the validator.
     */
    static final BigInteger ORDER_ADA_FOR_TOKEN_COLLATERAL = BigInteger.valueOf(2_800_000L);

    private final AppConfig.ConvertConfiguration configuration;
    private final AppConfig.Network network;

    public ConvertEconomics(AppConfig.ConvertConfiguration configuration, AppConfig.Network network) {
        this.configuration = configuration;
        this.network = network;
    }

    /**
     * Announce the stated bound at boot and refuse a negative one on mainnet — the same shape as the
     * compound and liquidation margins, for the same reason: a WARN on the mainnet path is a comment
     * rather than a guard, and "copy the working preview config" is the foreseeable operator action.
     */
    @PostConstruct
    void announceAndGuard() {
        BigInteger floor = configuration.getProfitMarginLovelace();
        log.info("CONVERT ECONOMICS enabled={} (default ON: this path fronts no capital and holds "
                        + "nothing); profit-margin={} lovelace, which the oracle value of the "
                        + "collateral-denominated liquidation fee less (txFee + order ada) must reach",
                configuration.isEnabled(), floor);

        if (floor == null || floor.signum() >= 0) {
            return;
        }
        String networkName = network == null ? null : network.getNetwork();
        // Fail-closed: anything that is not preview or preprod resolves to mainnet.
        boolean mainnet = networkName == null
                || (!"preview".equalsIgnoreCase(networkName) && !"preprod".equalsIgnoreCase(networkName));
        if (mainnet) {
            throw new IllegalStateException(("loans.liquidation.convert.profit-margin-lovelace is %s "
                    + "(negative) on network %s; a negative floor authorises converting at a cost to "
                    + "the operator — including for lender bonds whose liquidationFeePerMille is 0, "
                    + "which pay nothing at all — and is a preview-only override that must never be "
                    + "set on mainnet").formatted(floor, networkName));
        }
        log.warn("⛔ loans.liquidation.convert.profit-margin-lovelace is {} (negative) on network {} — "
                        + "the bot will convert AT A LOSS down to that bound, including for bonds whose "
                        + "liquidationFeePerMille is 0. This is a stated operator bound, not a disabled "
                        + "check.", floor, networkName);
    }

    /**
     * Decide one candidate. Checks fire cheapest-and-most-certain first, so a log line names the real
     * reason rather than a downstream symptom.
     *
     * @param bondAllowsConversion the lender bond's {@code shouldLiquidationConvertToPrincipal} — the
     *                             validator's first conjunct, and the lender's choice, not ours
     * @param collateralAmount     {@code loanCollateralAmount}: the collateral the loan input holds
     * @param feePerMille          {@code liquidationFeePerMille} from the live {@code LenderManagerDatum}
     * @param collateralIsAda      whether the collateral asset is ada, which decides the 2.8 ada term
     * @param collateralFeed       the oracle feed for the collateral asset, or {@code null} when there
     *                             is no usable one — the fee cannot be valued without it
     * @param txFee                the measured fee of the built transaction, in lovelace
     */
    public ConvertAssessment assess(boolean bondAllowsConversion,
                                    BigInteger collateralAmount,
                                    long feePerMille,
                                    boolean collateralIsAda,
                                    OraclePriceFeed collateralFeed,
                                    BigInteger txFee) {
        if (!configuration.isEnabled()) {
            return ConvertAssessment.refused(ConvertExclusion.NOT_ARMED);
        }
        if (!bondAllowsConversion) {
            return ConvertAssessment.refused(ConvertExclusion.BOND_FORBIDS_CONVERSION);
        }
        if (collateralFeed == null) {
            return ConvertAssessment.refused(ConvertExclusion.COLLATERAL_UNPRICEABLE);
        }

        BigInteger liquidationFee = liquidationFee(collateralAmount, feePerMille);
        BigInteger feeValue;
        try {
            feeValue = LoanFinance.toLovelace(Rational.fromInt(liquidationFee), collateralFeed).floor();
        } catch (RuntimeException e) {
            // Deliberately broad, and fail-closed. A feed can be unpriceable in three unrelated ways:
            // a POOLED variant is an outright `fail` in finance.ak and throws
            // UnsupportedOperationException; a zero denominator is rejected by Rational.required; and
            // the arithmetic itself can throw. All three mean the same thing here — we cannot value
            // the fee — and every one of them must refuse rather than let a default through.
            log.debug("collateral feed {} cannot be priced: {}", collateralFeed.token(), e.toString());
            return ConvertAssessment.refused(ConvertExclusion.COLLATERAL_UNPRICEABLE);
        }

        BigInteger orderAda = collateralIsAda ? BigInteger.ZERO : ORDER_ADA_FOR_TOKEN_COLLATERAL;
        BigInteger outlay = txFee.add(orderAda);
        BigInteger net = feeValue.subtract(outlay);
        BigInteger floor = configuration.getProfitMarginLovelace();
        boolean approved = net.compareTo(floor) >= 0;

        return new ConvertAssessment(approved,
                approved ? null : ConvertExclusion.NET_BELOW_FLOOR,
                liquidationFee, feeValue, txFee, orderAda, outlay, net, floor);
    }

    /**
     * {@code loanCollateralAmount * liquidationFeePerMille / 1000}, integer division, matching
     * {@code lm_liquidate_and_convert_action}'s expression exactly.
     *
     * <p>The truncation is deliberate and must not be rounded: the validator computes
     * {@code swappableCollateralAmount = loanCollateralAmount - equity - liquidationFee} from this
     * same expression, so a fee we round UP is collateral the order is short of, and the transaction
     * fails phase 2.
     */
    static BigInteger liquidationFee(BigInteger collateralAmount, long feePerMille) {
        return collateralAmount.multiply(BigInteger.valueOf(feePerMille)).divide(PER_MILLE);
    }
}
