package com.fluidtokens.aquarium.offchain.model.loans;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * ⛔ <b>A MARKET THE BOT MET AND COULD NOT SERVE, AND WHY.</b>
 *
 * <h2>A market is the token for the principal — and a loan has TWO legs</h2>
 * Giovanni's vocabulary, adopted verbatim: <i>"for market is the token for the principal"</i>. Same
 * keying as {@code MarketGate}, which caps per-market exposure on the same identity, and the same
 * string {@code CompoundCandidateScanner} already logs — {@code principalAsset().toUnit()}.
 * <p>
 * ⚠ <b>But a liquidation prices two assets, and either can be the one we cannot price.</b>
 * {@link LiquidationExclusion#COLLATERAL_ORACLE_UNUSABLE} is a gap in the <em>collateral</em> token's
 * coverage, and keying it by the principal was a real defect: two ada-principal loans whose different
 * collateral tokens had no feed collapsed into one series saying {@code market="lovelace"}, which
 * pages the operator about <b>ada</b> — a market that is always priceable — while the token that
 * actually needs a feed appears nowhere. Giovanni's <i>"a new stablecoin loan appears"</i> arrives as
 * collateral as often as as principal.
 * <p>
 * So the key names <b>the leg's own asset</b> and carries {@link Leg} to say which leg it is. The
 * asset is read from the only place it is reliably available for that leg:
 * <ul>
 *   <li>{@link Leg#PRINCIPAL} — off the <b>bond</b>. An assessment may carry a {@code null} loan
 *       ({@link LiquidationExclusion#LOAN_NOT_FOUND}) but never a null bond.</li>
 *   <li>{@link Leg#COLLATERAL} — off the <b>loan</b>, which is the only carrier of
 *       {@code CollateralAsset}. It is never null for a collateral-leg refusal: the scanner returns
 *       {@code LOAN_NOT_FOUND} before it ever reaches the collateral feed lookup, and
 *       {@code LOAN_NOT_FOUND} is classified servable and so is never reported.</li>
 * </ul>
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
 * @param market the token for this LEG, as {@code policyId + assetName} (or {@code lovelace}) — the
 *               same canonical unit string used everywhere else in this package. Never a human
 *               name: {@link com.fluidtokens.aquarium.offchain.model.AssetType#unsafeHumanAssetName()}
 *               decodes attacker-chosen bytes, which must not reach a log line or a metric label.
 * @param leg    which side of the loan {@link #market} is — the label that stops a collateral-side
 *               gap reading as a principal-side one
 * @param reason the scanner's own machine-readable reason, unchanged
 */
public record UnservableMarket(String market, Leg leg, LiquidationExclusion reason)
        implements Comparable<UnservableMarket> {

    /**
     * Which side of the loan an unservable reason is about. A liquidation prices the principal and
     * the collateral separately, and the two are different tokens with different feeds.
     */
    public enum Leg {
        PRINCIPAL,
        COLLATERAL;

        /** The metric label value: lower case, so {@code leg="collateral"} reads as prose. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Comparator<UnservableMarket> ORDER =
            Comparator.comparing(UnservableMarket::market)
                    .thenComparing(m -> m.leg().name())
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

    /**
     * ⛔ <b>Which leg of the loan a refusal is about</b> — the thing that decides which token gets
     * named.
     *
     * <p>Exhaustive and {@code default}-less for the same reason as {@link #isUnservable}: a new
     * constant must not compile until someone has said which asset it is about. The seven servable
     * reasons throw rather than picking a leg, because they are never reported and a leg for them
     * would be a guess that nothing would ever contradict.
     *
     * <p>{@link LiquidationExclusion#HEALTH_NOT_COMPUTABLE} and
     * {@link LiquidationExclusion#CONVERSION_TO_PRINCIPAL_REQUIRED} are about the loan as a whole
     * rather than about one feed, so they take the principal — which is Giovanni's market — rather
     * than being split across both legs.
     */
    public static Leg legOf(LiquidationExclusion reason) {
        return switch (reason) {
            case COLLATERAL_ORACLE_UNUSABLE -> Leg.COLLATERAL;
            case PRINCIPAL_ORACLE_UNUSABLE,
                 HEALTH_NOT_COMPUTABLE,
                 CONVERSION_TO_PRINCIPAL_REQUIRED -> Leg.PRINCIPAL;
            case LOAN_NOT_FOUND,
                 NOT_LIQUIDATABLE,
                 MODE_NOT_LIQUIDATION,
                 EQUITY_IN_PRINCIPAL_CURRENCY,
                 COLLATERAL_IS_COLLECTION,
                 COLLATERAL_AMOUNT_TOO_SMALL,
                 BOND_NOT_DELEGATED -> throw new IllegalArgumentException(
                    reason + " is servable — isUnservable() says so — and a servable reason has no "
                            + "leg, because nothing reports it and nothing would contradict a guess");
        };
    }

    /**
     * The asset one leg of one assessment names.
     *
     * <p>⛔ <b>There is no fallback to the principal.</b> A collateral-leg refusal whose loan is
     * missing would be a violation of {@link LiquidationAssessment}'s own contract (a null loan is
     * possible only for {@code LOAN_NOT_FOUND}, which is servable and never reported), and naming
     * the principal instead would be exactly the mis-attribution this leg label exists to end. It
     * throws instead; {@code LiquidationExecutor} calls the reporter inside a guard, so the cycle is
     * unaffected and the fault is logged every cycle until someone looks.
     */
    public static String assetOf(LiquidationAssessment assessment, Leg leg) {
        return switch (leg) {
            case PRINCIPAL -> assessment.bond().datum().principalAsset().toUnit();
            case COLLATERAL -> {
                Loan loan = assessment.loan();
                if (loan == null) {
                    throw new IllegalStateException(
                            "collateral-leg refusal " + assessment.exclusion() + " on bond "
                                    + assessment.bond().utxoRef() + " carries no loan, so the "
                                    + "collateral token cannot be named; refusing to report the "
                                    + "principal in its place");
                }
                yield loan.datum().collateral().assetType().toUnit();
            }
        };
    }

    /** The (asset, leg, reason) triple one unservable assessment sits in. */
    public static UnservableMarket of(LiquidationAssessment assessment) {
        Leg leg = legOf(assessment.exclusion());
        return new UnservableMarket(assetOf(assessment, leg), leg, assessment.exclusion());
    }

    /**
     * Every distinct {@code (market, leg, reason)} triple one scan met and could not serve, each with
     * <b>one</b> example {@code detail} — the scanner's own sentence about the first assessment that
     * produced that triple.
     *
     * <p>⚠ <b>The detail is a value, never part of the key.</b> It carries an instant
     * ({@code "outside its validity window at 1757…"}), so keying on it would mint a new metric
     * series every cycle — the unbounded-cardinality failure this slice is forbidden to ship. The
     * key is the triple; the detail only ever reaches a log line.
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
            seen.putIfAbsent(of(assessment), assessment.detail());
        }
        return seen;
    }

    @Override
    public int compareTo(UnservableMarket other) {
        return ORDER.compare(this, other);
    }
}
