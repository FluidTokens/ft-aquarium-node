package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * The profitability gate for the repayment-escrow compound path, and the only place its arithmetic
 * lives.
 *
 * <h2>The economics, and why they are not the liquidation's</h2>
 * On chain {@code lm_compound_action} requires the pool to receive
 * {@code addedLiquidity - compoundingFee}, where {@code compoundingFee} is FluidTokens'
 * {@code finance.get_compounding_fee} — since their 2026-10-26 update (upstream {@code a02d4e2}) an ADA
 * principal pays {@code max(addedLiquidity * compoudingFeePerMille / 1000, 1 ADA)} and any other
 * principal {@code addedLiquidity * compoudingFeePerMille / 1000}, integer division throughout.
 * {@link #compoundingFee} mirrors it exactly and is the ONLY place this node computes it.
 * <b>Nothing pays the bot.</b> The fee is the part the pool is not required to receive; it stays in the
 * transaction and the builder must place it deliberately (findings §20.1).
 *
 * <p>The exact final-body gate is {@code F-L} for ADA principal and
 * {@code floor(F*n/d)+E-L-T} for native principal. Native {@code F} is priced from raw units; its
 * escrow ADA is recovered as {@code E}, while {@code T} is the actual pool minimum-ADA top-up.
 *
 * <h2>⛔ The unit trap this gate no longer papers over by refusing outright</h2>
 * {@code compoudingFeePerMille} applies to the <b>principal asset</b>. For an ADA-principal pool the
 * fee is already lovelace and comparing it to a lovelace transaction fee is direct. For a
 * token-principal pool it is a token quantity, and subtracting a lovelace cost from it directly
 * would produce a number that looks like profit and means nothing — so it is priced first, through
 * {@link PricingService} (the ONE place a token quantity becomes lovelace in this path), and only
 * refused with a specific price exclusion when that pricing itself fails.
 * ADA is the identity in {@link PricingService#toLovelace} — no oracle is consulted for it, so this
 * gate's ada behaviour is unchanged from before this class knew how to price anything else.
 * <p>
 * A rounded native {@code F=0} deliberately bypasses the oracle and is decided by {@code E-L-T}.
 */
@Service
@Slf4j
public class CompoundEconomics {

    private static final BigInteger PER_MILLE = BigInteger.valueOf(1000L);

    /**
     * {@code constants.min_compounding_fee_lovelace} (upstream {@code a02d4e2}): the least an ADA pool pays
     * the compounding bot, whatever its {@code compoudingFeePerMille} — 0 included.
     */
    public static final BigInteger MIN_ADA_COMPOUNDING_FEE_LOVELACE = BigInteger.valueOf(1_000_000L);

    private final AppConfig.CompoundConfiguration configuration;
    private final AppConfig.Network network;
    private final PricingService pricingService;

    public CompoundEconomics(AppConfig.CompoundConfiguration configuration, AppConfig.Network network,
                             PricingService pricingService) {
        this.configuration = configuration;
        this.network = network;
        this.pricingService = pricingService;
    }

    /**
     * Announce the stated bound at boot, and say so loudly — on any network — when it is negative.
     *
     * <p>⛔ <b>Not fatal on mainnet.</b> Giovanni, 2026-09-03: <i>"it's fundamental to allow operators
     * to operate at a loss … operating at a loss MUST be implemented even on mainnet."</i> Compounding
     * a pool that pays less than the work costs is exactly the protocol-health work the bot exists to do.
     * Since FluidTokens' 2026-10-26 update an ADA pool always pays at least 1 ADA; a token pool pays
     * {@code Q*c/1000}, so a zero-rate token pool still pays nothing. The
     * protection is the DEFAULT of 0 — which refuses every loss on every network — so only an
     * explicitly negative value gets here, and it is a stated intention rather than a copy-paste
     * accident. A bot that will do unpaid work still has to say so where the operator already looks.
     */
    @PostConstruct
    void announceAndGuard() {
        BigInteger floor = configuration.getProfitMarginLovelace();
        log.info("COMPOUND ECONOMICS enabled={}; profit-margin={} lovelace (native net is "
                        + "pricedFee+recoveredEscrow-txFee-poolTopUp; ADA net is fee-txFee; "
                        + "0 refuses every net loss)",
                configuration.isEnabled(), floor);

        if (floor == null || floor.signum() >= 0) {
            return;
        }
        String networkName = network == null ? null : network.getNetwork();
        // Fail-closed for the PURPOSE OF REPORTING only: an unrecognised network gets the louder line.
        boolean mainnet = networkName == null
                || (!"preview".equalsIgnoreCase(networkName) && !"preprod".equalsIgnoreCase(networkName));
        if (mainnet) {
            log.warn("⛔ OPERATING AT A LOSS ON MAINNET, BY OPERATOR CONFIGURATION — path: compound; "
                            + "loans.compound.profit-margin-lovelace = {} lovelace (network {}). This "
                            + "node will compound pools that pay it less than the work costs — including "
                            + "token pools whose compoudingFeePerMille is 0 — down to that stated floor. A "
                            + "deliberate protocol-health setting, not a fault.",
                    floor, networkName);
            return;
        }
        log.warn("⛔ loans.compound.profit-margin-lovelace is {} (negative) on network {} — the bot will "
                + "compound AT A LOSS down to that bound, including for token pools whose "
                + "compoudingFeePerMille is 0. This is a stated operator bound, not a disabled check.",
                floor, networkName);
    }

    /**
     * Decide one candidate. Checks fire in the order that makes the cheapest and most certain
     * refusals first, so a log line names the real reason rather than a downstream symptom.
     *
     * @param bondNamesAPool  whether the lender bond's {@code poolId} is non-empty
     * @param poolAndManagerLive whether BOTH the pool NFT and the pool-manager NFT carrying that
     *                           {@code poolId} are live — the two {@code quantity_of(...) == 1}
     *                           checks {@code lm_compound_action} performs
     * @param principalAsset  the pool's {@code principalAsset} — priced via {@link PricingService},
     *                        which is the identity for ada and consults the oracle for anything else
     * @param escrow          principal held in the asset manager, in the principal's own unit
     * @param feePerMille     {@code compoudingFeePerMille} from the live {@code PoolManagerDatum}
     * @param txFee           the measured fee of the built transaction, in lovelace
     * @param atMillis        the instant to price the fee slice at — the transaction's own
     *                        {@code validFrom} is the caller's usual choice
     */
    public CompoundAssessment assess(boolean bondNamesAPool,
                                     boolean poolAndManagerLive,
                                     AssetType principalAsset,
                                     BigInteger escrow,
                                     long feePerMille,
                                     BigInteger txFee,
                                     long atMillis) {
        CompoundExclusion structural = structuralExclusion(bondNamesAPool, poolAndManagerLive);
        if (structural != null) {
            return CompoundAssessment.refused(structural);
        }
        BigInteger fee = compoundingFee(escrow, feePerMille, principalAsset);
        PricingService.Priced quote = quote(principalAsset, fee, atMillis);
        return assessMeasured(bondNamesAPool, poolAndManagerLive, principalAsset, escrow,
                feePerMille, BigInteger.ZERO, txFee, BigInteger.ZERO, quote);
    }

    /** Price the raw reward. A rounded {@code F=0} is an exact zero with no oracle lookup. */
    public PricingService.Priced quote(AssetType principalAsset, BigInteger rawFee, long atMillis) {
        if (rawFee.signum() == 0) {
            return PricingService.Priced.priced(BigInteger.ZERO);
        }
        return pricingService.toLovelace(principalAsset, rawFee, atMillis);
    }

    /** Final-body profitability using only facts produced by the transaction kernel. */
    public CompoundAssessment assessFinal(boolean bondNamesAPool,
                                          boolean poolAndManagerLive,
                                          AssetType principalAsset,
                                          BigInteger escrow,
                                          long feePerMille,
                                          CompoundTransactionBuilder.KernelFacts facts,
                                          PricingService.Priced quote) {
        Objects.requireNonNull(facts, "facts");
        return assessMeasured(bondNamesAPool, poolAndManagerLive, principalAsset, escrow,
                feePerMille, facts.recoveredEscrowLovelace(), facts.transactionFeeLovelace(),
                facts.poolTopUpLovelace(), quote);
    }

    private CompoundAssessment assessMeasured(boolean bondNamesAPool,
                                               boolean poolAndManagerLive,
                                               AssetType principalAsset,
                                               BigInteger escrow,
                                               long feePerMille,
                                               BigInteger recoveredEscrowLovelace,
                                               BigInteger txFee,
                                               BigInteger poolTopUpLovelace,
                                               PricingService.Priced priced) {
        CompoundExclusion structural = structuralExclusion(bondNamesAPool, poolAndManagerLive);
        if (structural != null) {
            return CompoundAssessment.refused(structural);
        }

        BigInteger expectedFee = compoundingFee(escrow, feePerMille, principalAsset);
        if (!priced.isPriced()) {
            log.info("compound: cannot price {} raw reward units: {}",
                    expectedFee, priced.refusal());
            return CompoundAssessment.refused(priceExclusion(priced.refusal()));
        }

        // ADA is its own price: the net is the validator's fee itself, never a caller-supplied figure, so
        // a quote computed from any other fee cannot move an ADA decision.
        BigInteger expectedFeeLovelace = principalAsset.isAda() ? expectedFee : priced.lovelace();
        BigInteger recovered = principalAsset.isAda() ? BigInteger.ZERO : recoveredEscrowLovelace;
        BigInteger topUp = principalAsset.isAda() ? BigInteger.ZERO : poolTopUpLovelace;
        BigInteger net = expectedFeeLovelace.add(recovered).subtract(txFee).subtract(topUp);
        BigInteger floor = configuration.getProfitMarginLovelace();
        boolean approved = net.compareTo(floor) >= 0;

        return new CompoundAssessment(approved,
                approved ? null : CompoundExclusion.NET_BELOW_FLOOR,
                escrow, feePerMille, expectedFee, expectedFeeLovelace, recovered,
                txFee, topUp, net, floor, priced.observation());
    }

    private CompoundExclusion structuralExclusion(boolean bondNamesAPool,
                                                  boolean poolAndManagerLive) {
        if (!configuration.isEnabled()) {
            return CompoundExclusion.NOT_ARMED;
        }
        if (!bondNamesAPool) {
            return CompoundExclusion.BOND_NAMES_NO_POOL;
        }
        return poolAndManagerLive ? null : CompoundExclusion.POOL_NOT_LIVE;
    }

    /** Gross ranking value, explicitly excluding the not-yet-known final fee and pool top-up. */
    public Optional<BigInteger> grossRecoverableLovelace(AssetType principalAsset,
                                                         BigInteger escrow,
                                                         long feePerMille,
                                                         BigInteger rawEscrowCoin,
                                                         long atMillis) {
        try {
            BigInteger fee = compoundingFee(escrow, feePerMille, principalAsset);
            PricingService.Priced quote = quote(principalAsset, fee, atMillis);
            if (!quote.isPriced()) {
                return Optional.empty();
            }
            return Optional.of(quote.lovelace().add(principalAsset.isAda()
                    ? BigInteger.ZERO : rawEscrowCoin));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Re-read and identify the same still-usable observation immediately before signing. */
    public CompoundExclusion revalidateObservation(CompoundAssessment assessment,
                                                   AssetType principalAsset,
                                                   long atMillis) {
        if (assessment.expectedFee() == null || assessment.expectedFee().signum() == 0
                || principalAsset.isAda()) {
            return null;
        }
        PricingService.Priced current = pricingService.toLovelace(
                principalAsset, assessment.expectedFee(), atMillis);
        if (!current.isPriced()) {
            return current.refusal() != null
                    && current.refusal().reason() == PricingService.RefusalReason.NOT_USABLE_AT_INSTANT
                    ? CompoundExclusion.PRICE_OBSERVATION_EXPIRED
                    : priceExclusion(current.refusal());
        }
        if (!Objects.equals(assessment.priceObservation(), current.observation())) {
            return CompoundExclusion.PRICE_OBSERVATION_CHANGED;
        }
        return null;
    }

    static CompoundExclusion priceExclusion(PricingService.PriceRefusal refusal) {
        if (refusal == null || refusal.reason() == null) {
            return CompoundExclusion.PRICE_CONVERSION_FAILED;
        }
        return switch (refusal.reason()) {
            case NO_FEED -> CompoundExclusion.PRICE_NO_FEED;
            case NOT_USABLE_AT_INSTANT -> CompoundExclusion.PRICE_NOT_USABLE;
            case POOLED -> CompoundExclusion.PRICE_POOLED;
            case NON_POSITIVE -> CompoundExclusion.PRICE_NON_POSITIVE;
            case CONVERSION_FAILED -> CompoundExclusion.PRICE_CONVERSION_FAILED;
        };
    }

    /**
     * ⛔ THE effective compounding fee — FluidTokens' {@code finance.get_compounding_fee} (upstream
     * {@code a02d4e2}, {@code lib/fluidtokens/finance.ak}), which {@code lm_compound_action} computes the
     * pool's required output from:
     * <pre>
     *   expect compoudingFeePerMille >= 0
     *   let perMilleFee = addedLiquidity * compoudingFeePerMille / 1000
     *   if principalPolicyId == ada_policy_id { max(perMilleFee, 1_000_000) } else { perMilleFee }
     * </pre>
     * Every fee this node admits on, ranks by, builds with, asserts on or logs is this value; nothing else
     * in the compound path multiplies by a per-mille rate.
     *
     * <p>The division is Aiken's {@code /}, which FLOORS; it is ported as a floor for every integer, so for
     * the only inputs that reach it ({@code Q > 0}, {@code c >= 0}) it is plain truncation. It must not be
     * rounded: the validator demands exactly {@code Q - fee} in the pool, so a fee one unit off either way
     * fails phase 2. Since 2026-10-26 an ADA pool pays at least 1 ADA even at {@code c = 0}; a token pool
     * pays {@code Q*c/1000} raw units with NO floor (upstream: the bot already keeps the escrow's min-ADA).
     * "ADA" is decided as the validator decides it — the principal's policy id is the empty ADA policy.
     *
     * @throws IllegalArgumentException for {@code c < 0}, where the validator's {@code expect} fails
     */
    public static BigInteger compoundingFee(BigInteger addedLiquidity, long feePerMille, AssetType principalAsset) {
        Objects.requireNonNull(addedLiquidity, "addedLiquidity");
        Objects.requireNonNull(principalAsset, "principalAsset");
        if (feePerMille < 0L) {
            throw new IllegalArgumentException("get_compounding_fee requires compoudingFeePerMille >= 0, got "
                    + feePerMille);
        }
        BigInteger[] quotientAndRemainder = addedLiquidity.multiply(BigInteger.valueOf(feePerMille))
                .divideAndRemainder(PER_MILLE);
        BigInteger perMilleFee = quotientAndRemainder[1].signum() < 0
                ? quotientAndRemainder[0].subtract(BigInteger.ONE) : quotientAndRemainder[0];
        return adaPolicy(principalAsset) ? perMilleFee.max(MIN_ADA_COMPOUNDING_FEE_LOVELACE) : perMilleFee;
    }

    /** {@code principalPolicyId == ada_policy_id}: the principal's on-chain policy id is the empty one. */
    private static boolean adaPolicy(AssetType principalAsset) {
        return principalAsset.isAda() || principalAsset.policyId() == null || principalAsset.policyId().isEmpty();
    }
}
