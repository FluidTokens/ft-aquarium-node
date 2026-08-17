package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The T-016 S1 pool-origination fixture: <b>our factory's own</b> preview pool, spelled out
 * parameter by parameter, plus the born-liquidatable property check the later liquidation slices
 * lean on and the eight published preview reference-script coordinates.
 *
 * <h2>Our pool, not FluidTokens' pool</h2>
 * {@link PoolTxEncoderTest} pins {@link PoolTxEncoder} against FluidTokens' <em>own</em> deployed
 * preview pool ({@code 0edd513b…#0}), whose datum this repo did not choose. This class is the pool
 * <em>we</em> would create: same collateral (tFLDT + its FluidTokens oracle) and same interest/LTV
 * numbers, but {@code dynamicCollateralPrice = false} (the no-oracle floor path), a
 * {@link #LIQUIDATION_FEE_PER_MILLE} the bot can actually collect, and
 * {@code lenderAuth = CardanoSignature(ourKeyHash)} — we mint only the pool NFT, never a
 * pool-manager NFT, because {@code pool.pool} never mentions the manager.
 *
 * <h2>{@code pool.pool}'s mint never reads the datum</h2>
 * As with the request mint, {@code pool.pool}'s {@code mint} handler validates only the NFT name and
 * placement, never the {@code PoolDatum} ({@link PoolCreateDryEvalTest}, consequence B). So no field
 * below is arbitrated by dry-evaluation; the schema pins, the goldens and these justifications are
 * the datum's only defences until a Borrow or Cancel spends the pool.
 */
public final class PoolFixtures {

    private PoolFixtures() {
    }

    // ---- the collateral token and its FluidTokens oracle ----------------------------------------

    /** Preview tFLDT — the same {@link RequestFixtures#TFLDT} the request fixture uses. */
    public static final AssetType TFLDT = RequestFixtures.TFLDT;

    /**
     * tFLDT's <b>FluidTokens</b> oracle NFT (the {@code fluidOracle} entry of
     * {@code oracle-registry-preview.json}), <em>not</em> the Charli3 feed. This is the
     * {@code oracleTokenAsset} the deployed pool {@code 0edd513b…#0} names in its single collateral
     * option, so our pool names the same one.
     */
    public static final AssetType TFLDT_FLUID_ORACLE = new AssetType(
            "9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");

    /** tFLDT as a collateral option with its FluidTokens oracle attached. */
    public static final CollateralAsset COLLATERAL = new CollateralAsset(
            TFLDT.policyId(), Optional.of(TFLDT.assetName()), TFLDT_FLUID_ORACLE);

    /** {@code constants.no_permissioned_condition} — the hex of ASCII {@code "NONE"}. */
    public static final String NO_PERMISSIONED_CONDITION = "4e4f4e45";

    /** The fee the bot collects, per mille of the liquidated collateral — <b>100</b>, i.e. 10%. */
    public static final long LIQUIDATION_FEE_PER_MILLE = 100L;

    // ---- the pool parameters ---------------------------------------------------------------------

    /**
     * The knobs a pool creator turns.
     *
     * @param collateralPerPrincipalNumerator the <b>no-oracle-path floor multiplier</b>, NOT an LTV.
     *                                         With {@code dynamicCollateralPrice = false}
     *                                         ({@code finance.ak get_min_principal_for_collateral_without_oracles})
     *                                         the collateral a borrower must post is
     *                                         {@code neededCollateral = ceil(principal × numerator / divider)}
     *                                         — {@code minCollateral} / {@code minCollateralDivider}
     *                                         are collateral units per principal unit. So {@code 2/1}
     *                                         on a 5,000,000 principal is a 10,000,000-unit floor, and
     *                                         the born-liquidatable property comes from the collateral
     *                                         <em>amount</em> posted against the price, never from this
     *                                         ratio being read as a loan-to-value.
     */
    public record PoolParameters(long principalLovelace,
                                 long collateralPerPrincipalNumerator,
                                 long collateralPerPrincipalDivider,
                                 long interestRate,
                                 long liquidationLtv,
                                 long liquidationLtvDivider,
                                 long partialLiquidationPenaltyPerMille,
                                 long liquidationFeePerMille,
                                 long poolLiquidityLovelace,
                                 long loanOutputLovelace) {
    }

    /**
     * The default factory pool: a 5 ADA principal floor, a 2/1 no-oracle collateral floor, a 4.59%
     * base rate ({@code interestRate 459}), an 80% liquidation LTV ({@code 100/125}), a positive
     * partial-liquidation penalty of <b>90‰</b>, a {@link #LIQUIDATION_FEE_PER_MILLE 100‰} bot fee,
     * 52 ADA of pool liquidity and a 1.5 ADA loan output floor.
     *
     * <h2>Why the penalty (90‰) and the fee (100‰) deliberately differ</h2>
     * These are two positional {@link PoolParameters} args flowing to unrelated destinations — the
     * penalty into the pool's {@code commonData} ({@link #commonData}), the fee into the lender-bond
     * datum ({@link #templateBondDatum}). Were they both 100, a transposition of the two args would be
     * value-invisible and {@link #assertBornLiquidatable}'s fee guard could be reading the wrong field
     * with nobody able to tell (T-016 S1 audit finding, folded into S2). Splitting them makes the
     * fee-100 invariant verifiable against the FEE field alone: 90 stays safe for born-liquidatable,
     * because equity clamps to zero whenever origination LTV ≥ {@code 1000/(1000+90) ≈ 0.917} and this
     * loan is born at LTV ≈ 1.48 (the {@code 1/2} price in {@link PoolCreateDryEvalTest}), so nothing
     * about the epic's shape changes. The penalty is still {@code ≤ 1000}, which
     * {@link #assertBornLiquidatable} requires for equity to floor at all.
     */
    public static PoolParameters defaults() {
        return new PoolParameters(5_000_000L, 2L, 1L, 459L, 100L, 125L, 90L,
                LIQUIDATION_FEE_PER_MILLE, 52_000_000L, 1_500_000L);
    }

    /**
     * Throws unless a loan originated from {@code params} at the collateral price
     * {@code priceNumerator/priceDenominator} (lovelace per smallest collateral unit) is
     * <b>born liquidatable</b> with a collectable fee — the property the later liquidation slices
     * rely on to have a real target to build against. The four conditions, each a plain
     * {@link LoanFinance} call so the check cannot drift from the engine:
     * <ol>
     *   <li><b>origination LTV ≥ 1</b> (and the penalty ≤ 1000‰), so the borrower is underwater at
     *       origination and {@code redeemerEquity} floors to zero — no positive-equity liquidation,
     *       which the deployed contracts cannot satisfy (finding E1);</li>
     *   <li>the loan is <b>liquidatable at that price</b> by {@link LoanFinance#canLiquidate}, the
     *       engine's own {@code can_liquidate};</li>
     *   <li>{@code neededCollateral > 1}, so the fee slice can round to a whole unit;</li>
     *   <li>{@code feePerMille > 0} and {@code floor(collateral × fee / 1000) ≥ 1}, so the bot's
     *       profit gate has something to collect.</li>
     * </ol>
     */
    public static void assertBornLiquidatable(PoolParameters params, long priceNumerator,
                                              long priceDenominator) {
        BigInteger principal = BigInteger.valueOf(params.principalLovelace());
        BigInteger neededCollateral = Rational.required(
                principal.multiply(BigInteger.valueOf(params.collateralPerPrincipalNumerator())),
                BigInteger.valueOf(params.collateralPerPrincipalDivider())).ceil();

        OraclePriceFeed principalFeed = OraclePriceFeed.unit();
        OraclePriceFeed collateralFeed = OraclePriceFeed.aggregated(TFLDT,
                BigInteger.valueOf(priceNumerator), BigInteger.valueOf(priceDenominator), 0L, 0L);

        Rational debt = Rational.fromInt(principal);
        Rational collateral = Rational.fromInt(neededCollateral);
        Rational liquidationLtv = Rational.required(params.liquidationLtv(), params.liquidationLtvDivider());

        if (params.partialLiquidationPenaltyPerMille() > 1000) {
            throw new AssertionError("penalty " + params.partialLiquidationPenaltyPerMille()
                    + "‰ > 1000 — equity would not floor to zero even at LTV ≥ 1");
        }
        Rational currentLtv = LoanFinance.currentLtv(debt, collateral, principalFeed, collateralFeed);
        if (currentLtv.compareTo(Rational.fromInt(BigInteger.ONE)) < 0) {
            throw new AssertionError("origination LTV " + currentLtv + " < 1 at price "
                    + priceNumerator + "/" + priceDenominator + " — the borrower would have equity");
        }
        if (!LoanFinance.canLiquidate(debt, collateral, liquidationLtv, principalFeed, collateralFeed)) {
            throw new AssertionError("not liquidatable at price " + priceNumerator + "/" + priceDenominator
                    + ": liquidationLtv " + liquidationLtv + " is not below the current LTV " + currentLtv);
        }
        if (neededCollateral.compareTo(BigInteger.ONE) <= 0) {
            throw new AssertionError("neededCollateral " + neededCollateral + " ≤ 1 — the fee cannot round up");
        }
        if (params.liquidationFeePerMille() <= 0) {
            throw new AssertionError("liquidationFeePerMille " + params.liquidationFeePerMille() + " ≤ 0");
        }
        BigInteger fee = Rational.required(
                neededCollateral.multiply(BigInteger.valueOf(params.liquidationFeePerMille())),
                BigInteger.valueOf(1000)).floor();
        if (fee.compareTo(BigInteger.ONE) < 0) {
            throw new AssertionError("fee slice " + fee + " < 1 unit — nothing for the bot to collect");
        }
    }

    // ---- the datum -------------------------------------------------------------------------------

    /**
     * The pool's {@code CommonData}: ada principal and ada principal-oracle, everything time-related
     * zeroed, {@code Liquidation} with the params' LTV / penalty and {@code equityInPrincipalCurrency
     * = false} (finding D2), a {@code PerpetualLoan(28, 5)} repayment mode, no receipts, and the
     * borrower bond sent straight to the borrower ({@code borrowerBondDestinationScriptHash = ""}).
     */
    public static RequestTxEncoder.CommonData commonData(PoolParameters params) {
        return new RequestTxEncoder.CommonData(
                AssetType.ada(),
                AssetType.ada(),
                BigInteger.valueOf(params.interestRate()),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                new LiquidationMode.Liquidation(
                        BigInteger.valueOf(params.liquidationLtv()),
                        BigInteger.valueOf(params.liquidationLtvDivider()),
                        BigInteger.valueOf(params.partialLiquidationPenaltyPerMille()),
                        false),
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                BigInteger.ZERO,
                BigInteger.ZERO,
                false,
                "");
    }

    /**
     * The factory pool datum: {@code permissionedConditionScriptHash = "NONE"}, an Aiken-unit
     * {@code extraData}, the params' {@link #commonData}, the given {@code lenderAuth} and
     * {@code lenderBondAddress}, the given {@code lenderBondInlineDatumHash}, tFLDT + its FluidTokens
     * oracle as the single collateral option, {@code minCollateral = [numerator]},
     * {@code minCollateralDivider = [divider]}, and {@code dynamicCollateralPrice = false}.
     */
    public static PoolTxEncoder.PoolDatum poolDatum(PoolParameters params,
                                                    AuthorizationMethod lenderAuth,
                                                    Address lenderBondAddress,
                                                    String lenderBondInlineDatumHash) {
        return new PoolTxEncoder.PoolDatum(
                NO_PERMISSIONED_CONDITION,
                RequestTxEncoder.unit(),
                commonData(params),
                lenderAuth,
                lenderBondAddress,
                lenderBondInlineDatumHash,
                List.of(COLLATERAL),
                List.of(BigInteger.valueOf(params.collateralPerPrincipalNumerator())),
                List.of(BigInteger.valueOf(params.collateralPerPrincipalDivider())),
                false);
    }

    // ---- our factory pool, ready to build --------------------------------------------------------

    private static LoansContractRegistry registry() {
        return LoanFixtures.registry();
    }

    /** The pool-create seed UTxO's transaction hash — also the {@code inputRef} the mint names. */
    public static final String TX_SEED = "bb".repeat(32);

    /** The seed as a {@link TransactionInput}. */
    public static TransactionInput seed() {
        return new TransactionInput(TX_SEED, 0);
    }

    /** Our pool NFT asset name for {@link #seed()}: {@code 0x00 ‖ blake2b_224(serialise_data(seed))}. */
    public static String poolAssetName() {
        return PoolTxEncoder.poolAssetName(0, seed());
    }

    /** {@code lenderAuth = CardanoSignature(ourKeyHash)} — our bot's payment key. */
    public static AuthorizationMethod lenderAuth() {
        byte[] key = new Address(LoanFixtures.botAddress()).getPaymentCredentialHash().orElseThrow();
        return new AuthorizationMethod.CardanoSignature(HexUtil.encodeHexString(key));
    }

    /** Where our lender bonds go: the enterprise address of the LenderManager spend script. */
    public static Address lenderBondAddress() {
        return new Address(LoanFixtures.bondAddress());
    }

    /**
     * The {@link LenderManagerDatum} our pool would stamp on every lender bond, at the given fee. Its
     * {@code poolId} is our pool NFT name (inert at mint, load-bearing at borrow), its principal ada,
     * its stake credential {@code None} to match the enterprise {@link #lenderBondAddress()}.
     */
    public static LenderManagerDatum templateBondDatum(long liquidationFeePerMille) {
        return LoanFixtures.bondDatum(BigInteger.valueOf(liquidationFeePerMille),
                LoanFixtures.noStakeCredential(), AssetType.ada(), poolAssetName());
    }

    /** {@code blake2b_256(serialise_data(bondDatum))} — the whole-bond-datum commitment the pool carries. */
    public static String bondInlineDatumHash(LenderManagerDatum bondDatum) {
        try {
            byte[] serialised = LoanFixtures.encode(bondDatum).serializeToBytes();
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(serialised));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash the bond datum", e);
        }
    }

    /** Our factory pool datum at the default parameters, wired to our own auth / bond address / bond hash. */
    public static PoolTxEncoder.PoolDatum factoryPoolDatum(PoolParameters params) {
        return poolDatum(params, lenderAuth(), lenderBondAddress(),
                bondInlineDatumHash(templateBondDatum(params.liquidationFeePerMille())));
    }

    /** The enterprise pool address: {@code get_outputs_to_smart_credential}'s native-token branch. */
    public static String poolAddress() {
        return LoanFixtures.entAddress(registry().getPoolSpendScriptHash());
    }

    /** The config reference input, carrying the real preview {@code ConfigDatum}. */
    public static Utxo configUtxo() {
        return LoanFixtures.configUtxo(RequestFixtures.TX_CONFIG, 0);
    }

    /**
     * The seed UTxO: ample lovelace to fund the pool liquidity, the fee and the change. It is the
     * {@code inputRef} the mint redeemer names, so {@link PoolCreateTransactionBuilder} collects it
     * by name rather than leaving it to coin selection.
     */
    public static Utxo seedUtxo(String funderBech32) {
        return LoanFixtures.adaUtxo(TX_SEED, 0, funderBech32, 100_000_000L);
    }

    /**
     * The pool-policy reference-script UTxO: the published coordinate the pool policy's ref script
     * sits at ({@link #PUBLISHED_REFERENCE_SCRIPTS}). Modelled as a plain UTxO — in this offline rig
     * the pool policy itself travels in the witness set (attached by {@code mintAsset}), so this
     * reference input's only jobs are to be a realistic second reference input and to make
     * {@link PoolCreateTransactionBuilder} re-derive {@code configRefInputIndex} among two of them
     * rather than assume zero.
     */
    public static Utxo poolPolicyRefScriptUtxo() {
        String coordinate = PUBLISHED_REFERENCE_SCRIPTS.get(registry().getPoolPolicyId());
        String txHash = coordinate.substring(0, coordinate.indexOf('#'));
        int index = Integer.parseInt(coordinate.substring(coordinate.indexOf('#') + 1));
        return LoanFixtures.adaUtxo(txHash, index, LoanFixtures.botAddress(), 20_000_000L);
    }

    /** Everything the pool-create dry-eval rig may resolve: config ref input, seed, pool-policy ref script. */
    public static List<Utxo> universe(String funderBech32) {
        List<Utxo> universe = new ArrayList<>();
        universe.add(configUtxo());
        universe.add(seedUtxo(funderBech32));
        universe.add(poolPolicyRefScriptUtxo());
        return universe;
    }

    // ---- the T-016 S2 pool-borrow fixtures -------------------------------------------------------

    /** {@code POOL} in hex — the {@code bytearray.concat("POOL", poolId)} prefix of a pool-origin loan's originId. */
    public static final String ORIGIN_POOL_PREFIX =
            HexUtil.encodeHexString("POOL".getBytes(StandardCharsets.US_ASCII));

    /**
     * The collateral a borrower must post for {@code wantedPrincipalAmount} against {@code params}'
     * no-oracle floor: {@code ceil(wantedPrincipalAmount × minCollateral / minCollateralDivider)}
     * ({@code finance.ak get_needed_collateral_without_oracles}). The same {@link Rational} ceil
     * {@link #assertBornLiquidatable} uses, so the two cannot drift.
     */
    public static BigInteger neededCollateral(PoolParameters params, long wantedPrincipalAmount) {
        return Rational.required(
                BigInteger.valueOf(wantedPrincipalAmount)
                        .multiply(BigInteger.valueOf(params.collateralPerPrincipalNumerator())),
                BigInteger.valueOf(params.collateralPerPrincipalDivider())).ceil();
    }

    /**
     * The exact {@link LoanDatum} {@code pool_borrow_action}'s {@code validate_output_to_loan} builds
     * and compares with {@code equals_data} — every field taken from the pool's {@link #commonData}, so
     * the fixture cannot drift from the datum the pool actually stamps. The three fields the borrow
     * sets rather than the pool: {@code originId = "POOL" ‖ poolId}, {@code principalAmount =
     * wantedPrincipalAmount}, {@code lendDate = validTo} (the validity range's upper bound in POSIX
     * millis); {@code repaidInstallments} and {@code doneRecasts} are zero at origination.
     */
    public static LoanDatum borrowLoanDatum(PoolParameters params, String poolIdHex,
                                            long wantedPrincipalAmount, long lendDateMillis) {
        RequestTxEncoder.CommonData cd = commonData(params);
        return new LoanDatum(
                BigInteger.ZERO,                                // doneRecasts
                BigInteger.valueOf(wantedPrincipalAmount),      // principalAmount
                BigInteger.valueOf(lendDateMillis),             // lendDate
                BigInteger.ZERO,                                // repaidInstallments
                cd.interestRate(),
                cd.totalInstallments(),
                cd.principalAsset(),
                cd.principalOracleAsset(),
                cd.installmentPeriod(),
                cd.initialGracePeriod(),
                cd.liquidationMode(),
                cd.repaymentMode(),
                cd.repaymentTimeWindow(),
                cd.penaltyFeeForLateRepayment(),
                cd.repaymentReceipts(),
                ORIGIN_POOL_PREFIX + poolIdHex,
                COLLATERAL);
    }

    // ---- the published preview reference scripts -------------------------------------------------

    /**
     * The eight preview reference-script coordinates the later pool slices resolve, keyed by the
     * derived script hash so a coordinate can never drift onto the wrong script. Every key except the
     * FluidTokens oracle is a hash {@link LoansContractRegistry} derives and
     * {@code LoansContractDerivationTest} pins; the oracle's is external (it is applied to parameters
     * FluidTokens does not publish) and is pinned by {@code RealLoanDryEvalTest}. S1 uses only the
     * pool-policy entry; the rest are recorded here so the borrow / cancel slices inherit a single
     * verified table rather than re-transcribing coordinates.
     */
    public static final Map<String, String> PUBLISHED_REFERENCE_SCRIPTS = publishedReferenceScripts();

    private static Map<String, String> publishedReferenceScripts() {
        Map<String, String> m = new LinkedHashMap<>();
        // pool_borrow_action
        m.put("2fd32e80ffdc2435613f1977b4633b66d21f5ff4cf31d4fc7c6c64e1",
                "0f96f341b700446c7559a7797d4158784de07916888f368f7b8f85de0f274329#0");
        // lender bond, bond.bond(1)
        m.put("bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b",
                "479f7460c6f10e75065ec980dc60f0d43e3de73cced399911e5220c1e497496c#0");
        // pool policy, pool.pool
        m.put("65a0bc5e6e5152fbe2bf3e1053f4020f6c7ee0a563beb0fe070a7b93",
                "5ffc0e3574a9111da195cf55dd99078762c558d8dff050fc39530b335007da97#0");
        // loan policy, loan.loan
        m.put("4f84c6e3f4a7812d23a968e38dd22016abea07cdee48e80a17839476",
                "693d33c07b4bd73af1440cb717d8abbb4349489a535d4677a8940dc8b914dfb2#0");
        // FluidTokens oracle (external, not derivable)
        m.put("402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124",
                "ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f#0");
        // borrower bond, bond.bond(0)
        m.put("eadc69a5d2d1357acc9b9d49ec5390fcdf6e080c7a40139917223dcb",
                "d2d5b9b6ff1335862719a7d43c060dfe50fbd5445e900598e6b0ec1f62d34b94#0");
        // pool spend, general_spend over the pool
        m.put("c0be04e50016c124a9954b066cca5e76b19ab97b086666ad9f4c7c45",
                "ebbf1159ccc369dc0e117cae0a78aabfe7a9e0982d69232e11abbcc5d824eecd#0");
        // pool_cancel_action
        m.put("4e4c5ed0d8c96fd158fa70ed619b93ec6bb9d0dfb425f3961b35d95b",
                "c85c812d4668080ff3cd121377f08710b8ea3b4d893500f46a708de62ef4fc38#0");
        return m;
    }
}
