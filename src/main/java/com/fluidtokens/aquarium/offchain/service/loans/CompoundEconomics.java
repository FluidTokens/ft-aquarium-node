package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

/**
 * The profitability gate for the repayment-escrow compound path, and the only place its arithmetic
 * lives.
 *
 * <h2>The economics, and why they are not the liquidation's</h2>
 * On chain {@code lm_compound_action} requires the pool to receive
 * {@code addedLiquidity - compoundingFee}, where
 * {@code compoundingFee = addedLiquidity * compoudingFeePerMille / 1000} — integer division,
 * mirrored here exactly. <b>Nothing pays the bot.</b> The fee is the part the pool is not required to
 * receive; it stays in the transaction and the builder must place it deliberately (findings §20.1).
 *
 * <p>So the bot's entire outlay is the transaction fee. It advances no principal, holds no bond, and
 * carries no oracle risk — which is why the floor here defaults to 0 rather than to the liquidation
 * path's 1_500_000 premium. See {@code AppConfig.CompoundConfiguration#profitMarginLovelace}.
 *
 * <h2>⛔ The unit trap this gate refuses rather than papers over</h2>
 * {@code compoudingFeePerMille} applies to the <b>principal asset</b>. For an ADA-principal pool the
 * fee is lovelace and comparing it to a lovelace transaction fee is sound. For a token-principal pool
 * it is a token quantity, and subtracting a lovelace cost from it produces a number that looks like
 * profit and means nothing. Pricing that token needs an oracle this path does not have, so a
 * non-ADA principal is {@link CompoundExclusion#PRINCIPAL_NOT_ADA} — refused, not guessed.
 */
@Service
@Slf4j
public class CompoundEconomics {

    private static final BigInteger PER_MILLE = BigInteger.valueOf(1000L);

    private final AppConfig.CompoundConfiguration configuration;
    private final AppConfig.Network network;

    public CompoundEconomics(AppConfig.CompoundConfiguration configuration, AppConfig.Network network) {
        this.configuration = configuration;
        this.network = network;
    }

    /**
     * Announce the stated bound at boot, and say so loudly — on any network — when it is negative.
     *
     * <p>⛔ <b>Not fatal on mainnet.</b> Giovanni, 2026-09-03: <i>"it's fundamental to allow operators
     * to operate at a loss … operating at a loss MUST be implemented even on mainnet."</i> Compounding
     * a pool that pays nothing is exactly the protocol-health work the bot exists to do. The
     * protection is the DEFAULT of 0 — which refuses every loss on every network — so only an
     * explicitly negative value gets here, and it is a stated intention rather than a copy-paste
     * accident. A bot that will do unpaid work still has to say so where the operator already looks.
     */
    @PostConstruct
    void announceAndGuard() {
        BigInteger floor = configuration.getProfitMarginLovelace();
        log.info("COMPOUND ECONOMICS enabled={}; profit-margin={} lovelace (the floor "
                        + "expectedFee-txFee must reach; 0 refuses every net loss)",
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
                            + "pools whose compoudingFeePerMille is 0 — down to that stated floor. A "
                            + "deliberate protocol-health setting, not a fault.",
                    floor, networkName);
            return;
        }
        log.warn("⛔ loans.compound.profit-margin-lovelace is {} (negative) on network {} — the bot will "
                + "compound AT A LOSS down to that bound, including for pools whose "
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
     * @param principalIsAda  whether the pool's {@code principalAsset} is ADA
     * @param escrow          principal held in the asset manager, in the principal's own unit
     * @param feePerMille     {@code compoudingFeePerMille} from the live {@code PoolManagerDatum}
     * @param txFee           the measured fee of the built transaction, in lovelace
     */
    public CompoundAssessment assess(boolean bondNamesAPool,
                                     boolean poolAndManagerLive,
                                     boolean principalIsAda,
                                     BigInteger escrow,
                                     long feePerMille,
                                     BigInteger txFee) {
        if (!configuration.isEnabled()) {
            return CompoundAssessment.refused(CompoundExclusion.NOT_ARMED);
        }
        if (!bondNamesAPool) {
            return CompoundAssessment.refused(CompoundExclusion.BOND_NAMES_NO_POOL);
        }
        if (!poolAndManagerLive) {
            return CompoundAssessment.refused(CompoundExclusion.POOL_NOT_LIVE);
        }
        if (!principalIsAda) {
            return CompoundAssessment.refused(CompoundExclusion.PRINCIPAL_NOT_ADA);
        }

        BigInteger expectedFee = expectedFee(escrow, feePerMille);
        BigInteger net = expectedFee.subtract(txFee);
        BigInteger floor = configuration.getProfitMarginLovelace();
        boolean approved = net.compareTo(floor) >= 0;

        return new CompoundAssessment(approved,
                approved ? null : CompoundExclusion.NET_BELOW_FLOOR,
                escrow, feePerMille, expectedFee, txFee, net, floor);
    }

    /**
     * {@code escrow * feePerMille / 1000}, integer division, matching
     * {@code lm_compound_action}'s {@code addedLiquidity * compoudingFeePerMille / 1000} exactly.
     *
     * <p>The truncation is deliberate and must not be rounded: the validator computes the pool's
     * required output from this same expression, so a fee we round UP is a fee the pool is not
     * required to give up, and the transaction fails phase 2.
     */
    static BigInteger expectedFee(BigInteger escrow, long feePerMille) {
        return escrow.multiply(BigInteger.valueOf(feePerMille)).divide(PER_MILLE);
    }
}
