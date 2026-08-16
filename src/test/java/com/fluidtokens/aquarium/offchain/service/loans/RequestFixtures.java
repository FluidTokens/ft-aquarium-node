package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The T-016 loan-origination fixture: one preview loan request, spelled out field by field with the
 * reason each value was chosen.
 *
 * <h2>Why every value is justified rather than plausible</h2>
 * {@code request.request}'s {@code mint} handler <b>never looks at the request output's datum</b>
 * (see {@link RequestMintDryEvalTest}, consequence B), so no amount of green dry-evaluation in this
 * slice arbitrates a single field below. The defences here are the schema pins
 * ({@link RequestTxEncoderSchemaTest}), the independently derived goldens
 * ({@link RequestTxEncoderTest}) — and these justifications, which is why they are written down.
 *
 * <h2>The fixture, field by field</h2>
 * <table>
 *   <caption>{@code RequestDatum} / {@code CommonData}</caption>
 *   <tr><th>Field</th><th>Value</th><th>Why</th></tr>
 *   <tr><td>{@code permissionedConditionScriptHash}</td><td>{@code "4e4f4e45"} (ASCII NONE)</td>
 *       <td>{@code constants.no_permissioned_condition}; takes the left branch at
 *       {@code request.ak:364-372}, so Lend needs no extra withdrawal</td></tr>
 *   <tr><td>{@code extraData}</td><td>Aiken unit, {@code Constr 0 []}</td><td>nothing reads it</td></tr>
 *   <tr><td>{@code commonData.principalAsset}</td><td>{@code AssetType.ada()}</td><td>ada principal</td></tr>
 *   <tr><td>{@code commonData.principalOracleAsset}</td><td>{@code AssetType.ada()}</td>
 *       <td>{@code retrieve_oracle_data} short-circuits on {@code expectedTokenPolicyId == ""}
 *       ({@code lib/fluidtokens/oracle.ak:39-50}); repo convention per
 *       {@code LoanFixtures.loanDatum}</td></tr>
 *   <tr><td>{@code commonData.interestRate}</td><td>{@code 0}</td>
 *       <td>with {@code apyIncreaseLinearCoefficient = 0} the remaining debt is time-independent —
 *       what the liquidation builder's V4 needs (the same number at both ends of the validity
 *       window). A non-zero rate makes {@code remainingDebt} a continuous function of wall-clock
 *       time and V4 refuses.</td></tr>
 *   <tr><td>{@code commonData.installmentPeriod}</td><td>{@code 0}</td>
 *       <td>perpetual, no installments; liquidation is LTV-driven</td></tr>
 *   <tr><td>{@code commonData.totalInstallments}</td><td>{@code 0}</td><td>ignored for perpetual</td></tr>
 *   <tr><td>{@code commonData.initialGracePeriod}</td><td>{@code 0}</td>
 *       <td><b>load-bearing</b>: non-zero makes the loan un-liquidatable for that many hours</td></tr>
 *   <tr><td>{@code commonData.liquidationMode}</td>
 *       <td>{@code Liquidation { lTV: 700, lTVDivider: 1000,
 *       partialLiquidationPenaltyPerMille: -1, equityInPrincipalCurrency: false } }</td>
 *       <td>see {@link #LIQUIDATION_MODE}</td></tr>
 *   <tr><td>{@code commonData.repaymentMode}</td>
 *       <td>{@code PerpetualLoan { apyIncreaseLinearCoefficient: 0, max_possible_recasts: 0 } }</td>
 *       <td>perpetual, no recasts</td></tr>
 *   <tr><td>{@code commonData.repaymentTimeWindow}</td><td>{@code 0}</td><td></td></tr>
 *   <tr><td>{@code commonData.penaltyFeeForLateRepayment}</td><td>{@code 0}</td><td></td></tr>
 *   <tr><td>{@code commonData.repaymentReceipts}</td><td>{@code false}</td>
 *       <td><b>mandatory</b> — the liquidate builder's veto V6 refuses {@code true}; the receipt
 *       mint is unmodelled</td></tr>
 *   <tr><td>{@code commonData.borrowerBondDestinationScriptHash}</td><td>{@code ""}</td>
 *       <td>sends the borrower bond straight to {@code borrowerAddress}
 *       ({@code request.ak:457-464}), keeping it recoverable and {@code locked_borrower_manager}
 *       out of the epic</td></tr>
 *   <tr><td>{@code borrowerAuth}</td><td>{@code CardanoSignature { hash: borrower payment key hash } }</td>
 *       <td>S3's Cancel authorises through this; derived from the borrower address, never typed</td></tr>
 *   <tr><td>{@code borrowerAddress}</td><td>a parameter, derived from a bech32 address</td>
 *       <td>S6 substitutes the real wallet; {@link LoanFixtures#botAddress()} by default</td></tr>
 *   <tr><td>{@code collateral}</td><td>preview tFLDT + its Charli3 feed</td>
 *       <td>see {@link #COLLATERAL}</td></tr>
 *   <tr><td>{@code minPrincipal} / {@code minPrincipalDivider}</td><td>{@code 3} / {@code 1}</td>
 *       <td>see {@link #MIN_PRINCIPAL}</td></tr>
 *   <tr><td>{@code maxPrincipal}</td><td>{@code 110_000_000}</td>
 *       <td>so {@code request.ak:342-346} admits {@code givenPrincipalAmount ∈ [100_000_000,
 *       110_000_000]}; S4 lends {@code 110_000_000}</td></tr>
 *   <tr><td>{@code dynamicCollateralPrice}</td><td>{@code false}</td>
 *       <td>also removes the oracle legs from TX B entirely</td></tr>
 *   <tr><td>{@code requestExpiration}</td><td>{@code 4_102_444_800_000}</td>
 *       <td>see {@link #REQUEST_EXPIRATION}</td></tr>
 *   <tr><td>{@code requestExpirationPenalty}</td><td>{@code 0}</td>
 *       <td>leaves a stranger no incentive to {@code CancelAfterExpiration} our request</td></tr>
 * </table>
 */
public final class RequestFixtures {

    private RequestFixtures() {
    }

    // ---- the collateral token and its oracle ---------------------------------------------------

    /**
     * Preview tFLDT, from {@code src/test/resources/loans-v4/oracle-registry-preview.json}
     * ({@code token.policyId} / {@code token.assetName}). Six decimals.
     */
    public static final AssetType TFLDT = new AssetType(
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");

    /**
     * tFLDT's Charli3 feed NFT, same fixture ({@code supportedOracle.c3}). The oracle token is
     * <b>mandatory</b> for this fixture: {@code validate_output_to_loan}'s
     * {@code isLiquidationCorrectlySet} ({@code request.ak:536-541}) requires
     * {@code oracleTokenAsset != Asset{"NONE","NONE"}} whenever the mode is {@code Liquidation}.
     */
    public static final AssetType TFLDT_C3_ORACLE = new AssetType(
            "decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");

    /** {@link #TFLDT} named by policy id and asset name, with its Charli3 feed attached. */
    public static final CollateralAsset COLLATERAL = new CollateralAsset(
            TFLDT.policyId(), Optional.of(TFLDT.assetName()), TFLDT_C3_ORACLE);

    /**
     * <b>300 whole tFLDT = 300,000,000 base units</b>, tFLDT having 6 decimals. Spelled out because
     * the ambiguity is expensive: read as 300 <em>base</em> units the collateral is worth roughly
     * 101 lovelace at the registry's 338163/1000000 price, the liquidation fee slice is ~10
     * lovelace, and the bot's profit gate refuses forever while every structural check still passes.
     */
    public static final long COLLATERAL_QUANTITY = 300_000_000L;

    // ---- the RequestDatum values ---------------------------------------------------------------

    /** {@code constants.no_permissioned_condition} — the hex of ASCII {@code "NONE"}. */
    public static final String NO_PERMISSIONED_CONDITION = "4e4f4e45";

    /**
     * {@code Liquidation { lTV: 700, lTVDivider: 1000, partialLiquidationPenaltyPerMille: -1,
     * equityInPrincipalCurrency: false } }.
     * <p>
     * {@code equityInPrincipalCurrency: false} is <b>mandatory</b>: {@code lm_liquidate_action.ak:122}
     * is a hard {@code expect .. == False} (finding D2).
     * <p>
     * The <b>negative</b> {@code partialLiquidationPenaltyPerMille} is equally deliberate.
     * {@code loan_claim_action.ak:241-243} disables equity outright when the penalty is negative —
     * {@code inputAction.equity == 0} unconditionally, at any price — and this repo's
     * {@code LoanFinance.redeemerEquity} already short-circuits to zero on a negative penalty
     * ({@code LoanFinance.java:247-249}). With a positive penalty the fixture would carry only about
     * 19% of price headroom before a tFLDT rally made the loan report as liquidatable while the
     * builder refused it with {@code POSITIVE_EQUITY_UNSUPPORTED} (positive-equity liquidations are
     * unsatisfiable on the deployed contracts, finding E1). A negative penalty makes the headroom the
     * full LTV band governed by {@code can_liquidate} alone. The borrower here is a disposable
     * fixture wallet that was never going to collect the equity.
     */
    public static final LiquidationMode.Liquidation LIQUIDATION_MODE =
            new LiquidationMode.Liquidation(BigInteger.valueOf(700), BigInteger.valueOf(1000),
                    BigInteger.valueOf(-1), false);

    /** {@code PerpetualLoan { apyIncreaseLinearCoefficient: 0, max_possible_recasts: 0 } }. */
    public static final RepaymentMode.PerpetualLoan REPAYMENT_MODE =
            new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO);

    /**
     * <b>Not a principal floor, despite the name.</b> {@code finance.ak:144-151}
     * ({@code get_min_principal_for_collateral_without_oracles}) computes the floor as
     * {@code ceil(collateralAmount / (minPrincipal / minPrincipalDivider))} — the pair is
     * <em>collateral units per single principal unit</em>. With {@link #COLLATERAL_QUANTITY} =
     * 300,000,000 tFLDT base units and 3/1, the floor is 100,000,000 lovelace.
     */
    public static final BigInteger MIN_PRINCIPAL = BigInteger.valueOf(3);

    /** The divider of {@link #MIN_PRINCIPAL}'s rational. */
    public static final BigInteger MIN_PRINCIPAL_DIVIDER = BigInteger.ONE;

    /** {@code request.ak:342-346} admits {@code givenPrincipalAmount ∈ [100_000_000, 110_000_000]}. */
    public static final BigInteger MAX_PRINCIPAL = BigInteger.valueOf(110_000_000);

    /**
     * 2100-01-01T00:00:00Z in POSIX millis — a <b>fixed absolute constant</b>, never
     * {@code now + X}: the goldens in {@link RequestTxEncoderTest} have to be reproducible.
     * {@code check_lend} requires {@code validTo < requestExpiration} ({@code request.ak:270}).
     */
    public static final BigInteger REQUEST_EXPIRATION = BigInteger.valueOf(4_102_444_800_000L);

    /** Zero: a stranger has no incentive to {@code CancelAfterExpiration} our request. */
    public static final BigInteger REQUEST_EXPIRATION_PENALTY = BigInteger.ZERO;

    // ---- the fixture universe --------------------------------------------------------------------

    /** The seed UTxO's transaction hash — also the {@code inputRef} the mint redeemer names. */
    public static final String TX_SEED = "ee".repeat(32);

    /** A second, pure-ada wallet UTxO: fee and collateral headroom, mirroring {@code BOT_SPARE_UTXO}. */
    public static final String TX_SPARE = "e1".repeat(32);

    /** The config reference input's transaction hash. */
    public static final String TX_CONFIG = "f1".repeat(32);

    /** {@link #secondRequestUtxo()}'s transaction hash — a decoy, never part of a happy path. */
    public static final String TX_SECOND_REQUEST = "a2".repeat(32);

    private static LoansContractRegistry registry() {
        return LoanFixtures.registry();
    }

    // ---- builders ----------------------------------------------------------------------------------

    /**
     * The fixture {@code CommonData}. Ada principal, ada principal oracle, everything time-related
     * zeroed — see the class table.
     */
    public static RequestTxEncoder.CommonData commonData() {
        return new RequestTxEncoder.CommonData(
                AssetType.ada(),
                AssetType.ada(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                LIQUIDATION_MODE,
                REPAYMENT_MODE,
                BigInteger.ZERO,
                BigInteger.ZERO,
                false,
                "");
    }

    /**
     * The fixture {@code RequestDatum} for a given borrower.
     * <p>
     * {@code borrowerAddress} is a <b>parameter, not a constant</b>: S6 substitutes the real wallet,
     * and the {@code borrowerAuth} hash is read off that same address rather than typed, so the two
     * cannot drift apart. Only a key-payment address is accepted — a script borrower would need a
     * {@code CardanoSpendScript} auth and a different Cancel story.
     */
    public static RequestTxEncoder.RequestDatum requestDatum(String borrowerBech32) {
        Address borrower = new Address(borrowerBech32);
        return new RequestTxEncoder.RequestDatum(
                NO_PERMISSIONED_CONDITION,
                RequestTxEncoder.unit(),
                commonData(),
                borrowerAuth(borrower),
                borrower,
                COLLATERAL,
                MIN_PRINCIPAL,
                MIN_PRINCIPAL_DIVIDER,
                MAX_PRINCIPAL,
                false,
                REQUEST_EXPIRATION,
                REQUEST_EXPIRATION_PENALTY);
    }

    /**
     * {@code CardanoSignature { hash: <borrower payment key hash> } } — the credential S3's Cancel
     * authorises through, read off the address rather than hand-typed.
     */
    public static AuthorizationMethod borrowerAuth(Address borrower) {
        byte[] paymentKeyHash = borrower.getPaymentCredentialHash().orElseThrow(
                () -> new IllegalArgumentException("borrower address has no payment credential: "
                        + borrower.getAddress()));
        return new AuthorizationMethod.CardanoSignature(HexUtil.encodeHexString(paymentKeyHash));
    }

    /**
     * The enterprise address of {@code requestSpendScriptHash} — the first, simple branch of
     * {@code smart_tokens/utils.get_outputs_to_smart_credential}
     * ({@code lib/smart-tokens/utils.ak:44-68}), which matches on
     * {@code output.address.payment_credential == Script(requestSpendScriptHash)} and ignores the
     * stake credential entirely.
     */
    public static String requestAddress() {
        return LoanFixtures.entAddress(registry().getRequestSpendScriptHash());
    }

    /**
     * The request NFT's asset name for a given seed: <b>{@code 0x00} ‖
     * {@code blake2b_224(serialise_data(OutputReference))}</b>, 29 bytes.
     * <p>
     * The {@code 0x00} is the token's <em>index</em> in {@code check_mint}'s
     * {@code mintedTokens} list ({@code request.ak:174-177}); this slice mints exactly one token, so
     * the index is zero. <b>Consequence A</b>: the loan NFT, borrower bond and lender bond minted by
     * TX B are 28 bytes with <em>no</em> prefix, and hash a <em>different</em> output reference (the
     * request UTxO's own, {@code request.ak:271}). Do not reuse this method for them.
     */
    public static String requestAssetName(TransactionInput seed) {
        byte[] preImage = serialise(RequestTxEncoder.outputReference(seed));
        byte[] hash = Blake2bUtil.blake2bHash224(preImage);
        return "00" + HexUtil.encodeHexString(hash);
    }

    /** {@code blake2b_224(serialise_data(..))} of any output reference, unprefixed — 28 bytes. */
    public static String hashOutputRef(TransactionInput ref) {
        return HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(serialise(RequestTxEncoder.outputReference(ref))));
    }

    private static byte[] serialise(PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise an OutputReference", e);
        }
    }

    /** The main config reference input, carrying the real preview {@code ConfigDatum}. */
    public static Utxo configUtxo() {
        return LoanFixtures.configUtxo(TX_CONFIG, 0);
    }

    /**
     * The borrower's seed UTxO: the collateral tFLDT plus ample lovelace. It is both the collateral
     * source and the {@code inputRef} the mint redeemer names, which is why
     * {@link RequestMintTransactionBuilder} collects it explicitly rather than leaving it to coin
     * selection.
     */
    public static Utxo seedUtxo(String borrowerBech32) {
        return LoanFixtures.utxo(TX_SEED, 0, borrowerBech32,
                List.of(Amount.lovelace(BigInteger.valueOf(50_000_000L)),
                        LoanFixtures.token(TFLDT, COLLATERAL_QUANTITY)),
                null);
    }

    /** A spare pure-ada UTxO at the same address, for fees and collateral. */
    public static Utxo spareUtxo(String borrowerBech32) {
        return LoanFixtures.adaUtxo(TX_SPARE, 0, borrowerBech32, 10_000_000L);
    }

    /** Everything the dry-eval rig may resolve: config reference input, seed, spare. */
    public static List<Utxo> universe(String borrowerBech32) {
        List<Utxo> universe = new ArrayList<>();
        universe.add(configUtxo());
        universe.add(seedUtxo(borrowerBech32));
        universe.add(spareUtxo(borrowerBech32));
        return universe;
    }

    /** The seed as a {@link TransactionInput} — what the mint redeemer's {@code inputRef} names. */
    public static TransactionInput seed() {
        return new TransactionInput(TX_SEED, 0);
    }

    // ---- the post-TX-A world (S3, Cancel) ----------------------------------------------------------

    /**
     * The request UTxO <b>TX A actually produced</b>, read off a built TX A rather than hand-rolled:
     * the single output at {@link #requestAddress()}, at that output's real index, carrying that
     * output's real value, with {@code RequestTxEncoderTest.REQUEST_DATUM_HEX} as its inline datum.
     * <p>
     * <b>The transaction hash is derived at runtime and must NEVER be hardcoded.</b> TX A's hash is
     * a hash of its whole body, so it moves the moment its ex-units or its fee change — and S6
     * prices it against a real evaluator, which changes both. A pinned literal here would go on
     * quietly building a Cancel for a UTxO TX A never produced: structurally perfect, unspendable,
     * and the collateral stranded at the request spend script. Chaining off the real builder output
     * is the only construction that cannot drift.
     * <p>
     * The datum is carried as the raw golden <b>hex</b>, never decoded and re-encoded: a round trip
     * through {@code PlutusData} would make this fixture agree with the encoder by construction
     * instead of pinning it.
     */
    public static Utxo requestUtxoFrom(Transaction txA) {
        Transaction onChain = deserialise(txA);
        List<TransactionOutput> outputs = onChain.getBody().getOutputs();
        int index = -1;
        for (int i = 0; i < outputs.size(); i++) {
            if (requestAddress().equals(outputs.get(i).getAddress())) {
                if (index >= 0) {
                    throw new IllegalStateException(
                            "TX A has more than one output at the request spend address");
                }
                index = i;
            }
        }
        if (index < 0) {
            throw new IllegalStateException("TX A has no output at the request spend address");
        }
        return LoanFixtures.utxo(TransactionUtil.getTxHash(onChain), index, requestAddress(),
                amountsOf(outputs.get(index)), RequestTxEncoderTest.REQUEST_DATUM_HEX);
    }

    /**
     * Everything a Cancel's rig may resolve: the config reference input, the request UTxO TX A
     * produced, and the borrower's spare pure-ada UTxO for fee and collateral.
     * <p>
     * <b>{@link #seedUtxo} is deliberately absent.</b> This models the world <em>after</em> TX A, and
     * TX A consumed the seed. Leaving it in the universe would let coin selection reach for a UTxO
     * that no longer exists — the Cancel would balance offline against a ledger state that cannot
     * happen.
     * <p>
     * <b>The spare does not carry the fee</b>, and saying so would be wrong. Measured on the finished
     * Cancel, the body has exactly <em>one</em> input — the request UTxO — and its own 5,000,000
     * lovelace pays for everything: 1,000,000 to the withdrawal-receiver output, 3,331,078 back as
     * change and 668,922 as fee, which is 5,000,000 exactly. The spare is nonetheless <b>required</b>,
     * as the <em>collateral</em> input: a Plutus transaction needs collateral, collateral must be a
     * pure-ada key UTxO, and the only other UTxO available is the multi-asset one sitting at the
     * request <em>script</em>. Drop the spare and the build has nothing it is allowed to pledge.
     */
    public static List<Utxo> cancelUniverse(String borrowerBech32, Utxo requestUtxo) {
        List<Utxo> universe = new ArrayList<>();
        universe.add(configUtxo());
        universe.add(requestUtxo);
        universe.add(spareUtxo(borrowerBech32));
        return universe;
    }

    /**
     * <b>A second UTxO at {@link #requestAddress()}</b>, and nothing else about it matters: it exists
     * so a test can make the request-input <em>count</em> in
     * {@code RequestCancelTransactionBuilder.assertStructure} come out as two rather than one, and
     * therefore make that guard discriminate on the credential instead of agreeing with itself.
     * <p>
     * Deliberately <b>not</b> in {@link #cancelUniverse}. A Cancel with two request inputs and one
     * {@code Cancel} action is refused on chain by an abort ({@code safe_list_at} running off
     * {@code actionsForEachInput}), so this must never leak into a happy path. It is pure ada, so
     * nothing about it resembles a real request UTxO beyond the one property under test — its
     * address.
     */
    public static Utxo secondRequestUtxo() {
        return LoanFixtures.adaUtxo(TX_SECOND_REQUEST, 0, requestAddress(), 5_000_000L);
    }

    /** An output's value as the {@code List<Amount>} shape {@link Utxo} wants. */
    private static List<Amount> amountsOf(TransactionOutput output) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(output.getValue().getCoin()));
        for (var multiAsset : output.getValue().getMultiAssets()) {
            for (var asset : multiAsset.getAssets()) {
                // Concatenated by hand rather than through Amount.asset(policy, name, qty): that
                // overload treats the name as literal UTF-8 and hex-encodes it again. See
                // LoanFixtures.unit()'s javadoc.
                String nameHex = asset.getNameAsHex();
                amounts.add(Amount.asset(multiAsset.getPolicyId()
                        + (nameHex.startsWith("0x") ? nameHex.substring(2) : nameHex),
                        asset.getValue()));
            }
        }
        return amounts;
    }

    /** Re-reads a transaction from its own bytes, so the outputs read are the ones a node would parse. */
    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new IllegalStateException("cannot round-trip TX A", e);
        }
    }
}
