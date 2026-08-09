package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

/**
 * Applies {@link LoanFinance} to indexed loans, pulling prices from {@link FluidOracleClient}.
 * <p>
 * Every number here is one the validator recomputes, so this is the same arithmetic the chain will
 * run — see {@link LoanFinance} for why that matters.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LoanHealthService {

    private final ObjectProvider<FluidOracleClient> oracleClient;

    public LoanHealth health(Loan loan, long atTimeMillis) {
        var datum = loan.datum();

        // Debt and lateness need no prices at all, so they are always answerable.
        BigInteger remainingDebt;
        try {
            remainingDebt = LoanFinance.remainingDebt(datum, atTimeMillis);
        } catch (ArithmeticException e) {
            log.debug("could not compute remaining debt for {}: {}", loan.utxoRef(), e.getMessage());
            return new LoanHealth(null, false, null, null, null, "debt: " + e.getMessage());
        }
        boolean late = LoanFinance.isRepaymentLate(datum, atTimeMillis);

        if (!(datum.liquidationMode() instanceof LiquidationMode.Liquidation liquidation)) {
            // No LTV threshold exists in these modes; lateness alone drives the lender's claim.
            return LoanHealth.debtOnly(remainingDebt, late, "no LTV threshold in " +
                    datum.liquidationMode().getClass().getSimpleName());
        }

        var client = oracleClient.getIfAvailable();
        if (client == null) {
            return LoanHealth.debtOnly(remainingDebt, late, "oracle client disabled");
        }

        AssetType collateralAsset = collateralAsset(loan);
        Optional<OraclePriceFeed> principalFeed = client.findFeed(datum.principalAsset(), atTimeMillis);
        Optional<OraclePriceFeed> collateralFeed = client.findFeed(collateralAsset, atTimeMillis);
        if (principalFeed.isEmpty() || collateralFeed.isEmpty()) {
            var missing = principalFeed.isEmpty() ? datum.principalAsset() : collateralAsset;
            return LoanHealth.debtOnly(remainingDebt, late, unpriceableReason(client, missing));
        }

        try {
            Rational debt = Rational.fromInt(remainingDebt);
            Rational collateral = Rational.fromInt(loan.collateralAmount());
            Rational ltv = LoanFinance.currentLtv(debt, collateral, principalFeed.get(), collateralFeed.get());
            boolean canLiquidate = LoanFinance.canLiquidate(debt, collateral,
                    LoanFinance.liquidationLtv(liquidation), principalFeed.get(), collateralFeed.get());
            BigInteger equity = LoanFinance.redeemerEquity(liquidation, collateral, debt,
                    principalFeed.get(), collateralFeed.get());

            // Lateness is an independent trigger: loan_claim_action.ak:230 is `or { isRepaymentLate, can_liquidate }`
            return new LoanHealth(remainingDebt, late, ltv, equity, canLiquidate || late, null);
        } catch (ArithmeticException | UnsupportedOperationException e) {
            log.debug("could not price {}: {}", loan.utxoRef(), e.getMessage());
            return LoanHealth.debtOnly(remainingDebt, late, e.getMessage());
        }
    }

    /**
     * "We have never heard of this asset" and "our price for it went stale" are different
     * operational problems — the first is a coverage gap to raise with FluidTokens, the second
     * usually fixes itself on the next refresh — so they must not read the same in the API.
     */
    private static String unpriceableReason(FluidOracleClient client, AssetType asset) {
        return client.findFeedIgnoringValidity(asset)
                .map(feed -> "oracle price for %s expired at %s"
                        .formatted(asset.toUnit(), Instant.ofEpochMilli(feed.validTo())))
                .orElseGet(() -> "no oracle price for " + asset.toUnit());
    }

    /**
     * {@code get_collateral_amount} treats a collection (no asset name) as the sum across the
     * policy; the named case is a single asset. {@link Loan#collateralAmount()} already carries
     * the quantity, so this only resolves which asset to price.
     */
    private static AssetType collateralAsset(Loan loan) {
        var collateral = loan.datum().collateral();
        return collateral.assetName()
                .map(name -> new AssetType(collateral.policyId(), name))
                .orElseGet(() -> new AssetType(collateral.policyId(), ""));
    }
}
