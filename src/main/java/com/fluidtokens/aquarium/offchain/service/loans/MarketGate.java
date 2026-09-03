package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * ⛔ <b>Per-market policy: may the bot act in this market, which transaction does it build, and how
 * much principal may it front?</b>
 *
 * <h2>Two independent questions, two fields — because neither implies the other</h2>
 * {@code mode} is the market's <b>execution state</b> (DISABLED / SHADOW / LIVE); {@code action} is its
 * <b>strategy</b> (CONVERT / ANTICIPATE). Shadowing an anticipate market and arming a convert one are
 * both meaningful, so a shape that folded one into the other could not express what an operator needs.
 * Giovanni's ruling of 2026-09-03 separated them explicitly.
 *
 * <h2>⛔ The global mode is a CEILING, not a default</h2>
 * <pre>effective(market) = min(loans.liquidation.mode, market.mode)   over DISABLED &lt; SHADOW &lt; LIVE</pre>
 * A market may be MORE restrictive than the node, never less. Without this a per-market {@code LIVE}
 * would let a node whose posture is {@code shadow} actually submit — making the node-level dial a lie,
 * and that dial is what every operator and the whole submit-veto suite rely on. The clamp is announced
 * at boot by {@code LiquidationConfiguration.validateMarkets()}, because an operator who wrote
 * {@code LIVE} and got {@code SHADOW} has to be told.
 *
 * <h2>An UNLISTED market is CONVERT at the node's own mode</h2>
 * So an empty list means "convert everywhere, at whatever posture the node is in", and listing a market
 * is how an operator <b>deviates</b>. In particular {@code loans.liquidation.mode: shadow} shadows every
 * market with no list at all — which matters because shadow is convert's only mainnet rehearsal
 * (findings §29.5): needing to enumerate markets first would make the rehearsal cost scale with the
 * market count.
 *
 * <h2>⚠ min() is a GATE on the cap, not a clamp — because the amount is not ours to choose</h2>
 * Giovanni's spec: <i>"repayable amount (principal that can be anticipated) must be min(balance, market
 * cap) and only enabled if market is enabled."</i> The protocol fixes what must be deposited:
 * {@code lm_liquidate_and_pay_in_advance_action} requires the repayment output to hold at least
 * {@code convertedLoanCollateralToPrincipalAmount}. <b>The bot cannot front part of a loan.</b> So when
 * the cap is below the required amount, {@code min(required, cap) < required} and the only lawful
 * response is to <b>refuse the candidate</b> — never to build a smaller deposit, which the validator
 * would reject after the fee was spent.
 *
 * <p><b>The comparison is {@code >=}, on the validator's authority and not on preference.</b>
 * {@code validate_repayment_output} at {@code e0b818e} requires
 * {@code quantity_of(repaymentOutput.value, …) >= repaymentAmount}, so a deposit exactly equal to the
 * requirement is valid on chain. Giovanni's phrasing — <i>"the cap is higher than the principal
 * repayment due"</i> — reads as a strict {@code >}; a strict {@code >} would refuse a candidate sitting
 * exactly on the operator's own stated bound, which raises every cap by one lovelace without saying so.
 * ⚠ For the same reason {@code required} is {@code convertedLoanCollateralToPrincipalAmount} and
 * <b>not {@code remainingDebt}</b>: "the principal repayment due" is the intuitive figure and the wrong
 * one.
 *
 * <h2>A market is keyed by its PRINCIPAL asset</h2>
 * The cap bounds an amount the bot pays out, denominated in the loan's principal asset, so keying by
 * that asset makes the cap's units self-evident. <b>The alternative worth knowing:</b> concentration
 * risk actually lives in the collateral the bot ends up HOLDING, so a collateral-keyed cap would bound a
 * different and also-real exposure. Both are defensible; this one matches the formula as stated.
 */
@Slf4j
public final class MarketGate {

    /** Why a candidate was refused by this gate. */
    public enum Refusal {
        /** The market's effective mode is {@code DISABLED}: the bot does nothing here. */
        MARKET_DISABLED,
        /**
         * The MARKET asked for {@code SHADOW} on a {@code LIVE} node. ⚠ <b>Temporary, and deliberately
         * fail-closed:</b> until the executor grows its {@code MARKET_NOT_LIVE} submit veto there is no
         * per-market build-then-stop path, so this refuses BEFORE the build rather than behaving as
         * LIVE. A safety setting briefly loosened between stages is worse than one that arrives late.
         *
         * <p>⚑ It deliberately does NOT fire for a node-wide {@code SHADOW}: that already has a working
         * build-then-veto path ({@code S1 MODE_NOT_LIVE}), and short-circuiting it here would destroy
         * the existing shadow feature instead of protecting anything.
         */
        MARKET_SHADOW_NOT_YET_IMPLEMENTED,
        /** The market's action is {@code CONVERT}, so pay-in-advance is not the chosen mechanism here. */
        MARKET_ACTION_IS_CONVERT,
        /** {@code ANTICIPATE}, but the cap is below what the protocol requires the bot to front. */
        ABOVE_MARKET_CAP
    }

