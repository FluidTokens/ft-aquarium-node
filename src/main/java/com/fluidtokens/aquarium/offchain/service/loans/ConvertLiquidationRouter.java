package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.Map;

/**
 * Routes one convert-eligible candidate to {@link ConvertTransactionBuilder}, or refuses it cleanly.
 *
 * <h2>Where this sits</h2>
 * The lender's bond flag decides the CLASS of liquidation a loan permits; the operator's market
 * decides the MECHANISM within it (findings §27). So the executor asks {@link MarketGate#actionFor}
 * and sends {@code CONVERT} here and {@code ANTICIPATE} to {@link PayInAdvanceLiquidationRouter}.
 * <b>An unlisted market converts</b> — Giovanni's ruling, and the safe default because this path
 * fronts no capital and holds nothing.
 *
 * <h2>⚠ Refusals are named, and each says which KIND of thing went wrong</h2>
 * "We will not", "we cannot yet" and "the chain says no" are three different statements, and an
 * operator reading a decision log needs to tell them apart. They get separate exceptions here for the
 * same reason {@code MarketGateRefusedException} is separate from {@code PayInAdvanceNotModelled}.
 */
@Slf4j
public class ConvertLiquidationRouter {

    /** No Minswap pool exists for this pair, so a convert is impossible rather than unprofitable. */
    public static final class NoPoolException extends RuntimeException {
        public NoPoolException(String message) {
            super(message);
        }
    }

    /** The economics gate refused: the fee this convert earns does not clear the operator's floor. */
    public static final class UnprofitableException extends RuntimeException {
        private final transient ConvertAssessment assessment;

        UnprofitableException(ConvertAssessment assessment, String message) {
            super(message);
            this.assessment = assessment;
        }

        public ConvertAssessment assessment() {
            return assessment;
        }
    }

    private final LoansContractRegistry registry;
    private final AppConfig.LoansConfiguration loansConfiguration;
    private final MinswapPoolResolver poolResolver;
    private final ConvertEconomics economics;
    private final ConvertTransactionBuilder builder;
    private final com.bloxbean.cardano.client.common.model.Network network;

    public ConvertLiquidationRouter(LoansContractRegistry registry,
                                    AppConfig.LoansConfiguration loansConfiguration,
                                    MinswapPoolResolver poolResolver,
                                    ConvertEconomics economics,
                                    ConvertTransactionBuilder builder,
                                    com.bloxbean.cardano.client.common.model.Network network) {
        this.registry = registry;
        this.loansConfiguration = loansConfiguration;
        this.poolResolver = poolResolver;
        this.economics = economics;
        this.builder = builder;
        this.network = network;
    }

    /**
     * Builds the convert liquidation for one already-resolved candidate.
     *
     * <p>⛔ <b>The order of operations is not arbitrary.</b> The pool is resolved first because
     * without one the candidate is impossible and nothing else is worth computing; the transaction is
     * then BUILT before the economics gate runs, because <b>the gate needs the transaction's own
     * fee</b> — the same ordering the compound executor uses, and for the same reason. A gate that ran
     * first would be pricing a guess.
     */
    /**
     * {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)} — the address the
     * convert validator checks the order output against. ⚠ Only the non-CIP-113 branch is built, which
     * is the one a pool at Minswap's own credential takes.
     */
    private String orderAddress() {
        return com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromScript(
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(
                                loansConfiguration.getMinswapOrderSpendScriptHash())),
                network).getAddress();
    }

    public Transaction buildConvertLiquidation(LiquidationAssessment assessment,
                                               Utxo loanUtxo,
                                               Utxo bondUtxo,
                                               Utxo configUtxo,
                                               Utxo lmConfigUtxo,
                                               Utxo walletUtxo,
                                               Map<String, OracleEntry> oraclesByOracleTokenUnit,
                                               String changeAddress,
                                               long validFromMillis,
                                               long validToMillis) {
        LoanDatum loan = assessment.loan().datum();
        AssetType collateral = loan.collateral().assetType();

        // 1. The pool, by NFT at run time. Either order — the datum then states the ordering.
        MinswapPoolResolver.ResolvedPool pool = poolResolver
                .resolveEitherOrder(collateral, loan.principalAsset())
                .orElseThrow(() -> new NoPoolException(
                        "no Minswap pool for " + collateral.toUnit() + "/" + loan.principalAsset().toUnit()
                                + "; convert is impossible for this loan, not merely unprofitable — "
                                + "set this market to action: ANTICIPATE if it should be liquidated"));

        // 2. Everything the validator dictates, computed against that pool.
        AssetType lenderBond = new AssetType(registry.getLenderBondPolicyId(), assessment.loan().loanId());
        ConvertOrderPlan plan = ConvertOrderPlan.plan(collateral, loan.principalAsset(),
                assessment.loan().collateralAmount(), assessment.equity(), assessment.remainingDebt(),
                assessment.bond().datum().liquidationFeePerMille().longValueExact(),
                assessment.bond().datum().shouldLiquidationConvertToPrincipal(),
                ((LiquidationMode.Liquidation) loan.liquidationMode()).equityInPrincipalCurrency(),
                pool.datum(), loansConfiguration.getMinswapPoolPolicyId(), lenderBond,
                assessment.bond().datum().lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(registry.getAssetManagerSpendScriptHash()),
                loanUtxo.getTxHash(), loanUtxo.getOutputIndex());

        OracleEntry collateralOracle =
                oraclesByOracleTokenUnit.get(loan.collateral().oracleTokenAsset().toUnit());

        ClaimData claim = new ClaimData((LiquidationMode.Liquidation) loan.liquidationMode(),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                assessment.bond().datum().lenderAuth(), assessment.equity(),
                assessment.loan().loanId(), assessment.remainingDebt());

        // 3. BUILD, then price. The gate needs the fee this transaction actually pays.
        Transaction transaction = builder.build(new ConvertTransactionBuilder.Request(
                loanUtxo, bondUtxo, pool.utxo(), collateralOracle, configUtxo, lmConfigUtxo, walletUtxo,
                Map.of(), plan, claim, collateral, lenderBond, loan.repaymentReceipts(),
                orderAddress(), changeAddress, validFromMillis, validToMillis));

        BigInteger txFee = transaction.getBody().getFee();
        ConvertAssessment verdict = economics.assess(
                assessment.bond().datum().shouldLiquidationConvertToPrincipal(),
                assessment.loan().collateralAmount(),
                assessment.bond().datum().liquidationFeePerMille().longValueExact(),
                collateral.isAda(),
                collateralOracle == null ? null : collateralOracle.feed(),
                txFee);

        if (!verdict.approved()) {
            throw new UnprofitableException(verdict,
                    ("convert refused as %s: fee %s of collateral is worth %s lovelace, outlay %s "
                            + "(measured %s, dex floor %s), net %s against a floor of %s")
                            .formatted(verdict.exclusion(), verdict.liquidationFee(),
                                    verdict.feeValueLovelace(), verdict.outlay(),
                                    verdict.measuredOutlay(), verdict.dexCostFloor(), verdict.net(),
                                    verdict.floor()));
        }

        log.info("CONVERT built for {}: swapping {} of {} for at least {}, fee {} to the bot, net {} "
                        + "lovelace over an outlay of {}",
                assessment.loan().utxoRef(), plan.swappableCollateralAmount(), collateral.toUnit(),
                plan.minimumReceive(), verdict.liquidationFee(), verdict.net(), verdict.outlay());
        return transaction;
    }
}
