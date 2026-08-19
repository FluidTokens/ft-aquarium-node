package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only liquidation candidate scan: for every indexed lender bond, decide whether the bot
 * could build a liquidation for it, and if not, exactly why.
 * <p>
 * <b>Filter order — first failure wins.</b> This is this scanner's own diagnostic convention, not
 * a mirror of on-chain evaluation order: join first (nothing else can be evaluated without a
 * loan), then the cheap type filters in the order this ticket specifies, then the epic-scope
 * restriction, then the two oracle legs, and finally the finance math — the most expensive step,
 * and the one every earlier filter exists to protect from bad inputs. The validators themselves
 * check D3's collateral shape ({@code lm_liquidate_action.ak:108,116}) <em>before</em> D1/D2
 * ({@code :120,121}), the opposite of steps 2-4 below — so a bond excluded here for reason N is
 * not guaranteed to be the first check a validator would actually reach; it only means the
 * scanner did not bother computing anything past N:
 * <ol>
 *   <li>join the bond to a loan by asset name → {@link LiquidationExclusion#LOAN_NOT_FOUND}</li>
 *   <li>D1 — mode must be {@link LiquidationMode.Liquidation} →
 *       {@link LiquidationExclusion#MODE_NOT_LIQUIDATION}</li>
 *   <li>D2 — {@code equityInPrincipalCurrency} must be false →
 *       {@link LiquidationExclusion#EQUITY_IN_PRINCIPAL_CURRENCY}</li>
 *   <li>D3 — collateral must name an asset (no NFT collections) →
 *       {@link LiquidationExclusion#COLLATERAL_IS_COLLECTION}</li>
 *   <li>D3 — collateral quantity must be &gt; 1 →
 *       {@link LiquidationExclusion#COLLATERAL_AMOUNT_TOO_SMALL}</li>
 *   <li>epic scope — {@code shouldLiquidationConvertToPrincipal} must be false →
 *       {@link LiquidationExclusion#CONVERSION_TO_PRINCIPAL_REQUIRED}</li>
 *   <li>the principal leg must be priceable and liquidatable →
 *       {@link LiquidationExclusion#PRINCIPAL_ORACLE_UNUSABLE}</li>
 *   <li>the collateral leg must be priceable and liquidatable →
 *       {@link LiquidationExclusion#COLLATERAL_ORACLE_UNUSABLE}</li>
 *   <li>{@link LoanFinance} must compute debt/lateness/equity without an {@link ArithmeticException} →
 *       {@link LiquidationExclusion#HEALTH_NOT_COMPUTABLE}</li>
 *   <li>D9 — the loan must actually be late or over its liquidation LTV →
 *       {@link LiquidationExclusion#NOT_LIQUIDATABLE}</li>
 * </ol>
 * Anything that survives all ten steps is {@link LiquidationAssessment#buildable()}.
 * <p>
 * <b>There used to be an eleventh step: the equity had to be zero.</b> It was a scope filter, and the
 * scope it described turned out not to exist. It rested on the claim that no output layout
 * {@code LiquidateTransactionBuilder} emits satisfies both {@code lm_liquidate_action} and
 * {@code loan_claim_action} at a positive equity — true of the layouts that had been tried, and never
 * a proof. In fact only {@code loan_claim_action}'s slot is forced; {@code lm_liquidate_action}
 * reaches its own through the {@code assetOutputIndexes} the builder writes. Emitting the borrower's
 * compensation output at the forced slot and pointing the free index at the displaced collateral
 * output satisfies both — see {@code LiquidateTransactionBuilder}'s "Positive equity: rule R", and
 * {@code RealEquityLoanDryEvalTest} for the evaluation against the deployed validators on a real
 * preview loan. A liquidatable loan with a positive equity is now an ordinary candidate.
 * <p>
 * <b>What still refuses, and where.</b> {@code repaymentReceipts == True} together with a positive
 * equity needs a receipt-NFT mint this repo does not model. That is refused by the <em>builder</em>
 * ({@code Refusal.REPAYMENT_RECEIPTS_WITH_EQUITY}), loudly and per candidate, rather than filtered out
 * here — the scanner's job is to describe the loan, and a loan that a future mint implementation would
 * make liquidatable is not one this class should be quietly hiding.
 * <p>
 * <b>Ports no arithmetic.</b> Every number in a buildable assessment comes straight out of
 * {@link LoanFinance}; every oracle-usability call defers to {@link OracleEntry#usableForLiquidation()}
 * and {@link OraclePriceFeed#usableAt(long)} as they stand today. (T-012 closed the former's fail-open:
 * a null {@code fluidOracle.referenceInput} now yields not-usable for every variant, so a corrupted
 * registry value can no longer produce a buildable oracle whose NFT reference UTxO cannot resolve.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LiquidationCandidateScanner {

    private final LenderBondService lenderBondService;

    private final LoanService loanService;

    private final ObjectProvider<FluidOracleClient> oracleClient;

    public List<LiquidationAssessment> scan(long atTimeMillis) {
        // A duplicate loanId (e.g. transient duplicate unspent UTxOs sharing an asset name) must
        // not blank the whole scan — same "one bad input must not blank the endpoint" posture as
        // LoanService/LenderBondService. Keep the first, log and drop the rest.
        Map<String, Loan> loansByLoanId = loanService.findAll().stream()
                .collect(Collectors.toMap(Loan::loanId, Function.identity(), (first, duplicate) -> {
                    log.warn("duplicate loanId {}: keeping {}#{}, dropping {}#{} from this scan",
                            first.loanId(), first.txHash(), first.outputIndex(),
                            duplicate.txHash(), duplicate.outputIndex());
                    return first;
                }));
        FluidOracleClient client = oracleClient.getIfAvailable();

        return lenderBondService.findAll().stream()
                .map(bond -> assess(bond, loansByLoanId.get(bond.loanId()), client, atTimeMillis))
                .toList();
    }

    private LiquidationAssessment assess(LenderBond bond, Loan loan, FluidOracleClient client, long atTimeMillis) {
        if (loan == null) {
            return LiquidationAssessment.excluded(bond, null, LiquidationExclusion.LOAN_NOT_FOUND,
                    "no loan under the loan policy shares bond asset name " + bond.loanId());
        }

        LoanDatum datum = loan.datum();

        if (!(datum.liquidationMode() instanceof LiquidationMode.Liquidation liquidation)) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.MODE_NOT_LIQUIDATION,
                    "liquidationMode is " + datum.liquidationMode().getClass().getSimpleName());
        }

        if (liquidation.equityInPrincipalCurrency()) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY,
                    "equity is denominated in principal currency");
        }

        if (datum.collateral().assetName().isEmpty()) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.COLLATERAL_IS_COLLECTION,
                    "collateral has no asset name (NFT collection collateral)");
        }

        if (loan.collateralAmount().compareTo(BigInteger.ONE) <= 0) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.COLLATERAL_AMOUNT_TOO_SMALL,
                    "collateral amount " + loan.collateralAmount() + " <= 1");
        }

        if (bond.datum().shouldLiquidationConvertToPrincipal()) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED,
                    "bond requires converting liquidation proceeds to principal; "
                            + "plain Liquidate enforces shouldLiquidationConvertToPrincipal == False");
        }

        FeedLookup principalFeed = lookupFeed(datum.principalAsset().isAda(), datum.principalOracleAsset(),
                client, atTimeMillis, "principal");
        if (!principalFeed.usable()) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                    principalFeed.unusableDetail());
        }

        FeedLookup collateralFeed = lookupFeed(datum.collateral().isAda(), datum.collateral().oracleTokenAsset(),
                client, atTimeMillis, "collateral");
        if (!collateralFeed.usable()) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE,
                    collateralFeed.unusableDetail());
        }

        BigInteger remainingDebt;
        boolean late;
        boolean liquidatable;
        BigInteger equity;
        try {
            remainingDebt = LoanFinance.remainingDebt(datum, atTimeMillis);
            late = LoanFinance.isRepaymentLate(datum, atTimeMillis);

            Rational debt = Rational.fromInt(remainingDebt);
            Rational collateralAmount = Rational.fromInt(loan.collateralAmount());
            // loan_claim_action.ak:230 is `or { isRepaymentLate, can_liquidate }` — Java `||` short-circuits
            // the same way, so `late` alone already decides the outcome without touching can_liquidate.
            liquidatable = late || LoanFinance.canLiquidate(debt, collateralAmount,
                    LoanFinance.liquidationLtv(liquidation), principalFeed.feed(), collateralFeed.feed());
            equity = LoanFinance.redeemerEquity(liquidation, collateralAmount, debt,
                    principalFeed.feed(), collateralFeed.feed());
        } catch (ArithmeticException e) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.HEALTH_NOT_COMPUTABLE,
                    e.getMessage());
        }

        if (!liquidatable) {
            return LiquidationAssessment.excluded(bond, loan, LiquidationExclusion.NOT_LIQUIDATABLE,
                    "not late and currentLtv <= liquidationLtv");
        }

        // A positive equity used to be excluded here, on the belief that no output layout
        // LiquidateTransactionBuilder emits could satisfy both lm_liquidate_action and
        // loan_claim_action at once. That belief was wrong: only loan_claim_action's slot is forced,
        // lm_liquidate_action reaches its own through the assetOutputIndexes the builder chooses, and
        // putting the borrower's compensation output at the forced slot satisfies both (rule R — see
        // LiquidateTransactionBuilder's class javadoc, and RealEquityLoanDryEvalTest for the
        // evaluation against the deployed validators on real chain data). So a liquidatable loan with
        // a positive equity is now an ordinary candidate and is passed through.
        //
        // One branch of it is still unbuildable and the BUILDER refuses it loudly rather than the
        // scanner filtering it out: repaymentReceipts == True together with a positive equity needs a
        // receipt-NFT mint this repo does not model (Refusal.REPAYMENT_RECEIPTS_WITH_EQUITY). That
        // refusal is reachable from a real scan now, which is why its @UnreachableFromScannedBatch
        // marking was removed with this exclusion.

        // §7.5 fee maths: liquidationFee = loanCollateralAmount * liquidationFeePerMille / 1000, floored.
        // liquidationFeePerMille is a lender-authored bond-datum field with no non-negativity
        // constraint, so the product can be negative — and BigInteger.divide truncates toward zero
        // rather than flooring, the exact asymmetry Rational.floor() documents. Going through
        // Rational (denominator 1000, always positive) reuses that floor and matches the on-chain
        // number exactly instead of drifting from it whenever the fee is negative.
        BigInteger liquidationFee = Rational.required(
                        loan.collateralAmount().multiply(bond.datum().liquidationFeePerMille()),
                        BigInteger.valueOf(1000))
                .floor();

        return LiquidationAssessment.buildable(bond, loan, "buildable liquidation",
                remainingDebt, equity, late, liquidationFee);
    }

    /**
     * Resolves one loan leg's price feed, exactly as {@code retrieve_oracle_data} would: the
     * {@code expectedTokenPolicyId == ""} branch synthesises the 1:1 unit feed with no oracle
     * consulted at all, and every other asset is looked up by the oracle NFT the datum names —
     * {@link FluidOracleClient#findEntryByOracleToken(AssetType)} is the tx-correct lookup per its
     * own javadoc, not a lookup on the priced asset itself.
     * <p>
     * A {@code null} client (disabled/absent {@link FluidOracleClient}) is reported unusable
     * rather than throwing, same as any other missing feed.
     */
    private static FeedLookup lookupFeed(boolean isAda, AssetType oracleToken, FluidOracleClient client,
                                         long atTimeMillis, String leg) {
        if (isAda) {
            return FeedLookup.usable(OraclePriceFeed.unit());
        }
        if (client == null) {
            return FeedLookup.unusable(leg + " leg: oracle client disabled");
        }
        var entry = client.findEntryByOracleToken(oracleToken);
        if (entry.isEmpty()) {
            return FeedLookup.unusable(leg + " leg: no oracle entry for " + oracleToken.toUnit());
        }
        OracleEntry oracleEntry = entry.get();
        if (!oracleEntry.usableForLiquidation()) {
            return FeedLookup.unusable(leg + " leg: oracle entry for " + oracleToken.toUnit()
                    + " is not usable for liquidation");
        }
        if (!oracleEntry.feed().usableAt(atTimeMillis)) {
            return FeedLookup.unusable(leg + " leg: oracle feed for " + oracleToken.toUnit()
                    + " is outside its validity window at " + atTimeMillis);
        }
        return FeedLookup.usable(oracleEntry.feed());
    }

    /** The outcome of resolving one leg's feed: either a usable feed, or a reason it is not. */
    private record FeedLookup(OraclePriceFeed feed, String unusableDetail) {
        static FeedLookup usable(OraclePriceFeed feed) {
            return new FeedLookup(feed, null);
        }

        static FeedLookup unusable(String detail) {
            return new FeedLookup(null, detail);
        }

        boolean usable() {
            return feed != null;
        }
    }
}