    /**
     * @param anticipatable {@code min(required, cap)} — exactly the figure Giovanni specified
     * @param required      what the protocol demands be deposited; not ours to choose
     * @param cap           the operator's stated cap, zero when no cap governs
     * @param refusal       {@code null} when the candidate may proceed
     */
    public record Decision(BigInteger anticipatable, BigInteger required, BigInteger cap,
                           Refusal refusal, String detail) {
        public boolean allowed() {
            return refusal == null;
        }
    }

    private final List<Market> markets;
    private final Mode globalMode;

    public MarketGate(AppConfig.LiquidationConfiguration configuration) {
        this.markets = configuration == null || configuration.getMarkets() == null
                ? List.of() : List.copyOf(configuration.getMarkets());
        this.globalMode = configuration == null || configuration.getMode() == null
                ? Mode.DISABLED : configuration.getMode();
    }

    /** The entry governing this asset, or {@code null} when the market is unlisted. */
    public Market marketFor(AssetType principal) {
        String unit = principal == null ? null : principal.toUnit();
        if (unit == null) {
            return null;
        }
        return markets.stream()
                .filter(m -> m != null && unit.equalsIgnoreCase(m.getUnit()))
                .findFirst().orElse(null);
    }

    /** {@code min(globalMode, market.mode)}; an unlisted market inherits the global mode outright. */
    public Mode effectiveMode(AssetType principal) {
        Market market = marketFor(principal);
        Mode wanted = market == null || market.getMode() == null ? globalMode : market.getMode();
        return wanted.ordinal() < globalMode.ordinal() ? wanted : globalMode;
    }

    /** The strategy for this market. Unlisted markets convert — Giovanni's default. */
    public Action actionFor(AssetType principal) {
        Market market = marketFor(principal);
        return market == null || market.getAction() == null ? Action.CONVERT : market.getAction();
    }

    /**
     * Decide a PAY-IN-ADVANCE candidate.
     *
     * @param principal the loan's principal asset — the market
     * @param required  {@code convertedLoanCollateralToPrincipalAmount}: what must be fronted
     */
    public Decision decide(AssetType principal, BigInteger required) {
        String unit = principal == null ? null : principal.toUnit();
        Mode effective = effectiveMode(principal);

        if (effective == Mode.DISABLED) {
            return refuse(Refusal.MARKET_DISABLED, required,
                    "market " + unit + " is DISABLED (node mode " + globalMode + "), so the bot does "
                            + "nothing in it");
        }
        // ⛔ Only when the SHADOW came from the MARKET. A node-wide SHADOW already has a working
        // build-then-veto path — the executor's S1 MODE_NOT_LIVE — and short-circuiting it here would
        // destroy the existing shadow feature rather than protect anything. A market-level SHADOW on a
        // LIVE node has no veto yet, and that is the case that must fail closed.
        if (effective == Mode.SHADOW && globalMode == Mode.LIVE) {
            return refuse(Refusal.MARKET_SHADOW_NOT_YET_IMPLEMENTED, required,
                    "market " + unit + " is SHADOW on a LIVE node. The per-market build-then-veto path "
                            + "is not wired yet, so this refuses rather than behaving as LIVE");
        }

        Action action = actionFor(principal);
        if (action != Action.ANTICIPATE) {
            return refuse(Refusal.MARKET_ACTION_IS_CONVERT, required,
                    "market " + unit + " is action: " + action + ", so pay-in-advance is not the "
                            + "mechanism here; set action: ANTICIPATE with a cap to force it");
        }

        // ANTICIPATE is validated at startup to carry a cap, so this cannot be null on a live node.
        BigInteger cap = Objects.requireNonNull(marketFor(principal).getCap(),
                "action: ANTICIPATE without a cap should have aborted startup");

        // min(balance, cap), exactly as specified.
        BigInteger anticipatable = required.min(cap);
        if (anticipatable.compareTo(required) < 0) {
            return new Decision(anticipatable, required, cap, Refusal.ABOVE_MARKET_CAP,
                    ("market %s is capped at %s but this candidate requires %s to be fronted; the "
                            + "protocol does not allow fronting part of a loan, so it is refused "
                            + "rather than reduced").formatted(unit, cap, required));
        }
        return new Decision(anticipatable, required, cap, null,
                "market " + unit + " allows " + required + " of " + cap);
    }

    private static Decision refuse(Refusal why, BigInteger required, String detail) {
        return new Decision(BigInteger.ZERO, required, BigInteger.ZERO, why, detail);
    }
}
