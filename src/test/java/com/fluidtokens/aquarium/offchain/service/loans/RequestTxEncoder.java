package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
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
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Encodes the datum and redeemer of the T-016 loan-origination <b>request mint</b> transaction
 * (TX A) as {@code PlutusData}, in the {@link LiquidationTxEncoder} discipline: hand-written,
 * constructor indices and field order asserted against the schema oracle
 * {@code loans-v4-alltypes.plutus.json} ({@link RequestTxEncoderSchemaTest}), and pinned against
 * transcribed golden hex ({@link RequestTxEncoderTest}) so a change that still satisfies the schema
 * pins is still caught.
 *
 * <h2>Why this lives in {@code src/test}</h2>
 * The node never originates a loan — it indexes them and liquidates them. Nothing in
 * {@code src/main} builds a {@code RequestDatum}, and this encoder deliberately stays in test scope
 * so nothing starts to.
 *
 * <h2>Consequence A — the request NFT asset name is 29 bytes, and the other three are 28</h2>
 * The request NFT minted by {@code request.request}'s {@code mint} handler is named
 * <b>{@code index-byte ‖ blake2b_224(serialise_data(redeemer.inputRef))}</b> — a
 * <b>1-byte index prefix</b> ({@code 0x00} for the single-token case) in front of the 28-byte hash,
 * because {@code check_mint} ({@code validators/request.ak:174-177}, deployed pin {@code bbe9c1a})
 * demands {@code bytearray.at(assetName, 0) == index} and
 * {@code bytearray.drop(assetName, 1) == inputRefHash}.
 * <p>
 * The other three NFTs of this epic — the loan NFT, the borrower bond and the lender bond — are
 * <b>28 bytes with no prefix</b>. They are {@code hash_output_ref(input.output_reference)} computed
 * in {@code check_lend} ({@code request.ak:271}), where {@code input} is the <em>request UTxO being
 * spent by TX B</em>. That is a <b>different {@code OutputReference}</b> from this slice's seed: two
 * hashes of two different output references, one prefixed and one not. Confusing them mints an NFT
 * the validator will not recognise and strands the collateral.
 *
 * <h2>Consequence C — no validity interval</h2>
 * {@code check_mint} reads no validity range, no signature and no withdrawal, so TX A is built with
 * none. See {@link RequestMintTransactionBuilder}.
 *
 * <h2>Consequence D — a Cancel runs three script purposes, and the burn still needs a spent seed</h2>
 * {@code request.request} has {@code mint} and {@code withdraw} handlers only ({@code
 * validators/request.ak:48-130}, {@code else(_) { fail }} at {@code :132-134}), so the {@code Cancel}
 * path is a <b>withdraw</b> purpose — never a spend one. The request UTxO itself sits at the request
 * {@code general_spend} wrapper, whose only check when an inline datum is present is
 * {@code list.any(self.withdrawals, ..)} matching {@code Script(withdrawScriptHash)} — the request
 * policy id ({@code general_spend.ak:31-41}). So a Cancel carries <b>three</b> redeemers:
 * <ol>
 *   <li><b>Spend</b> at {@code requestSpendScriptHash} — {@code general_spend}, any {@code Data} as
 *       redeemer (the handler signature is {@code _redeemer: Data});</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at the reward address of the request policy id —
 *       {@code request.request} with a {@link #requestWithdrawRedeemer};</li>
 *   <li><b>Mint</b> (burn, −1) under the request policy id — the same {@code request.request} script
 *       again, with a {@link #requestMintRedeemer}.</li>
 * </ol>
 * Purposes 2 and 3 are the same compiled script and travel as one witness copy.
 * <p>
 * The burn is not a free ride: {@code check_cancel} requires
 * {@code quantity_of(self.mint, requestPolicyId, requestId) == -1}, which invokes the policy, and
 * {@code check_mint}'s token accounting degenerates to {@code indexed_all([], _)} because it filters
 * the minted tokens to {@code quantity > 0} ({@code request.ak:164-168}) — but
 * {@code isInputRefSpent} sits <em>outside</em> that filter ({@code request.ak:161-162}) and is
 * conjoined at {@code :191-194}. <b>A burn therefore still has to name a genuinely spent
 * {@code inputRef}.</b> The Cancel builder names the request UTxO's own output reference, which is
 * spent by construction and so cannot drift from the body.
 *
 * <h2>Duplicated primitives</h2>
 * The private {@code constr}/{@code bool}/{@code bytes}/{@code bigInt}/{@code asset} helpers below
 * duplicate the ones inside {@link LoanFixtures}. <b>That duplication is deliberate and sanctioned
 * by the T-016 S2 slice contract</b>: widening {@code LoanFixtures}' visibility to share them would
 * put a heavily contended file into this slice's file allowlist for no gain.
 */
public final class RequestTxEncoder {

    private RequestTxEncoder() {
    }

    // ---- test-scope models ---------------------------------------------------------------------

    /**
     * {@code RequestDatum} from {@code lib/fluidtokens/types/request.ak} — constructor 0, 12 fields,
     * in the order pinned by {@link RequestTxEncoderSchemaTest}.
     *
     * @param permissionedConditionScriptHash hex; {@code constants.no_permissioned_condition} is the
     *                                        hex of ASCII {@code "NONE"}
     * @param extraData                       opaque {@code Data}; nothing on chain reads it
     */
    public record RequestDatum(String permissionedConditionScriptHash,
                               PlutusData extraData,
                               CommonData commonData,
                               AuthorizationMethod borrowerAuth,
                               Address borrowerAddress,
                               CollateralAsset collateral,
                               BigInteger minPrincipal,
                               BigInteger minPrincipalDivider,
                               BigInteger maxPrincipal,
                               boolean dynamicCollateralPrice,
                               BigInteger requestExpiration,
                               BigInteger requestExpirationPenalty) {
    }

    /**
     * {@code CommonData} from {@code lib/fluidtokens/types/pool.ak} — constructor 0, 12 fields.
     * <p>
     * <b>Not a re-ordering of {@code LoanDatum}.</b> {@code totalInstallments} sits at index 4 here
     * and at index 5 of {@code LoanDatum}, and the two types agree on no other position either — a
     * copy-paste of {@code LoanFixtures.encode(LoanDatum)}'s field order into this encoder would
     * transpose fields silently. {@link RequestTxEncoderSchemaTest} asserts that difference
     * deliberately.
     */
    public record CommonData(AssetType principalAsset,
                             AssetType principalOracleAsset,
                             BigInteger interestRate,
                             BigInteger installmentPeriod,
                             BigInteger totalInstallments,
                             BigInteger initialGracePeriod,
                             LiquidationMode liquidationMode,
                             RepaymentMode repaymentMode,
                             BigInteger repaymentTimeWindow,
                             BigInteger penaltyFeeForLateRepayment,
                             boolean repaymentReceipts,
                             String borrowerBondDestinationScriptHash) {
    }

    /**
     * Thrown for an address this encoder cannot represent as an Aiken {@code Address}: a pointer
     * address, or one with no payment credential at all (a reward address).
     * <p>
     * Named, and thrown rather than worked around, on purpose: cardano-client-lib's
     * {@code getDelegationCredential} happily hands back a {@link Credential} built from a pointer
     * address's <em>pointer bytes</em>, so a silent fallback here would encode a plausible-looking
     * {@code Inline(VerificationKey(..))} that is not the address at all.
     */
    public static final class UnrepresentableAddressException extends IllegalArgumentException {
        UnrepresentableAddressException(String message) {
            super(message);
        }
    }

    // ---- RequestDatum ---------------------------------------------------------------------------

    /**
     * {@code RequestDatum { permissionedConditionScriptHash, extraData, commonData, borrowerAuth,
     * borrowerAddress, collateral, minPrincipal, minPrincipalDivider, maxPrincipal,
     * dynamicCollateralPrice, requestExpiration, requestExpirationPenalty } } — constructor 0.
     */
    public static PlutusData requestDatum(RequestDatum d) {
        return constr(0,
                bytes(d.permissionedConditionScriptHash()),
                d.extraData(),
                commonData(d.commonData()),
                authorizationMethod(d.borrowerAuth()),
                address(d.borrowerAddress()),
                collateralAsset(d.collateral()),
                bigInt(d.minPrincipal()),
                bigInt(d.minPrincipalDivider()),
                bigInt(d.maxPrincipal()),
                bool(d.dynamicCollateralPrice()),
                bigInt(d.requestExpiration()),
                bigInt(d.requestExpirationPenalty()));
    }

    /**
     * {@code CommonData { principalAsset, principalOracleAsset, interestRate, installmentPeriod,
     * totalInstallments, initialGracePeriod, liquidationMode, repaymentMode, repaymentTimeWindow,
     * penaltyFeeForLateRepayment, repaymentReceipts, borrowerBondDestinationScriptHash } } —
     * constructor 0.
     */
    public static PlutusData commonData(CommonData d) {
        return constr(0,
                asset(d.principalAsset()),
                asset(d.principalOracleAsset()),
                bigInt(d.interestRate()),
                bigInt(d.installmentPeriod()),
                bigInt(d.totalInstallments()),
                bigInt(d.initialGracePeriod()),
                liquidationMode(d.liquidationMode()),
                repaymentMode(d.repaymentMode()),
                bigInt(d.repaymentTimeWindow()),
                bigInt(d.penaltyFeeForLateRepayment()),
                bool(d.repaymentReceipts()),
                bytes(d.borrowerBondDestinationScriptHash()));
    }

    // ---- RequestMintRedeemer ----------------------------------------------------------------------

    /**
     * {@code RequestMintRedeemer { configRefInputIndex, inputRef } } — constructor 0.
     * {@code inputRef} is the "seed": the UTxO the transaction must actually spend, which
     * {@code check_mint}'s {@code isInputRefSpent} looks for with
     * {@code find_input(self.inputs, redeemer.inputRef)}.
     */
    public static PlutusData requestMintRedeemer(long configRefInputIndex, TransactionInput inputRef) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), outputReference(inputRef));
    }

    // ---- RequestWithdrawRedeemer --------------------------------------------------------------------

    /**
     * {@code RequestWithdrawRedeemer { configRefInputIndex, actionsForEachInput } } — constructor 0.
     * <p>
     * {@code actionsForEachInput} is indexed by position within the <b>filtered</b> request-input
     * list that {@code get_inputs_from_smart_credential} produces ({@code request.ak:68-74} then
     * {@code :77-82}) — <em>not</em> by absolute input index. Too few actions is an <b>abort</b>,
     * not a {@code False}: {@code utils.indexed_all} ({@code utils.ak:203-209}) calls the predicate
     * at every index of that filtered list and the predicate's first move is
     * {@code safe_list_at(redeemer.actionsForEachInput, index)} ({@code request.ak:82}), which is
     * {@code do_list_at} → {@code builtin.head_list} on an emptied list ({@code utils.ak:113-124}).
     * The upstream comment at {@code request.ak:76} claims the opposite — "we DO NOT need to ensure
     * that the number of actions is equal to the number of inputs" — and it holds only in the
     * direction of <em>more</em> actions than inputs. So the list length must be at least the count
     * of inputs at the request spend credential, and this epic keeps the two equal.
     * <p>
     * The {@code configRefInputIndex} here is a <b>second, independent</b> index from the one the
     * mint redeemer of the same transaction carries: both the withdraw handler ({@code
     * request.ak:54-59}) and {@code check_mint} ({@code :144-149}) resolve the config themselves.
     */
    public static PlutusData requestWithdrawRedeemer(long configRefInputIndex,
                                                     List<PlutusData> actionsForEachInput) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                ListPlutusData.of(actionsForEachInput.toArray(PlutusData[]::new)));
    }

    /**
     * {@code RequestAction.Cancel { requestId } } — constructor <b>0</b>. {@code requestId} is the
     * 29-byte request NFT asset name; {@code check_cancel} ({@code request.ak:197-217}) uses it for
     * both of its {@code quantity_of} conjuncts — one against the spent input's value, one against
     * the transaction's mint field.
     */
    public static PlutusData cancelAction(String requestIdHex) {
        return constr(0, bytes(requestIdHex));
    }

    /**
     * {@code RequestAction.CancelAfterExpiration { requestId } } — constructor <b>1</b>.
     * <p>
     * <b>This exists solely so the discrimination sentinel in {@link RequestTxEncoderTest} can tell
     * the two constructors apart. It is not a supported path in this epic.</b>
     * {@code CancelAfterExpiration} is out of scope: {@code check_cancel_after_expiration}
     * ({@code request.ak:219-245}) needs {@code validFrom > requestExpiration}, and this repo's
     * fixture pins {@code requestExpiration} at the year 2100. {@code Cancel} and
     * {@code CancelAfterExpiration} carry <em>identical field shapes</em> — one {@code ByteArray}
     * named {@code requestId} apiece — so the constructor tag is the only thing that distinguishes
     * them and a swap would otherwise be invisible in both the schema pins and the goldens. Nothing
     * but the sentinel calls this.
     */
    public static PlutusData cancelAfterExpirationAction(String requestIdHex) {
        return constr(1, bytes(requestIdHex));
    }

    /**
     * {@code OutputReference { transaction_id: ByteArray, output_index: Int } } — constructor 0.
     * {@code transaction_id} is a <b>flat</b> {@code ByteArray} (PlutusV3), not nested in a wrapper
     * constructor; {@link RequestTxEncoderSchemaTest} pins that against the blueprint's {@code $ref}.
     * <p>
     * The bytes this produces are the pre-image {@code utils.hash_output_ref} hashes
     * ({@code lib/fluidtokens/utils.ak:32-34}: {@code serialise_data |> blake2b_224}), so
     * {@link RequestFixtures#requestAssetName} derives the NFT name straight from here.
     */
    public static PlutusData outputReference(TransactionInput input) {
        String transactionIdHex = input.getTransactionId();
        if (transactionIdHex == null || transactionIdHex.length() != 64) {
            throw new IllegalArgumentException(
                    "transactionId must be exactly 64 hex chars (32 bytes), was: " + transactionIdHex);
        }
        if (input.getIndex() < 0) {
            throw new IllegalArgumentException("outputIndex must be >= 0, was: " + input.getIndex());
        }
        return constr(0, bytes(transactionIdHex), BigIntPlutusData.of(input.getIndex()));
    }

    // ---- Address --------------------------------------------------------------------------------

    /**
     * {@code cardano/address/Address { payment_credential, stake_credential } } — constructor 0.
     * <p>
     * Derived from a real bech32 {@link Address} rather than hand-assembled from two hashes, so the
     * {@code borrowerAddress} written into the datum cannot drift from the address the Lend
     * transaction actually pays the borrower bond to ({@code request.ak:457-464} compares
     * {@code outputWithBorrowerToken.address} against exactly this value).
     */
    public static PlutusData address(Address address) {
        if (address.getAddressType() == AddressType.Ptr) {
            throw new UnrepresentableAddressException(
                    "pointer addresses have no Inline stake credential this encoder can represent: "
                            + address.getAddress());
        }
        Credential payment = address.getPaymentCredential().orElseThrow(() ->
                new UnrepresentableAddressException(
                        "address has no payment credential: " + address.getAddress()));
        Optional<Credential> delegation = address.getDelegationCredential();
        return constr(0, credential(payment), optionalStakeCredential(delegation));
    }

    /** {@code Credential} — VerificationKey is constructor 0, Script is constructor 1. */
    private static PlutusData credential(Credential credential) {
        int alternative = credential.getType() == CredentialType.Script ? 1 : 0;
        return constr(alternative, BytesPlutusData.of(credential.getBytes()));
    }

    /**
     * {@code Option<StakeCredential>} — {@code Some} is constructor 0, {@code None} is 1 — wrapping
     * {@code StakeCredential.Inline}, which is constructor 0 ({@code Pointer} is 1 and is refused by
     * {@link #address}).
     */
    private static PlutusData optionalStakeCredential(Optional<Credential> delegation) {
        return delegation
                .<PlutusData>map(credential -> constr(0, constr(0, credential(credential))))
                .orElseGet(() -> constr(1));
    }

    // ---- CollateralAsset -------------------------------------------------------------------------

    /** {@code CollateralAsset { policyId, maybeAssetName, oracleTokenAsset } } — constructor 0. */
    public static PlutusData collateralAsset(CollateralAsset collateral) {
        return constr(0,
                bytes(collateral.policyId()),
                collateral.assetName()
                        .<PlutusData>map(name -> constr(0, bytes(name)))
                        .orElseGet(() -> constr(1)),
                asset(collateral.oracleTokenAsset()));
    }

    // ---- shared sub-encoders ---------------------------------------------------------------------

    /** {@code LiquidationMode} — constructor indices 0/1/2. */
    public static PlutusData liquidationMode(LiquidationMode mode) {
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

    /**
     * {@code RepaymentMode} — constructor indices 0/1/2 in order InterestOnRemainingPrincipal /
     * PrincipalAndInterestOnInstallments / PerpetualLoan.
     */
    public static PlutusData repaymentMode(RepaymentMode mode) {
        return switch (mode) {
            case RepaymentMode.InterestOnRemainingPrincipal m -> constr(0, bigInt(m.maxPossibleRecasts()));
            case RepaymentMode.PrincipalAndInterestOnInstallments ignored -> constr(1);
            case RepaymentMode.PerpetualLoan m -> constr(2,
                    bigInt(m.apyIncreaseLinearCoefficient()),
                    bigInt(m.maxPossibleRecasts()));
        };
    }

    /**
     * {@code AuthorizationMethod} — constructor indices 0..3 in order CardanoSignature /
     * CardanoSpendScript / CardanoWithdrawScript / CardanoMintScript, all {@code { hash: ByteArray } }.
     */
    public static PlutusData authorizationMethod(AuthorizationMethod auth) {
        return switch (auth) {
            case AuthorizationMethod.CardanoSignature a -> constr(0, bytes(a.hash()));
            case AuthorizationMethod.CardanoSpendScript a -> constr(1, bytes(a.hash()));
            case AuthorizationMethod.CardanoWithdrawScript a -> constr(2, bytes(a.hash()));
            case AuthorizationMethod.CardanoMintScript a -> constr(3, bytes(a.hash()));
        };
    }

    /** {@code Asset { policyId, assetName } } — constructor 0, policy id first; ada is two empty ByteArrays. */
    public static PlutusData asset(AssetType token) {
        return constr(0,
                BytesPlutusData.of(token.getPlutusDataPolicyId()),
                BytesPlutusData.of(token.getPlutusDataAssetName()));
    }

    /** The Aiken unit value {@code Void}/{@code ()} — {@code Constr 0 []}. */
    public static PlutusData unit() {
        return constr(0);
    }

    // ---- primitives --------------------------------------------------------------------------------
    //
    // Deliberately duplicated from LoanFixtures rather than shared — see the class javadoc.

    /** Aiken {@code Bool}: Constr 0 = False, Constr 1 = True. */
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
}
