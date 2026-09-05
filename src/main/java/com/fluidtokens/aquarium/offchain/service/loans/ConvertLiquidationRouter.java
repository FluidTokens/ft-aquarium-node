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
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.cardanofoundation.conversions.CardanoConverters;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import java.util.LinkedHashMap;
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
    private final AppConfig.LiquidationConfiguration liquidationConfiguration;
    private final CardanoConverters converters;
    private final MinswapPoolResolver poolResolver;
    private final ConvertEconomics economics;
    private final ConvertTransactionBuilder builder;
    private final com.bloxbean.cardano.client.common.model.Network network;

    public ConvertLiquidationRouter(LoansContractRegistry registry,
                                    AppConfig.LoansConfiguration loansConfiguration,
                                    AppConfig.LiquidationConfiguration liquidationConfiguration,
                                    MinswapPoolResolver poolResolver,
                                    ConvertEconomics economics,
                                    ConvertTransactionBuilder builder,
                                    CardanoConverters converters,
                                    com.bloxbean.cardano.client.common.model.Network network) {
        this.registry = registry;
        this.loansConfiguration = loansConfiguration;
        this.liquidationConfiguration = liquidationConfiguration;
        this.poolResolver = poolResolver;
        this.economics = economics;
        this.builder = builder;
        this.converters = converters;
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
    /**
     * ⛔ <b>The configured reference scripts, as {@code scriptHash -> UTxO}. This was {@code Map.of()}
     * until 2026-09-05, and that literal was the entire reason a convert could not be submitted.</b>
     *
     * <p>Every other path supplies this correctly — {@code LiquidationExecutor}, the pay-in-advance
     * router, and {@code CompoundExecutor} all read the operator's coordinates. <b>Convert alone
     * threw them away</b>, so all six of its validators travelled inline: <b>20,270 bytes of script
     * against a 16,384 {@code max_tx_size}</b>, measured from the published mainnet scripts —
     * {@code loan} 2,547 · {@code loan-spend} 1,158 · {@code lender-manager} 968 ·
     * {@code lender-manager-spend} 1,158 · {@code loan-claim-action} 8,662 ·
     * {@code lm-liquidate-and-convert-action} 5,777. A convert could be planned, priced and refused,
     * but never built small enough to send.
     *
     * <p><b>Exactly the six {@code ConvertTransactionBuilder} can reference, and no more.</b> Its
     * {@code referencedScripts} considers that set; anything else here would still be added as a
     * reference input by {@code referenceInputs()} and charged the Conway per-byte reference-script
     * fee for a script no redeemer invokes. So {@code asset-manager}, {@code lm-liquidate-action} and
     * {@code lm-liquidate-and-pay-in-advance-action} are deliberately absent — they belong to the
     * other two routes.
     *
     * <p>⚠ <b>Keyed by the REGISTRY's hash for each named slot, not by a hash read off the chain.</b>
     * That is sound only because {@code LoansReferenceScriptVerifier} resolves every configured
     * coordinate at startup and <b>hard-fails on a mismatch</b> — so by the time this runs, the claim
     * "the key named {@code loan-spend} publishes the loan-spend script" is already proven. The
     * compound path reads the hash off chain instead because its coordinates are an unnamed list,
     * where a mislabelled entry would otherwise be inexpressible.
     *
     * <p>An unconfigured slot is simply absent: that validator travels inline, which is correct and
     * larger — never referenced-but-absent, which is {@code RequiredRedeemersMismatch} (CCL trap 13).
     */
    Map<String, TransactionInput> referenceScripts() {
        LiquidateTransactionBuilder.ReferenceScripts configured =
                liquidationConfiguration == null ? null : liquidationConfiguration.getReferenceScripts();
        if (configured == null) {
            return Map.of();
        }
        Map<String, TransactionInput> resolved = new LinkedHashMap<>();
        put(resolved, registry.getLoanPolicyId(), configured.loan());
        put(resolved, registry.getLoanSpendScriptHash(), configured.loanSpend());
        put(resolved, registry.getLenderManagerWithdrawScriptHash(), configured.lenderManager());
        put(resolved, registry.getLenderManagerSpendScriptHash(), configured.lenderManagerSpend());
        put(resolved, registry.getLoanClaimActionScriptHash(), configured.loanClaimAction());
        put(resolved, registry.getLmLiquidateAndConvertActionScriptHash(),
                configured.lmLiquidateAndConvertAction());
        return Map.copyOf(resolved);
    }

    /**
     * Slots for the requested millisecond window, clamped <em>inwards</em> — the same rule
     * {@code PayInAdvanceLiquidationRouter} and {@code LiquidateTransactionBuilder} apply, so the
     * interval the transaction claims is contained by the one the guards proved safe.
     */
    private long[] validitySlots(long validFromMillis, long validToMillis) {
        long slotFrom = converters.time().toSlot(utc(validFromMillis));
        if (millisOf(converters.slot().slotToTime(slotFrom)) < validFromMillis) {
            slotFrom += 1;
        }
        long slotTo = converters.time().toSlot(utc(validToMillis));
        if (millisOf(converters.slot().slotToTime(slotTo)) > validToMillis) {
            slotTo -= 1;
        }
        return new long[]{slotFrom, slotTo};
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** The feed for a non-ada leg, refused by name rather than defaulted when it is absent. */
    private static OraclePriceFeed feedOf(Map<String, OracleEntry> oracles, AssetType oracleToken) {
        OracleEntry entry = oracles.get(oracleToken.toUnit());
        if (entry == null) {
            throw new IllegalStateException("no oracle feed for " + oracleToken.toUnit()
                    + "; the loan's figures cannot be derived at the body's validFrom");
        }
        return entry.feed();
    }

    private static void put(Map<String, TransactionInput> into, String scriptHash,
                            TransactionInput coordinate) {
        if (scriptHash != null && !scriptHash.isBlank() && coordinate != null) {
            into.put(scriptHash, coordinate);
        }
    }

    /**
     * ⛔ <b>The order output's address is DICTATED by the validator, not chosen by us — and this
     * built an enterprise address unconditionally until 2026-09-05.</b>
     *
     * <p>{@code lm_liquidate_and_convert_action} at the deployed sha {@code bb4349c} requires, for a
     * non-CIP-113 pair:
     * <pre>
     * minswapOrderOutput.address == Address {
     *     payment_credential: Script(minswapOrderSpendScriptHash),
     *     stake_credential:   lenderStakeCredential,        // ← the LENDER's, from the bond datum
     * }
     * </pre>
     * ⚠ <b>An enterprise address has no stake part</b>, so it satisfies that equality only when the
     * bond's {@code lenderStakeCredential} is {@code None}. This method's own {@code @param} javadoc
     * on {@code ConvertTransactionBuilder.Request} has always said
     * {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)} — <b>the parameter
     * was documented correctly and constructed wrongly</b>, and the two never met.
     *
     * <p>⚑ <b>Sibling parity, again:</b> {@code LiquidateTransactionBuilder} already decodes this
     * exact {@code Option&lt;StakeCredential&gt;} (findings §7 D6) and this path did not use it. The
     * decode is duplicated here deliberately and minimally rather than refactoring the working plain
     * builder mid-diagnosis; <b>one shared decoder is the right end state and is recorded as a
     * follow-up.</b>
     *
     * <p>⚠ {@code Some(Pointer{..})} is refused rather than guessed, exactly as the sibling refuses
     * it: cardano-client-lib can build a pointer address, and putting guessed bytes on an output that
     * carries someone else's collateral is not a trade this builder makes.
     */
    private String orderAddress(LenderBond bond) {
        com.bloxbean.cardano.client.address.Credential payment =
                com.bloxbean.cardano.client.address.Credential.fromScript(
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(
                                loansConfiguration.getMinswapOrderSpendScriptHash()));
        com.bloxbean.cardano.client.address.Credential stake = lenderStake(bond);
        return stake == null
                ? com.bloxbean.cardano.client.address.AddressProvider
                        .getEntAddress(payment, network).getAddress()
                : com.bloxbean.cardano.client.address.AddressProvider
                        .getBaseAddress(payment, stake, network).getAddress();
    }

    /**
     * {@code Option<StakeCredential>} → a CCL credential, or {@code null} for {@code None}.
     * {@code Some(Inline(credential))} contributes the stake part; anything else is refused by name.
     */
    private static com.bloxbean.cardano.client.address.Credential lenderStake(LenderBond bond) {
        PlutusData data = bond.datum().lenderStakeCredential();
        if (!(data instanceof ConstrPlutusData option)) {
            throw new IllegalStateException("bond " + bond.loanId()
                    + ": lenderStakeCredential is not a constructor");
        }
        if (option.getAlternative() == 1) {
            return null;                                  // None ⇒ enterprise, and that is correct
        }
        if (option.getAlternative() != 0
                || !(firstField(option) instanceof ConstrPlutusData referenced)) {
            throw new IllegalStateException("bond " + bond.loanId()
                    + ": Option<StakeCredential> constructor " + option.getAlternative());
        }
        if (referenced.getAlternative() == 1) {
            throw new IllegalStateException("bond " + bond.loanId()
                    + " carries a POINTER stake credential; refusing to guess its bytes onto an "
                    + "output holding someone else's collateral");
        }
        if (referenced.getAlternative() != 0
                || !(firstField(referenced) instanceof ConstrPlutusData credential)) {
            throw new IllegalStateException("bond " + bond.loanId()
                    + ": StakeCredential constructor " + referenced.getAlternative());
        }
        if (!(firstField(credential) instanceof BytesPlutusData hash)) {
            throw new IllegalStateException("bond " + bond.loanId()
                    + ": credential hash is not a ByteArray");
        }
        // Credential is VerificationKey (0) or Script (1) — the same two the ledger has.
        return credential.getAlternative() == 0
                ? com.bloxbean.cardano.client.address.Credential.fromKey(hash.getValue())
                : com.bloxbean.cardano.client.address.Credential.fromScript(hash.getValue());
    }

    private static PlutusData firstField(ConstrPlutusData constr) {
        var list = constr.getData().getPlutusDataList();
        if (list.isEmpty()) {
            throw new IllegalStateException("expected a field, found an empty constructor");
        }
        return list.get(0);
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

        // ⛔ MILLISECONDS ARE NOT SLOTS, and until 2026-09-05 this router handed the caller's
        // millisecond window straight into a record whose components are `validFromSlot` /
        // `validToSlot`. Both are `long` and both are positional, so javac saw nothing and the
        // ledger saw a validity interval of SlotNo 1788596164000 — about 12,776x past the tip,
        // rejected as "beyond the foreseeable end of the current era". A convert has therefore
        // NEVER built, on any network, for any configuration.
        //
        // ⚠ Every sibling already did this and only this one did not: PayInAdvanceLiquidationRouter,
        // LiquidateTransactionBuilder and CompoundExecutor all call toSlot() at their boundary.
        // Sibling parity is what localises a defect like this — "what is this path omitting that the
        // working ones do" — not a search for something novel.
        long[] slots = validitySlots(validFromMillis, validToMillis);

        OracleEntry collateralOracle =
                oraclesByOracleTokenUnit.get(loan.collateral().oracleTokenAsset().toUnit());

        // ⛔ THE FIGURES ARE THE LOAN'S AT THE BODY'S OWN validFrom — NOT the assessment's.
        //
        // Until 2026-09-05 this passed `assessment.equity()` and `assessment.remainingDebt()`, the
        // numbers the SCANNER computed at assessment time, into both the order plan and the claim
        // redeemer — while the body's validity interval starts at a different instant. Interest
        // accrues per slot, so the two disagree, and `loan_claim_action` recomputes them itself:
        // the validator derives `swappableCollateralAmount = collateral − equity − liquidationFee`
        // and `minimum_receive = remainingDebt` from what the redeemer carries.
        //
        // ⚑ SIBLING PARITY, the fourth time on this path. LiquidateTransactionBuilder does exactly
        // this and then re-asserts it off the FINISHED body — five call sites of
        // assertRedeemerFiguresMatchTheBodysValidFrom. This builder had none.
        //
        // ⚠ It became REACHABLE only when the slot fix (5b65c72) gave the body a real validFrom to
        // disagree at: before that, evaluation died at PastHorizon long before any figure was
        // compared. Clearing the third wall is what made the fourth visible — which is the shape of
        // this whole path, and the reason "expect the next wall, not green" keeps being right.
        long figuresAtMillis = millisOf(converters.slot().slotToTime(slots[0]));
        BigInteger remainingDebt = LoanFinance.remainingDebt(loan, figuresAtMillis);
        // The principal leg: ada is the synthesised 1:1 feed (retrieve_oracle_data's
        // `expectedTokenPolicyId == ""` branch), any other principal needs its own feed.
        OraclePriceFeed principalFeed = loan.principalAsset().isAda()
                ? OraclePriceFeed.unit()
                : feedOf(oraclesByOracleTokenUnit, loan.principalOracleAsset());
        BigInteger equity = LoanFinance.redeemerEquity(
                (LiquidationMode.Liquidation) loan.liquidationMode(),
                Rational.fromInt(assessment.loan().collateralAmount()),
                Rational.fromInt(remainingDebt),
                principalFeed,
                collateralOracle == null ? null : collateralOracle.feed());

        // 2. Everything the validator dictates, computed against that pool.
        AssetType lenderBond = new AssetType(registry.getLenderBondPolicyId(), assessment.loan().loanId());
        ConvertOrderPlan plan = ConvertOrderPlan.plan(collateral, loan.principalAsset(),
                assessment.loan().collateralAmount(), equity, remainingDebt,
                assessment.bond().datum().liquidationFeePerMille().longValueExact(),
                assessment.bond().datum().shouldLiquidationConvertToPrincipal(),
                ((LiquidationMode.Liquidation) loan.liquidationMode()).equityInPrincipalCurrency(),
                pool.datum(), loansConfiguration.getMinswapPoolPolicyId(), lenderBond,
                assessment.bond().datum().lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(registry.getAssetManagerSpendScriptHash()),
                loanUtxo.getTxHash(), loanUtxo.getOutputIndex());

        ClaimData claim = new ClaimData((LiquidationMode.Liquidation) loan.liquidationMode(),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                assessment.bond().datum().lenderAuth(), equity,
                assessment.loan().loanId(), remainingDebt);

        // 3. BUILD, then price. The gate needs the fee this transaction actually pays.
        Transaction transaction = builder.build(new ConvertTransactionBuilder.Request(
                loanUtxo, bondUtxo, pool.utxo(), collateralOracle, configUtxo, lmConfigUtxo, walletUtxo,
                referenceScripts(), plan, claim, collateral, lenderBond, loan.repaymentReceipts(),
                orderAddress(assessment.bond()), changeAddress, slots[0], slots[1]));

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
