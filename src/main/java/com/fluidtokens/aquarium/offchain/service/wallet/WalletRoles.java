package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>Which wallet output plays which role in one liquidation check</b> (FAB-185), resolved ONCE from one
 * wallet read and never re-resolved within that check. An immutable value: no Spring, no network.
 *
 * <h2>The roles</h2>
 * <ul>
 *   <li><b>C</b> — the ada-only output of exactly {@link #collateralLovelace()} lovelace
 *       ({@code WalletShape.collateralLovelace(params)}), the collateral the rebalancer keeps. <b>It never
 *       funds a route.</b> Among several, the smallest ref (txHash, then index).</li>
 *   <li><b>U</b> — the LARGEST ada-only output that is not C; ties go to the smallest ref. It funds plain,
 *       convert and ADA-principal pay-in-advance liquidations.</li>
 *   <li><b>token output</b>, per unit — the output holding the most of that unit, with no datum and no
 *       reference script; ties go to the smallest ref. It funds a token-principal pay-in-advance.</li>
 * </ul>
 * Ada-only is {@link WalletInputSelection#nominable} — the one ada-only predicate (FAB-188).
 *
 * <p>Units are compared lower-cased, as {@code WalletShape} compares them.
 */
public final class WalletRoles {

    /**
     * A pre-build CEILING for the S-15 collateral rider — the bot's own acquired-collateral output on the
     * pay-in-advance path, measured at 1,176,630 lovelace on the pinned rig. It sizes which output may be
     * offered before anything is built; the build is the judge of whether it actually suffices.
     */
    public static final BigInteger S15_COLLATERAL_RIDER_CEILING = BigInteger.valueOf(1_500_000L);

    /**
     * A pre-build CEILING for the change output's own min-ada. It sizes which output may be offered before
     * anything is built; the build is the judge of whether it actually suffices.
     */
    public static final BigInteger CHANGE_MIN_ADA_CEILING = BigInteger.valueOf(2_000_000L);

    private static final String LOVELACE = "lovelace";

    /** Smallest ref first: txHash, then output index. */
    private static final Comparator<Utxo> BY_REF = Comparator.comparing(Utxo::getTxHash,
                    Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparingInt(Utxo::getOutputIndex);

    private final BigInteger collateralLovelace;
    private final Utxo c;
    private final Utxo u;
    private final Map<String, Utxo> tokenOutputs;

    private WalletRoles(BigInteger collateralLovelace, Utxo c, Utxo u, Map<String, Utxo> tokenOutputs) {
        this.collateralLovelace = collateralLovelace;
        this.c = c;
        this.u = u;
        this.tokenOutputs = tokenOutputs;
    }

    /**
     * The roles of {@code wallet} for a collateral of exactly {@code collateralLovelace}. The list is copied:
     * nothing done to it afterwards changes what is resolved here.
     */
    public static WalletRoles resolve(List<Utxo> wallet, BigInteger collateralLovelace) {
        Objects.requireNonNull(collateralLovelace, "collateralLovelace");
        List<Utxo> copy = wallet == null ? List.of() : wallet.stream().filter(Objects::nonNull).toList();

        List<Utxo> adaOnly = copy.stream().filter(WalletInputSelection::nominable).toList();
        Utxo c = adaOnly.stream()
                .filter(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(collateralLovelace) == 0)
                .min(BY_REF)
                .orElse(null);
        Utxo u = adaOnly.stream()
                .filter(utxo -> c == null || !ref(utxo).equals(ref(c)))
                .min(Comparator.comparing(LedgerCeilings::lovelaceOf, Comparator.<BigInteger>reverseOrder())
                        .thenComparing(BY_REF))
                .orElse(null);

        Map<String, Utxo> tokenOutputs = new HashMap<>();
        for (Utxo utxo : copy) {
            if (utxo.getAmount() == null || utxo.getReferenceScriptHash() != null
                    || utxo.getInlineDatum() != null || utxo.getDataHash() != null) {
                continue;
            }
            for (Amount amount : utxo.getAmount()) {
                if (amount == null || amount.getUnit() == null) {
                    continue;
                }
                String unit = normalise(amount.getUnit());
                if (LOVELACE.equals(unit) || quantityOf(utxo, unit).signum() <= 0) {
                    continue;
                }
                tokenOutputs.merge(unit, utxo, (held, candidate) -> better(unit, held, candidate));
            }
        }
        return new WalletRoles(collateralLovelace, c, u, Map.copyOf(tokenOutputs));
    }

    /** C, the exact-collateral ada-only output; empty when the wallet has none. */
    public Optional<Utxo> c() {
        return Optional.ofNullable(c);
    }

    /** U, the largest ada-only output that is not C; empty when the wallet has none. */
    public Optional<Utxo> u() {
        return Optional.ofNullable(u);
    }

    /** The output holding the most of {@code unit} (compared lower-cased); empty when no eligible output holds any. */
    public Optional<Utxo> tokenOutput(String unit) {
        return Optional.ofNullable(tokenOutputs.get(normalise(unit)));
    }

    /**
     * The role {@code ref} ({@code txHash#index}) plays in these roles — {@code C}, {@code U} or
     * {@code TOKEN <unit>} — or empty when it plays none.
     */
    public Optional<String> roleOf(String ref) {
        if (c != null && ref(c).equals(ref)) {
            return Optional.of("C");
        }
        if (u != null && ref(u).equals(ref)) {
            return Optional.of("U");
        }
        return tokenOutputs.entrySet().stream()
                .filter(entry -> ref(entry.getValue()).equals(ref))
                .map(entry -> "TOKEN " + entry.getKey())
                .sorted()
                .findFirst();
    }

    /** The exact lovelace C was resolved against. */
    public BigInteger collateralLovelace() {
        return collateralLovelace;
    }

    // ---- requirement ceilings: what the designated output must hold before anything is built -----------

    /** A plain liquidation fronts nothing: U must cover the fee ceiling. */
    public static BigInteger plainRequired(BigInteger fee) {
        return fee;
    }

    /** A convert: U must cover the Minswap order the router plans plus the fee ceiling. */
    public static BigInteger convertRequired(BigInteger order, BigInteger fee) {
        return order.add(fee);
    }

    /**
     * An ADA-principal pay-in-advance: U must cover the lender payout, the fee ceiling, the S-15 collateral
     * rider and the change output's min-ada.
     */
    public static BigInteger adaAnticipateRequired(BigInteger payout, BigInteger fee) {
        return payout.add(fee).add(S15_COLLATERAL_RIDER_CEILING).add(CHANGE_MIN_ADA_CEILING);
    }

    /**
     * The lovelace a token output must carry beside the token for a token-principal pay-in-advance: the fee
     * ceiling, the lender output's min-ada ({@link WalletInputSelection#TOKEN_PRINCIPAL_MIN_ADA_CEILING}), the
     * S-15 collateral rider and the change output's min-ada.
     */
    public static BigInteger tokenOutputRequiredAda(BigInteger fee) {
        return fee.add(WalletInputSelection.TOKEN_PRINCIPAL_MIN_ADA_CEILING)
                .add(S15_COLLATERAL_RIDER_CEILING).add(CHANGE_MIN_ADA_CEILING);
    }

    /** The quantity of {@code unit} (compared lower-cased) an output holds. Zero if it holds none. */
    public static BigInteger quantityOf(Utxo utxo, String unit) {
        if (utxo.getAmount() == null) {
            return BigInteger.ZERO;
        }
        String wanted = normalise(unit);
        return utxo.getAmount().stream()
                .filter(a -> a != null && a.getQuantity() != null && wanted.equals(normalise(a.getUnit())))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** {@code txHash#index}, the form every log line and used-mark here names an output by. */
    public static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static Utxo better(String unit, Utxo held, Utxo candidate) {
        int byQuantity = quantityOf(candidate, unit).compareTo(quantityOf(held, unit));
        if (byQuantity != 0) {
            return byQuantity > 0 ? candidate : held;
        }
        return BY_REF.compare(candidate, held) < 0 ? candidate : held;
    }

    private static String normalise(String unit) {
        return unit == null ? "" : unit.toLowerCase(java.util.Locale.ROOT);
    }
}
