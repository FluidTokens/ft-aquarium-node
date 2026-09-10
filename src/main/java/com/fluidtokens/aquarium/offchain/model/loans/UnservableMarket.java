package com.fluidtokens.aquarium.offchain.model.loans;

import java.util.Collection;
import java.util.Comparator;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * ⛔ <b>A MARKET THE BOT MET AND COULD NOT SERVE, AND WHY.</b>
 *
 * <h2>A market is the token for the principal</h2>
 * Giovanni's vocabulary, adopted verbatim: <i>"for market is the token for the principal"</i>. Same
 * keying as {@code MarketGate}, which caps per-market exposure on the same identity, and the same
 * string {@code CompoundCandidateScanner} already logs — {@code principalAsset().toUnit()}.
 * <p>
 * The market is read off the <b>bond</b>, never the loan: an assessment may carry a {@code null}
 * loan ({@link LiquidationExclusion#LOAN_NOT_FOUND}) but never a null bond, so the bond is the only
 * place the market is always available.
 *
 * <h2>⚠ This class DECIDES NOTHING. It re-reads a verdict that already exists</h2>
 * Every value here is derived from {@link LiquidationAssessment}s the scanner has already produced.
 * Nothing in this file is consulted by any liquidation decision, and adding a reason to
 * {@link #isUnservable} can only change what is <em>reported</em>.
 *
 * <h2>The question this answers, and the one it does not</h2>
 * It answers <b>"is there something here we cannot do?"</b> — the failure Giovanni named: <i>"assume
 * a new stable coin loan appears and I wouldn't be able to process that loan if it went sour, well I
 * would need to know."</i> It does <b>not</b> answer "should we" (profitability, capital, market
 * policy); those refusals are about choice, not capability, and they belong to a different metric.
 * <p>
 * ⚠ It is therefore <b>not</b> "the bot is broken here". Three of the eleven exclusions below mean
 * the <em>validator itself</em> forbids liquidation, and no change to this node could ever serve
 * them — reporting those would page an operator about work that does not exist.
 *
 * @param market the token for the principal, as {@code policyId + assetName} (or {@code lovelace}) —
 *               the same canonical unit string used everywhere else in this package. Never a human
 *               name: {@link com.fluidtokens.aquarium.offchain.model.AssetType#unsafeHumanAssetName()}
 *               decodes attacker-chosen bytes, which must not reach a log line or a metric label.
 * @param reason the scanner's own machine-readable reason, unchanged
 */
public record UnservableMarket(String market, LiquidationExclusion reason)
        implements Comparable<UnservableMarket> {

    private static final Comparator<UnservableMarket> ORDER =
            Comparator.comparing(UnservableMarket::market)
                    .thenComparing(m -> m.reason().name());

    /**
     * ⛔ <b>Which exclusions mean "there is something here we cannot do".</b>
     *
     * <p>A deliberately exhaustive {@code switch} with <b>no {@code default}</b>: adding a constant
     * to {@link LiquidationExclusion} must not compile until someone has classified it. A default
     * arm would silently classify every future refusal as servable — the direction that fails
     * quiet, and quiet is the whole defect this slice exists to close.
     *
     * <h2>TRUE — we cannot act, or cannot even tell</h2>
     * <ul>
     *   <li>{@link LiquidationExclusion#PRINCIPAL_ORACLE_UNUSABLE} and
     *       {@link LiquidationExclusion#COLLATERAL_ORACLE_UNUSABLE} — <b>Giovanni's exact
     *       scenario.</b> A leg has no usable feed, so the loan cannot be priced and its health
     *       cannot even be established. {@code POOLED} and {@code PRICE_DATA_ORCFAX} feeds are not
     *       modelled by this node at all ({@code OracleEntry.usableForLiquidation}), so a market
     *       priced by one of them is unservable until the bot is changed.</li>
     *   <li>{@link LiquidationExclusion#HEALTH_NOT_COMPUTABLE} — our own arithmetic threw. Whatever
     *       the cause, we did not reach a verdict.</li>
     *   <li>{@link LiquidationExclusion#CONVERSION_TO_PRINCIPAL_REQUIRED} — a shape the plain
     *       {@code Liquidate} path does not build. <b>Not produced by
     *       {@code LiquidationCandidateScanner} today</b> (convert candidates are routed rather than
     *       excluded), so this arm is unreachable from a live scan; it is classified true because if
     *       the scanner ever emits it again it will mean exactly "a loan we are not equipped to
     *       liquidate".</li>
     * </ul>
     *
     * <h2>FALSE — there is nothing here to do, so there is nothing we are failing to do</h2>
     * <ul>
     *   <li>{@link LiquidationExclusion#LOAN_NOT_FOUND} — the ordinary post-settlement state: the
     *       loan closed and burned its NFT while the bond survives. Reporting it would page on
     *       history.</li>
     *   <li>{@link LiquidationExclusion#NOT_LIQUIDATABLE} — the loan is <em>healthy</em>. This is the
     *       single most common exclusion on a working node, and the one whose misclassification
     *       would make the metric useless.</li>
     *   <li>{@link LiquidationExclusion#MODE_NOT_LIQUIDATION} — the lender chose a bond mode with no
     *       liquidation. Their choice, not our gap.</li>
     *   <li>{@link LiquidationExclusion#EQUITY_IN_PRINCIPAL_CURRENCY},
     *       {@link LiquidationExclusion#COLLATERAL_IS_COLLECTION} and
     *       {@link LiquidationExclusion#COLLATERAL_AMOUNT_TOO_SMALL} — <b>the validator forbids the
     *       liquidation</b> (every working LenderManager liquidation action {@code expect}s the
     *       opposite). No off-chain change could serve these; nobody can.</li>
     *   <li>{@link LiquidationExclusion#BOND_NOT_DELEGATED} — produced only by the loan-anchored
     *       {@code GET /api/v1/loans} join, never by a scan. The lender never delegated to this bot,
     *       so there is nothing for it to do.</li>
     * </ul>
     */
    public static boolean isUnservable(LiquidationExclusion reason) {
        return switch (reason) {
            case PRINCIPAL_ORACLE_UNUSABLE,
                 COLLATERAL_ORACLE_UNUSABLE,
                 HEALTH_NOT_COMPUTABLE,
                 CONVERSION_TO_PRINCIPAL_REQUIRED -> true;
            case LOAN_NOT_FOUND,
                 NOT_LIQUIDATABLE,
                 MODE_NOT_LIQUIDATION,
                 EQUITY_IN_PRINCIPAL_CURRENCY,
                 COLLATERAL_IS_COLLECTION,
                 COLLATERAL_AMOUNT_TOO_SMALL,
                 BOND_NOT_DELEGATED -> false;
        };
    }

    /** The market one assessment sits in: the token for the principal, off the bond. */
    public static String marketOf(LiquidationAssessment assessment) {
        return assessment.bond().datum().principalAsset().toUnit();
    }

    /**
     * Every distinct (market, reason) pair one scan met and could not serve, each with <b>one</b>
     * example {@code detail} — the scanner's own sentence about the first assessment that produced
     * that pair.
     *
     * <p>⚠ <b>The detail is a value, never part of the key.</b> It carries an instant
     * ({@code "outside its validity window at 1757…"}), so keying on it would mint a new metric
     * series every cycle — the unbounded-cardinality failure this slice is forbidden to ship. The
     * key is the pair; the detail only ever reaches a log line.
     *
     * <p>Sorted, so the log and the gauge order are stable between cycles and a test can assert
     * them.
     */
    public static SortedMap<UnservableMarket, String> seenIn(
            Collection<LiquidationAssessment> assessments) {
        SortedMap<UnservableMarket, String> seen = new TreeMap<>(ORDER);
        for (LiquidationAssessment assessment : assessments) {
            if (assessment.buildable() || !isUnservable(assessment.exclusion())) {
                continue;
            }
            seen.putIfAbsent(new UnservableMarket(marketOf(assessment), assessment.exclusion()),
                    assessment.detail());
        }
        return seen;
    }

    @Override
    public int compareTo(UnservableMarket other) {
        return ORDER.compare(this, other);
    }
}
