package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Synthetic Lending v4 fixtures for {@link LiquidateTransactionBuilderTest}.
 *
 * <h2>The test-scope datum encoder</h2>
 * The production code only ever <em>decodes</em> a {@code LoanDatum} / {@code LenderManagerDatum} —
 * the loan validators write them, the bot never does — so no encoder exists in {@code src/main},
 * and this one deliberately stays in test scope so none is accidentally introduced there.
 * <p>
 * A hand-written encoder that quietly disagrees with the decoder would make every fixture below a
 * lie, so it is pinned twice in {@link LiquidateTransactionBuilderTest}: by round-tripping through
 * the production {@link LoanDatumConverter}/{@link LenderManagerDatumConverter}, and — for
 * {@code LoanDatum} — by re-encoding a datum recorded off preview
 * ({@code src/test/resources/loans-v4/preview-loan-datums.hex}) back to the exact bytes the chain
 * produced.
 *
 * <h2>Everything is preview</h2>
 * The registry inputs are the live preview config policy ids (the same constants
 * {@code LoansContractDerivationTest} proves against the deployed contracts), so every script hash,
 * address and reward address in a fixture is a real derived one rather than a made-up 28-byte
 * string.
 */
public final class LoanFixtures {

    private LoanFixtures() {
    }

    // ---- preview derivation inputs (see LoansContractDerivationTest) -------------------------
    //
    // THIRD preview deployment, config NFTs minted in tx 7374a985…e781 (outputs 0 and 1). These
    // must stay in step with the two recorded config datums under src/test/resources/loans-v4:
    // the validators compare the hashes they derive from these policy ids against the ones the
    // datum publishes, so a mismatched pair makes every dry-eval fixture unsatisfiable.

    public static final String CONFIG_POLICY_ID = "c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9";
    public static final String LM_CONFIG_POLICY_ID = "de1b8b40536f96c1084d73f838ebac6b228d891902d6234afc731484";
    public static final String CONFIG_ASSET_NAME = "706172616d6574657273";

    public static final Network NETWORK = Networks.preview();

    /** {@code constants.no_oracle_token_asset} — the ASCII "NONE" sentinel, in hex. */
    public static final AssetType NO_ORACLE = new AssetType("4e4f4e45", "4e4f4e45");

