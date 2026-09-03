package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⛔ <b>How much principal the bot may ANTICIPATE on a pay-in-advance liquidation.</b>
 *
 * <p>Giovanni's spec, 2026-09-03: <i>"repayable amount (principal that can be anticipated) must be
 * min(balance, market cap) and only enabled if market is enabled."</i>
 *
 * <h2>Why this gate exists on THIS path only</h2>
 * Pay-in-advance is the one action where <b>the bot fronts its own capital</b>: it deposits
 * {@code oracle_convert(collateral − equity − liquidationFee)} in the principal asset and takes the
 * collateral. A plain liquidation fronts nothing, and a convert liquidation fronts nothing either —
 * Minswap's batcher supplies the principal (findings §25). <b>So this is the only path with an
 * exposure to cap</b>, which is exactly the one Giovanni named as risky.
 *
 * <h2>A market is keyed by its PRINCIPAL asset — the decision, with its reason</h2>
 * The cap bounds an amount the bot pays out, and that amount is denominated in the loan's principal
 * asset. Keying the market by that asset makes the cap's units self-evident: {@code lovelace:500000000}
 * is five hundred ada of ada-principal exposure and cannot be read as anything else. Keying by
 * collateral would have made the cap's unit differ from the thing it limits.
 * <b>The alternative worth knowing:</b> concentration risk actually lives in the collateral the bot
 * ends up HOLDING, so a collateral-keyed cap would bound a different and also-real exposure. Both are
 * defensible; this one matches the formula as stated, and the two can coexist later.
 *
 * <h2>⚠ min() is a GATE, not a clamp — because the amount is not ours to choose</h2>
 * The protocol fixes what must be deposited: {@code lm_liquidate_and_pay_in_advance_action} requires
 * the repayment output to hold at least {@code convertedLoanCollateralToPrincipalAmount}. <b>The bot
 * cannot front part of a loan.</b> So when the cap is below the required amount,
 * {@code min(required, cap) < required} and the only lawful response is to <b>refuse the
 * candidate</b> — never to build a smaller deposit, which the validator would reject after the fee
 * was spent. The formula is implemented exactly as stated and the refusal falls out of it.
 *
 * <h2>Configuration: one key, because two that can disagree are worse than one that cannot</h2>
 * <pre>
 *   loans.liquidation.markets: "lovelace:500000000,&lt;policyId&gt;&lt;assetName&gt;:1000000000"
 * </pre>
 * <b>Being listed IS being enabled</b>, and an entry carries its own cap — so a market cannot be
 * enabled without a cap, or capped without being enabled, and the two can never contradict.
 * <b>The default is empty, which disables every market</b>: a market the operator has not explicitly
 * turned on is off, per the standing defensive-defaults ruling. An explicit {@code asset:0} is a
 * documented disable — {@code min(required, 0) == 0}, so it refuses while recording the intent.
 */
@Slf4j
public final class MarketGate {

    /** Why a pay-in-advance candidate was refused by this gate. */
    public enum Refusal {
        /** The market is not listed, so it is disabled. The default state of every market. */
        MARKET_DISABLED,
        /** Listed, but the cap is below what the protocol requires the bot to front. */
        ABOVE_MARKET_CAP
    }

    /**
     * @param anticipatable {@code min(required, cap)} — exactly the figure Giovanni specified
     * @param required      what the protocol demands be deposited; not ours to choose
     * @param cap           the operator's stated cap, zero when the market is disabled
     * @param refusal       {@code null} when the candidate may proceed
     */
    public record Decision(BigInteger anticipatable, BigInteger required, BigInteger cap,
                           Refusal refusal, String detail) {
        public boolean allowed() {
            return refusal == null;
        }
    }

    private final Map<String, BigInteger> capsByUnit;

    public MarketGate(String configured) {
        this.capsByUnit = parse(configured);
    }

    /** Visible for the operator-facing boot log: which markets are on, and for how much. */
    public Map<String, BigInteger> caps() {
        return Map.copyOf(capsByUnit);
    }

    /**
     * @param principal the loan's principal asset — the market
     * @param required  {@code convertedLoanCollateralToPrincipalAmount}: what must be fronted
     */
    public Decision decide(AssetType principal, BigInteger required) {
        String unit = principal == null ? null : principal.toUnit();
        BigInteger cap = unit == null ? null : capsByUnit.get(unit);

        if (cap == null) {
            return new Decision(BigInteger.ZERO, required, BigInteger.ZERO, Refusal.MARKET_DISABLED,
                    "market " + unit + " is not listed in loans.liquidation.markets, so it is "
                            + "disabled; anticipatable is 0 and no principal may be fronted");
        }
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

    /** {@code unit:cap} pairs. A malformed entry is dropped loudly — never silently widened. */
    private static Map<String, BigInteger> parse(String configured) {
        Map<String, BigInteger> caps = new LinkedHashMap<>();
        if (configured == null || configured.isBlank()) {
            return caps;
        }
        for (String raw : configured.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int at = entry.lastIndexOf(':');
            if (at <= 0 || at == entry.length() - 1) {
                log.warn("loans.liquidation.markets entry '{}' is not <unit>:<cap>; IGNORING it, so "
                        + "that market stays DISABLED", entry);
                continue;
            }
            try {
                BigInteger cap = new BigInteger(entry.substring(at + 1).trim());
                if (cap.signum() < 0) {
                    log.warn("loans.liquidation.markets entry '{}' has a negative cap; IGNORING it, "
                            + "so that market stays DISABLED", entry);
                    continue;
                }
                caps.put(entry.substring(0, at).trim(), cap);
            } catch (NumberFormatException e) {
                log.warn("loans.liquidation.markets entry '{}' has an unparseable cap; IGNORING it, "
                        + "so that market stays DISABLED", entry);
            }
        }
        return caps;
    }
}
