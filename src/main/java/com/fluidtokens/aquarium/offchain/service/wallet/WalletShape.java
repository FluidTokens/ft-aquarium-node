package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The bot wallet's TARGET SHAPE, and the pure arithmetic that decides whether a set of UTxOs is in
 * it. No Spring, no network, no transaction — {@link WalletShapeTransactions} builds the self-send.
 *
 * <h2>The shape (FAB-130, as Giovanni ruled it)</h2>
 * <ul>
 *   <li>one UTxO per <b>relevant</b> token (the principal of an {@code ANTICIPATE} market), carrying
 *       all of that token and exactly {@link #SHAPED_TOKEN_LOVELACE};</li>
 *   <li>one UTxO holding every <b>non-relevant</b> token, carrying exactly
 *       {@link #SHAPED_TOKEN_LOVELACE};</li>
 *   <li>one ADA-only collateral UTxO of exactly C lovelace, C = {@link #collateralLovelace};</li>
 *   <li>every remaining lovelace in exactly ONE ADA-only change UTxO.</li>
 * </ul>
 *
 * <p>Units are compared <b>lower-cased</b>, matching {@code MarketGate.marketFor}'s case-insensitive
 * comparison: an operator who wrote a market's unit in upper case still means the same asset.
 */
public final class WalletShape {

    /** The lovelace every token-bearing UTxO of the target shape carries — exactly, never "at least". */
    public static final BigInteger SHAPED_TOKEN_LOVELACE = BigInteger.valueOf(10_000_000L);

    /**
     * cardano-client-lib's hardcoded collateral selection amount, the floor C may never fall below —
     * the same floor {@code ScheduledTransactionService.requiredWalletLovelace} applies.
     */
    static final BigInteger CCL_DEFAULT_COLLATERAL_LOVELACE = BigInteger.valueOf(5_000_000L);

    static final String LOVELACE = "lovelace";

    /** Why a wallet is not in the target shape. Each value is one rule; several may fire at once. */
    public enum Reason {
        /** R1: no ADA-only UTxO holds exactly C lovelace. */
        NO_EXACT_COLLATERAL,
        /** R2: more than one ADA-only UTxO besides the (one) collateral UTxO. */
        ADA_FRAGMENTED,
        /** R3: one UTxO holds two or more distinct relevant units. */
        RELEVANT_UNITS_SHARE_A_UTXO,
        /** R4: a UTxO holding a relevant unit does not carry exactly 10 ADA. */
        RELEVANT_UTXO_LOVELACE_NOT_EXACT,
        /**
         * R5: the non-relevant tokens are spread over more than one UTxO, or the one UTxO holding them
         * does not carry exactly 10 ADA.
         */
        NON_RELEVANT_NOT_ONE_EXACT_UTXO,
        /** R6: a UTxO mixes a relevant unit with non-relevant units. */
        RELEVANT_MIXED_WITH_NON_RELEVANT
    }

    /** What one planned output is for. */
    public enum OutputRole {
        RELEVANT_TOKEN,
        NON_RELEVANT_TOKENS,
        COLLATERAL
    }

    /**
     * One output of the plan.
     *
     * @param role     what the output is for
     * @param lovelace its exact lovelace
     * @param assets   its native assets, lower-case unit → quantity, sorted by unit; empty for collateral
     */
    public record PlannedOutput(OutputRole role, BigInteger lovelace, Map<String, BigInteger> assets) {

        public PlannedOutput {
            Objects.requireNonNull(role);
            Objects.requireNonNull(lovelace);
            assets = java.util.Collections.unmodifiableMap(new TreeMap<>(assets));
        }

        /**
         * The amounts to pay, lovelace first. Built with the two-argument unit form on purpose:
         * {@code Amount.asset(policy, name, qty)} hex-encodes an already-hex name (officina CCL trap 3).
         */
        public List<Amount> amounts() {
            List<Amount> amounts = new ArrayList<>();
            amounts.add(Amount.lovelace(lovelace));
            assets.forEach((unit, quantity) -> amounts.add(Amount.asset(unit, quantity)));
            return amounts;
        }

        /** Every unit this output must hold, lovelace included. */
        public Map<String, BigInteger> units() {
            Map<String, BigInteger> units = new TreeMap<>(assets);
            units.put(LOVELACE, lovelace);
            return units;
        }
    }

    /**
     * The ordered output plan: relevant-token outputs sorted by unit, then the non-relevant bundle (if
     * any non-relevant token exists), then collateral. The change is not a planned output — its value
     * is whatever lovelace remains once the fee is known — so the plan records only what it has to
     * cover, {@link #changeAndFeeLovelace}.
     *
     * @param outputs       the planned outputs, in the order they are paid
     * @param inputLovelace the lovelace the inputs hold
     */
    public record Plan(List<PlannedOutput> outputs, BigInteger inputLovelace) {

        public Plan {
            outputs = List.copyOf(outputs);
        }

        /** The lovelace the planned outputs carry, change excluded. */
        public BigInteger plannedLovelace() {
            return outputs.stream().map(PlannedOutput::lovelace).reduce(BigInteger.ZERO, BigInteger::add);
        }

        /** What is left for the change output and the fee together. Negative: the inputs cannot fund the plan. */
        public BigInteger changeAndFeeLovelace() {
            return inputLovelace.subtract(plannedLovelace());
        }
    }

    private WalletShape() {
    }

    /**
     * The principal units of the {@code ANTICIPATE} markets — the tokens the bot must be able to front
     * one UTxO at a time — lower-cased, never {@code lovelace} (ADA is not a token to isolate; an ADA
     * market fronts from the change).
     */
    public static Set<String> relevantUnits(List<AppConfig.LiquidationConfiguration.Market> markets) {
        if (markets == null) {
            return Set.of();
        }
        return markets.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getAction() == AppConfig.LiquidationConfiguration.Action.ANTICIPATE)
                .map(AppConfig.LiquidationConfiguration.Market::getUnit)
                .filter(Objects::nonNull)
                .map(WalletShape::normalise)
                .filter(unit -> !unit.isEmpty() && !LOVELACE.equals(unit))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** C: {@code max(LedgerCeilings.maxPossibleCollateral(params), 5 ADA)}. */
    public static BigInteger collateralLovelace(ProtocolParams params) {
        return LedgerCeilings.maxPossibleCollateral(params).max(CCL_DEFAULT_COLLATERAL_LOVELACE);
    }

    /** Every rule the given UTxOs break. Empty: the wallet is in the target shape. */
    public static EnumSet<Reason> reasons(List<Utxo> utxos, Set<String> relevantUnits, BigInteger collateralLovelace) {
        Set<String> relevant = normaliseAll(relevantUnits);
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);

        // R1 / R2: the ADA-only UTxOs.
        List<Utxo> adaOnly = utxos.stream().filter(WalletShape::isAdaOnly).toList();
        boolean hasCollateral = adaOnly.stream()
                .anyMatch(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(collateralLovelace) == 0);
        if (!hasCollateral) {
            reasons.add(Reason.NO_EXACT_COLLATERAL);
        }
        long adaOnlyBesidesCollateral = adaOnly.size() - (hasCollateral ? 1 : 0);
        if (adaOnlyBesidesCollateral > 1) {
            reasons.add(Reason.ADA_FRAGMENTED);
        }

        // R3–R6: the token-bearing UTxOs.
        List<Utxo> nonRelevantHolders = new ArrayList<>();
        for (Utxo utxo : utxos) {
            Set<String> units = tokenUnits(utxo);
            long relevantHeld = units.stream().filter(relevant::contains).count();
            boolean holdsNonRelevant = units.stream().anyMatch(unit -> !relevant.contains(unit));
            BigInteger lovelace = LedgerCeilings.lovelaceOf(utxo);

            if (relevantHeld >= 2) {
                reasons.add(Reason.RELEVANT_UNITS_SHARE_A_UTXO);
            }
            if (relevantHeld >= 1 && lovelace.compareTo(SHAPED_TOKEN_LOVELACE) != 0) {
                reasons.add(Reason.RELEVANT_UTXO_LOVELACE_NOT_EXACT);
            }
            if (relevantHeld >= 1 && holdsNonRelevant) {
                reasons.add(Reason.RELEVANT_MIXED_WITH_NON_RELEVANT);
            }
            if (holdsNonRelevant) {
                nonRelevantHolders.add(utxo);
            }
        }
        if (nonRelevantHolders.size() > 1
                || nonRelevantHolders.stream()
                .anyMatch(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(SHAPED_TOKEN_LOVELACE) != 0)) {
            reasons.add(Reason.NON_RELEVANT_NOT_ONE_EXACT_UTXO);
        }
        return reasons;
    }

    /** The ordered output plan that moves these UTxOs into the target shape. */
    public static Plan plan(List<Utxo> utxos, Set<String> relevantUnits, BigInteger collateralLovelace) {
        Set<String> relevant = normaliseAll(relevantUnits);
        Map<String, BigInteger> totals = totalUnits(utxos);
        BigInteger inputLovelace = totals.getOrDefault(LOVELACE, BigInteger.ZERO);

        Map<String, BigInteger> relevantHeld = new TreeMap<>();
        Map<String, BigInteger> nonRelevantHeld = new TreeMap<>();
        totals.forEach((unit, quantity) -> {
            if (LOVELACE.equals(unit) || quantity.signum() == 0) {
                return;
            }
            (relevant.contains(unit) ? relevantHeld : nonRelevantHeld).put(unit, quantity);
        });

        List<PlannedOutput> outputs = new ArrayList<>();
        relevantHeld.forEach((unit, quantity) -> outputs.add(
                new PlannedOutput(OutputRole.RELEVANT_TOKEN, SHAPED_TOKEN_LOVELACE, Map.of(unit, quantity))));
        if (!nonRelevantHeld.isEmpty()) {
            outputs.add(new PlannedOutput(OutputRole.NON_RELEVANT_TOKENS, SHAPED_TOKEN_LOVELACE, nonRelevantHeld));
        }
        outputs.add(new PlannedOutput(OutputRole.COLLATERAL, collateralLovelace, Map.of()));
        return new Plan(outputs, inputLovelace);
    }

    /** ADA-only: the amount list is exactly lovelace. */
    static boolean isAdaOnly(Utxo utxo) {
        return utxo.getAmount() != null && utxo.getAmount().size() == 1
                && LOVELACE.equals(normalise(utxo.getAmount().get(0).getUnit()));
    }

    /** Every unit across the given UTxOs, lower-cased, summed; lovelace included. */
    static Map<String, BigInteger> totalUnits(Collection<Utxo> utxos) {
        Map<String, BigInteger> totals = new LinkedHashMap<>();
        for (Utxo utxo : utxos) {
            if (utxo.getAmount() == null) {
                continue;
            }
            for (Amount amount : utxo.getAmount()) {
                totals.merge(normalise(amount.getUnit()), amount.getQuantity(), BigInteger::add);
            }
        }
        return totals;
    }

    static String normalise(String unit) {
        return unit == null ? "" : unit.toLowerCase(Locale.ROOT);
    }

    private static Set<String> normaliseAll(Set<String> units) {
        return units == null ? Set.of()
                : units.stream().map(WalletShape::normalise).collect(Collectors.toSet());
    }

    /** The non-lovelace units a UTxO holds with a non-zero quantity, lower-cased. */
    private static Set<String> tokenUnits(Utxo utxo) {
        if (utxo.getAmount() == null) {
            return Set.of();
        }
        return utxo.getAmount().stream()
                .filter(a -> a.getQuantity() != null && a.getQuantity().signum() != 0)
                .map(a -> normalise(a.getUnit()))
                .filter(unit -> !LOVELACE.equals(unit))
                .collect(Collectors.toSet());
    }
}