    private static final LoansContractRegistry REGISTRY =
            new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, null);

    public static LoansContractRegistry registry() {
        return REGISTRY;
    }

    public static CardanoConverters converters() {
        return ClasspathConversionsFactory.createConverters(NetworkType.PREVIEW);
    }

    // ---- addresses ---------------------------------------------------------------------------

    public static String loanAddress() {
        return entAddress(REGISTRY.getLoanSpendScriptHash());
    }

    public static String bondAddress() {
        return entAddress(REGISTRY.getLenderManagerSpendScriptHash());
    }

    /** An ordinary key-hash base address for the bot: fees in, change and the fee slice out. */
    public static String botAddress() {
        return AddressProvider.getBaseAddress(
                Credential.fromKey("11111111111111111111111111111111111111111111111111111111"),
                Credential.fromKey("22222222222222222222222222222222222222222222222222222222"),
                NETWORK).getAddress();
    }

    public static String entAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(scriptHash), NETWORK).getAddress();
    }

    public static String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), NETWORK).getAddress();
    }

    // ---- test-scope datum encoders -----------------------------------------------------------

    /** {@code LoanDatum} — constructor 0, 17 fields, in {@link LoanDatum}'s declaration order. */
    public static PlutusData encode(LoanDatum d) {
        return constr(0,
                bigInt(d.doneRecasts()),
                bigInt(d.principalAmount()),
                bigInt(d.lendDate()),
                bigInt(d.repaidInstallments()),
                bigInt(d.interestRate()),
                bigInt(d.totalInstallments()),
                asset(d.principalAsset()),
                asset(d.principalOracleAsset()),
                bigInt(d.installmentPeriod()),
                bigInt(d.initialGracePeriod()),
                encode(d.liquidationMode()),
                encode(d.repaymentMode()),
                bigInt(d.repaymentTimeWindow()),
                bigInt(d.penaltyFeeForLateRepayment()),
                bool(d.repaymentReceipts()),
                bytes(d.originId()),
                encode(d.collateral()));
    }

    /** {@code LenderManagerDatum} — constructor 0, 6 fields. */
    public static PlutusData encode(LenderManagerDatum d) {
        return constr(0,
                encode(d.lenderAuth()),
                d.lenderStakeCredential(),
                bool(d.shouldLiquidationConvertToPrincipal()),
                bigInt(d.liquidationFeePerMille()),
                bytes(d.poolId()),
                asset(d.principalAsset()));
    }

    public static String hex(LoanDatum d) {
        return encode(d).serializeToHex();
    }

    public static String hex(LenderManagerDatum d) {
        return encode(d).serializeToHex();
    }

    private static PlutusData encode(CollateralAsset c) {
        return constr(0,
                bytes(c.policyId()),
                c.assetName().map(name -> constr(0, bytes(name))).orElseGet(() -> constr(1)),
                asset(c.oracleTokenAsset()));
    }

    private static PlutusData encode(LiquidationMode mode) {
        return switch (mode) {
            case LiquidationMode.NoLiquidationFullCollateralClaim ignored -> constr(0);
            case LiquidationMode.NoLiquidationDutchAuctionClaim ignored -> constr(1);
            case LiquidationMode.Liquidation l -> constr(2,
                    bigInt(l.ltv()),
                    bigInt(l.ltvDivider()),
                    bigInt(l.partialLiquidationPenaltyPerMille()),
                    bool(l.equityInPrincipalCurrency()));
        };
    }

    private static PlutusData encode(RepaymentMode mode) {
        return switch (mode) {
            case RepaymentMode.InterestOnRemainingPrincipal m -> constr(0, bigInt(m.maxPossibleRecasts()));
            case RepaymentMode.PrincipalAndInterestOnInstallments ignored -> constr(1);
            case RepaymentMode.PerpetualLoan m -> constr(2,
                    bigInt(m.apyIncreaseLinearCoefficient()), bigInt(m.maxPossibleRecasts()));
        };
    }

    private static PlutusData encode(AuthorizationMethod auth) {
        return switch (auth) {
            case AuthorizationMethod.CardanoSignature a -> constr(0, bytes(a.hash()));
            case AuthorizationMethod.CardanoSpendScript a -> constr(1, bytes(a.hash()));
            case AuthorizationMethod.CardanoWithdrawScript a -> constr(2, bytes(a.hash()));
            case AuthorizationMethod.CardanoMintScript a -> constr(3, bytes(a.hash()));
        };
    }

    private static PlutusData asset(AssetType asset) {
        return constr(0,
                BytesPlutusData.of(asset.getPlutusDataPolicyId()),
                BytesPlutusData.of(asset.getPlutusDataAssetName()));
    }

    private static PlutusData bool(boolean b) {
        return constr(b ? 1 : 0);
    }

    private static PlutusData bigInt(BigInteger value) {
        return BigIntPlutusData.of(value);
    }

    private static PlutusData bytes(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }

    // ---- stake credentials -------------------------------------------------------------------

    /** {@code None} — the asset outputs end up at an enterprise address. */
    public static PlutusData noStakeCredential() {
        return constr(1);
    }

    /** {@code Some(Inline(VerificationKey(hash)))}. */
    public static PlutusData inlineKeyStakeCredential(String keyHash) {
        return constr(0, constr(0, constr(0, bytes(keyHash))));
    }

    /** {@code Some(Pointer{..})} — refused by the builder, and here only so that can be proven. */
    public static PlutusData pointerStakeCredential() {
        return constr(0, constr(1, bigInt(BigInteger.ONE), bigInt(BigInteger.TWO),
                bigInt(BigInteger.valueOf(3))));
    }

    /** Neither {@code Some} nor {@code None}: an {@code Option} constructor that does not exist. */
    public static PlutusData undecodableStakeCredential() {
        return constr(2);
    }

    // ---- datums ------------------------------------------------------------------------------

    /**
     * A late, installment-repaid loan: {@code PrincipalAndInterestOnInstallments} makes the
     * remaining debt time-independent, which is what lets a fixture satisfy V4's demand that the
     * number be the same at both ends of the validity window.
     */
    public static LoanDatum loanDatum(AssetType principalAsset,
                                      BigInteger principalAmount,
                                      BigInteger interestRate,
                                      CollateralAsset collateral,
                                      long lendDateMillis,
                                      LiquidationMode mode,
                                      RepaymentMode repaymentMode,
                                      boolean repaymentReceipts) {
        return loanDatum(principalAsset, principalAsset.isAda() ? AssetType.ada() : NO_ORACLE,
                principalAmount, interestRate, collateral, lendDateMillis, mode, repaymentMode,
                repaymentReceipts);
    }

    /** As above, with the principal leg's oracle NFT chosen explicitly. */
    public static LoanDatum loanDatum(AssetType principalAsset,
                                      AssetType principalOracleAsset,
                                      BigInteger principalAmount,
                                      BigInteger interestRate,
                                      CollateralAsset collateral,
                                      long lendDateMillis,
                                      LiquidationMode mode,
                                      RepaymentMode repaymentMode,
                                      boolean repaymentReceipts) {
        return new LoanDatum(
                BigInteger.ZERO,
                principalAmount,
                BigInteger.valueOf(lendDateMillis),
                BigInteger.ZERO,
                interestRate,
                BigInteger.ONE,
                principalAsset,
                principalOracleAsset,
                BigInteger.valueOf(24),
                BigInteger.ZERO,
                mode,
                repaymentMode,
                BigInteger.ZERO,
                BigInteger.ZERO,
                repaymentReceipts,
                "",
                collateral);
    }

    /** The bot-liquidatable mode: {@code Liquidation} with equity in the collateral currency. */
    public static LiquidationMode.Liquidation liquidation() {
        return new LiquidationMode.Liquidation(BigInteger.valueOf(750), BigInteger.valueOf(1000),
                BigInteger.valueOf(50), false);
    }

    /**
     * {@link #liquidation()} with D2's flag set. {@code lm_liquidate_action.ak:122} is a hard
     * {@code expect equityInPrincipalCurrency == False}, so this mode exists only so the refusal can
     * be proven.
     */
    public static LiquidationMode.Liquidation liquidationInPrincipalCurrency() {
        return new LiquidationMode.Liquidation(BigInteger.valueOf(750), BigInteger.valueOf(1000),
                BigInteger.valueOf(50), true);
    }

    public static CollateralAsset adaCollateral() {
        return new CollateralAsset("", Optional.of(""), NO_ORACLE);
    }

    public static CollateralAsset tokenCollateral(AssetType token, AssetType oracleToken) {
        return new CollateralAsset(token.policyId(), Optional.of(token.assetName()), oracleToken);
    }

    public static LenderManagerDatum bondDatum(BigInteger liquidationFeePerMille,
                                               PlutusData stakeCredential,
                                               AssetType principalAsset) {
        return bondDatum(liquidationFeePerMille, stakeCredential, principalAsset, "");
    }

    /** As above, with an explicit {@code poolId} — the field the chunked-datum fixture stretches. */
    public static LenderManagerDatum bondDatum(BigInteger liquidationFeePerMille,
                                               PlutusData stakeCredential,
                                               AssetType principalAsset,
                                               String poolIdHex) {
        return new LenderManagerDatum(
                new AuthorizationMethod.CardanoSignature(
                        "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8"),
                stakeCredential,
                false,
                liquidationFeePerMille,
                poolIdHex,
                principalAsset);
    }

    /**
     * A bond demanding the liquidation proceeds be converted to the principal currency.
     * {@code lm_liquidate_action.ak:143} makes {@code shouldLiquidationConvertToPrincipal == False} a
     * conjunct of the plain {@code Liquidate} check, so this bond exists only so the refusal can be
     * proven. Identical to {@link #bondDatum(BigInteger, PlutusData, AssetType)} in every other field.
     */
    public static LenderManagerDatum convertToPrincipalBondDatum(BigInteger liquidationFeePerMille,
                                                                 PlutusData stakeCredential,
                                                                 AssetType principalAsset) {
        LenderManagerDatum plain = bondDatum(liquidationFeePerMille, stakeCredential, principalAsset);
        return new LenderManagerDatum(plain.lenderAuth(), plain.lenderStakeCredential(), true,
                plain.liquidationFeePerMille(), plain.poolId(), plain.principalAsset());
    }

    // ---- utxos -------------------------------------------------------------------------------

    public static Utxo utxo(String txHash, int outputIndex, String address, List<Amount> amounts,
                            String inlineDatum) {
        Utxo utxo = new Utxo();
        utxo.setTxHash(txHash);
        utxo.setOutputIndex(outputIndex);
        utxo.setAddress(address);
        utxo.setAmount(new ArrayList<>(amounts));
        utxo.setInlineDatum(inlineDatum);
        return utxo;
    }

    public static Utxo adaUtxo(String txHash, int outputIndex, String address, long lovelace) {
        return utxo(txHash, outputIndex, address,
                List.of(Amount.lovelace(BigInteger.valueOf(lovelace))), null);
    }

    // ---- the two config reference inputs, with the datums the chain really carries ------------

    /**
     * A committed fixture under {@code src/test/resources/loans-v4}, read as a trimmed string.
     */
    public static String fixture(String name) {
        try (InputStream is = LoanFixtures.class.getResourceAsStream("/loans-v4/" + name)) {
            if (is == null) {
                throw new IllegalStateException("missing fixture /loans-v4/" + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    /**
     * The main config reference input as {@code utils.get_config_as_data_list} needs to see it: the
     * config NFT in the value (the {@code quantity_of(..) > 0} expectation) and the
     * <em>real</em> preview {@code ConfigDatum} inline
     * ({@code src/test/resources/loans-v4/preview-config-datum.hex}, the same bytes
     * {@code LoansConfigVerifierTest} verifies the derivation against).
     */
    public static Utxo configUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, CONFIG_POLICY_ID, fixture("preview-config-datum.hex"));
    }

    /** The LenderManager config reference input, carrying the real preview {@code LMConfigDatum}. */
    public static Utxo lmConfigUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, LM_CONFIG_POLICY_ID, fixture("preview-lm-config-datum.hex"));
    }

    private static Utxo configUtxo(String txHash, int outputIndex, String policyId, String datumHex) {
        return utxo(txHash, outputIndex, entAddress(policyId),
                List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(policyId + CONFIG_ASSET_NAME, BigInteger.ONE)),
                datumHex);
    }

    /**
     * The unit string for an asset. Built by concatenation on purpose: cardano-client-lib's
     * {@code Amount.asset(policy, assetName, qty)} treats {@code assetName} as a literal string and
     * hex-encodes it again, which silently produces a different token.
     */
    public static String unit(AssetType asset) {
        return asset.policyId() + asset.assetName();
    }

    public static Amount token(AssetType asset, long quantity) {
        return Amount.asset(unit(asset), BigInteger.valueOf(quantity));
    }

    public static TransactionInput input(String txHash, int index) {
        return new TransactionInput(txHash, index);
    }

    // ---- loans and bonds ---------------------------------------------------------------------

    /** A loan UTxO plus the decoded {@link Loan} the scanner would have produced from it. */
    public record LoanUtxo(Loan loan, Utxo utxo) {
    }

    /** A lender-bond UTxO plus the decoded {@link LenderBond}. */
    public record BondUtxo(LenderBond bond, Utxo utxo) {
    }

    public static LoanUtxo loanUtxo(String txHash, int outputIndex, String loanId, LoanDatum datum,
                                    long lovelace, List<Amount> extraAssets) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        amounts.add(Amount.asset(REGISTRY.getLoanPolicyId() + loanId, BigInteger.ONE));
        amounts.addAll(extraAssets);

        String datumHex = hex(datum);
        Utxo utxo = utxo(txHash, outputIndex, loanAddress(), amounts, datumHex);

        AssetType collateral = datum.collateral().assetType();
        BigInteger collateralAmount = collateral.isAda()
                ? BigInteger.valueOf(lovelace)
                : amounts.stream()
                .filter(amount -> unit(collateral).equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);

        Loan loan = new Loan(txHash, outputIndex, loanAddress(), loanId, collateralAmount,
                BigInteger.valueOf(lovelace), datum);
        return new LoanUtxo(loan, utxo);
    }

    public static BondUtxo bondUtxo(String txHash, int outputIndex, String loanId,
                                    LenderManagerDatum datum, long lovelace) {
        return bondUtxo(txHash, outputIndex, loanId, datum, lovelace, hex(datum));
    }

    /**
     * As above, but with the raw inline datum supplied separately from the decoded one. Real chain
     * bytes are not always what cardano-client-lib would re-emit for the same structure, and D6
     * needs the input's exact bytes echoed — this is how a fixture can carry bytes the library
     * would never produce on its own.
     */
    public static BondUtxo bondUtxo(String txHash, int outputIndex, String loanId,
                                    LenderManagerDatum datum, long lovelace, String rawDatumHex) {
        String datumHex = rawDatumHex;
        Utxo utxo = utxo(txHash, outputIndex, bondAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(lovelace)),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + loanId, BigInteger.ONE)),
                datumHex);
        LenderBond bond = new LenderBond(txHash, outputIndex, bondAddress(), loanId, datumHex, datum);
        return new BondUtxo(bond, utxo);
    }

    // ---- assessments -------------------------------------------------------------------------

    /**
     * The buildable assessment {@code LiquidationCandidateScanner} would produce for this pair —
     * same {@link LoanFinance} calls, same fee formula, so the builder's V4 guard agrees with it.
     */
    public static LiquidationAssessment assess(LenderBond bond, Loan loan, OraclePriceFeed principalFeed,
                                               OraclePriceFeed collateralFeed, long atMillis) {
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.datum().liquidationMode();
        BigInteger remainingDebt = LoanFinance.remainingDebt(loan.datum(), atMillis);
        boolean late = LoanFinance.isRepaymentLate(loan.datum(), atMillis);
        Rational debt = Rational.fromInt(remainingDebt);
        Rational collateral = Rational.fromInt(loan.collateralAmount());
        BigInteger equity = LoanFinance.redeemerEquity(liquidation, collateral, debt, principalFeed,
                collateralFeed);
        BigInteger fee = Rational.required(
                        loan.collateralAmount().multiply(bond.datum().liquidationFeePerMille()),
                        BigInteger.valueOf(1000))
                .floor();
        return LiquidationAssessment.buildable(bond, loan, "buildable liquidation",
                remainingDebt, equity, late, fee);
    }

    /** The same assessment with its numbers replaced, for the guards that must catch a stale one. */
    public static LiquidationAssessment withNumbers(LiquidationAssessment assessment,
                                                    BigInteger remainingDebt, BigInteger equity,
                                                    BigInteger liquidationFee) {
        return LiquidationAssessment.buildable(assessment.bond(), assessment.loan(), assessment.detail(),
                remainingDebt, equity, assessment.late(), liquidationFee);
    }

    /**
     * An <em>excluded</em> assessment that nonetheless carries the full set of numbers a buildable
     * one would.
     * <p>
     * {@link LiquidationAssessment#excluded} leaves them null, which means a test built on it proves
     * only that the builder trips over a null somewhere downstream — the veto could be deleted and
     * the test would still "pass" on a {@code NullPointerException}. With the numbers populated,
     * removing the veto lets the batch sail through every later guard and build, so the test fails
     * on its own refusal assertion: the veto is what is being tested, not the nulls.
     */
    public static LiquidationAssessment excludedButFullyNumbered(LiquidationAssessment buildable,
                                                                 LiquidationExclusion exclusion) {
        return new LiquidationAssessment(buildable.bond(), buildable.loan(), exclusion,
                "excluded, but with every number a buildable assessment would carry",
                buildable.remainingDebt(), buildable.equity(), buildable.late(),
                buildable.liquidationFee());
    }

    // ---- oracles -----------------------------------------------------------------------------

    /**
     * A Charli3-backed oracle entry. The reward address is derived from the withdrawal credential on
     * the app network, which is exactly what the builder re-derives and compares against.
     */
    public static OracleEntry charli3(AssetType token, AssetType oracleToken, String withdrawCredentialHash,
                                      OraclePriceFeed feed, TransactionInput referenceInput,
                                      TransactionInput referenceScript, TransactionInput provider) {
        return new OracleEntry(token, oracleToken, rewardAddress(withdrawCredentialHash),
                withdrawCredentialHash, referenceInput, referenceScript, List.of(), 0, feed,
                List.of(), provider);
    }

    // ---- suppliers ---------------------------------------------------------------------------

    /**
     * An in-memory {@link UtxoSupplier}. The whole point of this slice is that a transaction can be
     * assembled with no chain access at all, so the tests hand the builder its universe up front.
     */
    public static UtxoSupplier utxoSupplier(List<Utxo> utxos) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                if (page != null && page > 0) {
                    return List.of();
                }
                return utxos.stream().filter(utxo -> utxo.getAddress().equals(address)).toList();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return utxos.stream()
                        .filter(utxo -> utxo.getTxHash().equals(txHash) && utxo.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
    }

    /**
     * Conway-era protocol parameters. Only the fields fee calculation, min-ada and collateral
     * balancing actually read are set; the PlutusV3 cost model is the one bundled with
     * cardano-client-lib, so the script data hash a reference-script build produces is a real one.
     */
    public static ProtocolParamsSupplier protocolParams() {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        costModels.put("PlutusV3", Arrays.stream(CostModelUtil.PlutusV3CostModel.getCosts())
                .boxed().toList());

        ProtocolParams params = ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .maxTxSize(16384)
                .maxValSize("5000")
                .coinsPerUtxoSize("4310")
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .minFeeRefScriptCostPerByte(new BigDecimal("15"))
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .costModelsRaw(costModels)
                .build();
        return () -> params;
    }
}
