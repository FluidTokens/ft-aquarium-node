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
import java.util.Set;

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
    // FOURTH preview deployment (e0b818e), config NFTs minted in tx 8dd38e97…091c at outputs 0 and 1
    // — the deployment application.yaml's preview profile is pinned to and the running pod uses.
    //
    // ⛔ RE-POINTED 2026-09-04 FROM THE THIRD DEPLOYMENT, on Giovanni's ruling. These must stay in
    // step with the two recorded config datums this class serves below: the validators compare the
    // hashes they derive from these policy ids against the ones the datum publishes, so a mismatched
    // pair makes every dry-eval fixture unsatisfiable — and that is exactly what the third-deployment
    // pairing had become. The vendored loans-v4.plutus.json derives THIS deployment
    // (ShippedRegistryMatchesPinnedConfigTest), so blueprint and fixtures are now one build.
    //
    // ⚠ The third-deployment datums stay on disk under src/test/resources/loans-v4 and are still
    // used, deliberately: LoansConfigVerifierTest exercises the verifier on a matched third/third
    // pair, and ShippedRegistryMatchesPinnedConfigTest pins that a third-deployment fixture must NOT
    // verify against the shipped blueprint. Do not delete them.

    public static final String CONFIG_POLICY_ID = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    public static final String LM_CONFIG_POLICY_ID = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    public static final String CONFIG_ASSET_NAME = "706172616d6574657273";

    public static final Network NETWORK = Networks.preview();

    /** {@code constants.no_oracle_token_asset} — the ASCII "NONE" sentinel, in hex. */
    public static final AssetType NO_ORACLE = new AssetType("4e4f4e45", "4e4f4e45");

    /**
     * {@code smartTokensSpendScriptHash}, read from the live preview {@code ConfigDatum} (index 0) —
     * the blueprint ships no {@code smart_tokens} validator, so it cannot be derived. Supplying it is
     * what unlocks the pool-manager branch of {@link LoansContractRegistry}'s derivation
     * ({@code poolManagerPolicyId}, {@code poolManagerSpendScriptHash},
     * {@code pmCancelPoolManagerScriptHash}), without which T-024's PoolManager mint and burn cannot be
     * expressed at all.
     * <p>
     * The same constant appears in {@code application.yaml} and in {@code LoansContractDerivationTest},
     * which pins the two hashes it unlocks against the live {@code ConfigDatum}. Passing it adds
     * derivations and moves no existing hash.
     */
    public static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final LoansContractRegistry REGISTRY = new LoansContractRegistry(
            CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);

    /**
     * <b>A registry built from the coordinates {@code application.yaml} ACTUALLY SHIPS for preview —
     * read from the file, never typed here.</b>
     *
     * <p>⚠ <b>Since 2026-09-04 this agrees with {@link #registry()}</b>, and that agreement is now an
     * asserted invariant rather than a coincidence ({@code ShippedPreviewRegistryTest}). It did not
     * always: {@code registry()} was pinned to the THIRD deployment while this parsed the FOURTH, and
     * re-pointing it was an open decision Giovanni ruled on. <b>The two methods still exist for
     * different reasons</b> — this one is a GENERATOR that cannot disagree with what ships, that one
     * is a constant. Keep both: if a fifth deployment lands, this moves by itself and the assertion
     * that they agree is what makes the constant's staleness fail loudly instead of silently.
     *
     * <h2>Why it parses the file instead of holding constants</h2>
     * The on-chain loan factory silently targeted the third deployment after the 2026-08-25 redeploy,
     * so a loan it created would have landed at credentials {@code TankUtxoStorage} does not index —
     * <b>the bot would have reported {@code 0 live bonds} and the natural readings would have been
     * "the experiment failed" or "the indexer is broken".</b> A constant typed here would drift the
     * same way at the next redeploy. <b>A value read from the shipped config cannot disagree with what
     * ships</b> — the generator, not the reminder.
     */
    public static LoansContractRegistry shippedPreviewRegistry() {
        String yaml;
        try (InputStream is = LoanFixtures.class.getResourceAsStream("/application.yaml")) {
            if (is == null) {
                throw new IllegalStateException("application.yaml is not on the test classpath");
            }
            yaml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read application.yaml", e);
        }
        // The preview profile is the LAST document; take everything after its on-profile marker so a
        // mainnet value can never be picked up by accident.
        int preview = yaml.indexOf("on-profile: preview");
        if (preview < 0) {
            throw new IllegalStateException("no preview profile in application.yaml");
        }
        String section = yaml.substring(preview);
        return new LoansContractRegistry(
                yamlValue(section, "policy-id", 1),
                yamlValue(section, "policy-id", 2),
                "706172616d6574657273",   // loans.config.asset-name default, AppConfig:102
                yamlValue(section, "smart-tokens-spend-script-hash", 1));
    }

    /** The {@code n}-th occurrence of {@code key:} in {@code section}, hex value only. */
    private static String yamlValue(String section, String key, int occurrence) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*" + key + ":\\s*([0-9a-f]{40,})\\s*$").matcher(section);
        for (int i = 0; i < occurrence; i++) {
            if (!m.find()) {
                throw new IllegalStateException(
                        "application.yaml preview section has fewer than " + occurrence
                                + " '" + key + "' values");
            }
        }
        return m.group(1);
    }

    public static LoansContractRegistry registry() {
        return REGISTRY;
    }

    // ---- the THIRD deployment, kept on purpose ------------------------------------------------

    private static final String THIRD_CONFIG_POLICY_ID =
            "c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9";
    private static final String THIRD_LM_CONFIG_POLICY_ID =
            "de1b8b40536f96c1084d73f838ebac6b228d891902d6234afc731484";

    private static final LoansContractRegistry THIRD_DEPLOYMENT_REGISTRY = new LoansContractRegistry(
            THIRD_CONFIG_POLICY_ID, THIRD_LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);

    /**
     * ⛔ <b>The THIRD preview deployment &mdash; NOT a leftover, and not a candidate for the next
     * clean-up.</b>
     *
     * <h2>Why one family of rigs cannot move to the fourth deployment</h2>
     * The 2026-09-04 re-point moved {@link #registry()} to the FOURTH deployment so that fixtures and
     * the vendored blueprint come from one build. <b>The pool-origination rigs cannot follow, and the
     * reason is a fact about the chain rather than about this code.</b>
     *
     * <p>{@link PoolFixtures#PUBLISHED_REFERENCE_SCRIPTS} is a record of <b>reference-script UTxOs
     * FluidTokens actually published on preview</b>, keyed by the script hash each one publishes.
     * Those publications exist for the <b>third</b> deployment only: every hash is parameterised by the
     * config policy id, so the fourth deployment moved them all, and nothing was republished
     * (see {@code application.yaml}'s preview block, where every liquidation coordinate is blank for
     * exactly this reason). <b>Re-keying that map to fourth-deployment hashes with invented
     * coordinates would not fix anything — it would delete the map's meaning</b>, and with it the
     * {@code LoanFactory} gate that refuses to create a PoolManager-bearing pool whose cancel could
     * never be submitted.
     *
     * <p>⇒ <b>The split is by what a rig REPLAYS, which is the only honest boundary.</b> A rig that
     * replays published third-deployment reference scripts stays here; a rig that derives from the
     * shipped blueprint uses {@link #registry()}. Both are internally consistent, and neither is
     * pretending to be the other.
     *
     * <p>⚠ These rigs prove the same thing they proved before &mdash; the pool validators' shape,
     * arbitrated by the real compiled scripts. What they do <b>not</b> prove is anything about the
     * deployment the node is pinned to, which is what {@link #registry()} is now for.
     */
    public static LoansContractRegistry thirdDeploymentRegistry() {
        return THIRD_DEPLOYMENT_REGISTRY;
    }

    /** The third deployment's main config reference input, paired with {@link #thirdDeploymentRegistry()}. */
    public static Utxo thirdDeploymentConfigUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, THIRD_CONFIG_POLICY_ID,
                fixture("preview-config-datum.hex"));
    }

    /** The third deployment's LenderManager config reference input. */
    public static Utxo thirdDeploymentLmConfigUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, THIRD_LM_CONFIG_POLICY_ID,
                fixture("preview-lm-config-datum.hex"));
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

    /**
     * A script payment credential with a key-hash stake part &mdash; the shape a real loan or lender-bond
     * UTxO has, because {@code LenderManagerDatum.lenderStakeCredential} puts the LENDER's stake
     * credential on every UTxO the protocol creates for them (findings §5).
     *
     * <p>⛔ <b>Derived, never pinned.</b> A rig that hard-codes such an address survives a redeploy
     * looking correct and then fails as {@code RequiredRedeemersMismatch}, because the inputs sit at
     * the old script while the redeemers name the new one &mdash; measured on exactly that during the
     * 2026-09-04 re-point. The payment half must come from the registry so it moves with it.
     */
    public static String baseScriptAddress(String scriptHash, String stakeKeyHash) {
        return AddressProvider.getBaseAddress(Credential.fromScript(scriptHash),
                Credential.fromKey(stakeKeyHash), NETWORK).getAddress();
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

    // ---- the placeholder constants, named so the on-chain path can refuse them ----------------

    /**
     * The hand-written authorisation hash every no-auth {@link #bondDatum} overload stamps —
     * <b>28 bytes of arithmetic ramp</b>: 11, 18, 25, 32 … 200, step 7. It is not a key hash and it is
     * not a script hash; <b>no private key and no script exists for it</b>, so nothing on earth can
     * satisfy an {@code AuthorizationMethod} that names it.
     *
     * <h2>Why it now has a name</h2>
     * It reached chain. A pool originated through {@code LoanFactory} carried a lender bond whose
     * {@code lenderAuth} was this constant, and {@code lm_withdraw_bonds_action.ak} demands exactly that
     * authorisation to release the bond — so the bond, the ~3.17 ADA and 9,000,000 tFLDT behind it, and
     * the asset-manager vault the bond NFT gates ({@code asset_manager.ak} wants the bond as a
     * transaction <em>input</em>) are unrecoverable at any price. {@code PoolDatum.lenderBondInlineDatumHash}
     * pins the bond datum by hash at pool creation, so pool creation is the only moment the bond's
     * authorisation can ever be chosen and there is no later correction.
     * <p>
     * Naming it is what lets {@code LoanFactory}'s {@code FIXTURE_ORIGIN_GATE} refuse any datum whose
     * serialised bytes contain it, instead of the on-chain path being defended by nothing but the hope
     * that a test constant stays in tests.
     *
     * <h2>It stays here, and it stays in use</h2>
     * Every fixture bond in the offline liquidation suites carries it, and that is <em>correct</em> — but
     * for a narrower reason than this javadoc used to give. It claimed "none of the liquidation validators
     * reads {@code lenderAuth} at all". <b>That is false, and the correction is recorded here rather than
     * quietly deleted, because the false version is the one a reader would find plausible.</b> Only the
     * parenthetical survives: {@code lm_liquidate_action.ak:149} indeed reads no {@code lenderAuth}, it
     * requires {@code equals_data(lenderBondInput.output, lenderBondOutput)}. But
     * {@code lm_liquidate_and_convert_action.ak} <em>does</em> read it — destructured from the bond's inline
     * datum at {@code :145-152} and written into the Minswap order as
     * {@code canceller: to_order_auth_method(lenderAuth)} at {@code :249}, i.e. it decides who may cancel or
     * refund a stuck conversion order.
     * <p>
     * So the real reason these fixtures are safe is <b>that they are never submitted</b>, not that nothing
     * reads the field. On the convert path this constant would be copied into a real order's
     * {@code canceller}, and no key exists for it — the order would be uncancellable by anyone. Nothing is
     * broken today because we do not build that path; if we ever do, these fixtures must not reach it. {@code LiquidationTxEncoderTest} also uses the same 28 bytes as a synthetic
     * <em>policy id</em> — likewise legitimate test scope. The defect was never the constant; it was the
     * committable path reaching for it.
     */
    public static final String PLACEHOLDER_AUTH_HASH =
            "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8";

    /**
     * Every hand-written placeholder in this class that must never appear in a datum bound for chain,
     * as lowercase hex. {@code LoanFactory}'s {@code FIXTURE_ORIGIN_GATE} refuses a datum whose
     * serialised bytes contain any of them, in any field — so a future placeholder is defended the
     * moment it is added here, without the gate needing to know which field it might land in.
     *
     * <h2>What this set is not</h2>
     * It is not "every synthetic value in test scope". {@link #botAddress()}'s {@code 11…} / {@code 22…}
     * key hashes are deliberately absent: those arrive at the factory through the caller's
     * {@code Recipe}, as the caller's declared identity, and the offline rig legitimately declares a
     * synthetic lender because it never submits. The factory cannot adjudicate whether a caller holds a
     * key — that is what {@code LoanFactoryOnChainRunnerTest}'s mnemonic-derives-wallet-B check is for.
     * <p>
     * Nor is it a guarantee that the factory never <em>substitutes</em> a constant of its own for a
     * caller-supplied value — an earlier revision of this javadoc claimed exactly that, and it is not
     * true. What the set enforces is narrower and purely mechanical: <b>none of the values named here
     * may appear in the serialised bytes of a datum bound for an output.</b> A substitution by a
     * constant that is <em>not</em> named here goes straight through, and one such substitution is
     * live and known: {@code PoolFixtures.poolDatum} writes {@code List.of(PoolFixtures.COLLATERAL)}
     * as the pool's {@code collateralOptions} whatever {@code Recipe.collateralAsset()} says, and
     * {@code COLLATERAL} is deliberately not in this set (it is a real preview asset with a real
     * oracle, so the offline rig needs it to evaluate). Adding a constant here is what brings it under
     * the gate; being a constant is not enough.
     */
    public static final Set<String> PLACEHOLDER_CONSTANTS = Set.of(PLACEHOLDER_AUTH_HASH);

    // ---- datums ------------------------------------------------------------------------------

    /**
     * A late, installment-repaid loan. {@code PrincipalAndInterestOnInstallments} makes the remaining
     * debt time-independent, which keeps most fixtures' arithmetic easy to read.
     * <p>
     * <b>No longer a requirement, as of T-021.</b> This javadoc used to say time-independence was
     * "what lets a fixture satisfy V4's demand that the number be the same at both ends of the
     * validity window". V4 no longer demands that: the validator derives the debt from
     * {@code validFrom - datum.lendDate} alone ({@code loan_claim_action.ak:212-222} at
     * {@code ff005fb}), so a drifting perpetual debt is buildable. See
     * {@code LiquidateTransactionBuilder.assertHealthReproducesAndIsLiquidatableOverWindow}.
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
        return bondDatum(new AuthorizationMethod.CardanoSignature(PLACEHOLDER_AUTH_HASH),
                liquidationFeePerMille, stakeCredential, principalAsset, poolIdHex);
    }

    /**
     * As above, with the {@code lenderAuth} supplied by the caller — the overload anything that builds
     * a <b>committable</b> bond must use, because every other one stamps {@link #PLACEHOLDER_AUTH_HASH}.
     */
    public static LenderManagerDatum bondDatum(AuthorizationMethod lenderAuth,
                                               BigInteger liquidationFeePerMille,
                                               PlutusData stakeCredential,
                                               AssetType principalAsset,
                                               String poolIdHex) {
        return new LenderManagerDatum(
                lenderAuth,
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
     * ({@code src/test/resources/loans-v4/fourth-deployment-config-datum.hex} since the 2026-09-04
     * re-point — the live datum of the deployment the shipped blueprint actually derives).
     */
    public static Utxo configUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, CONFIG_POLICY_ID, fixture("fourth-deployment-config-datum.hex"));
    }

    /** The LenderManager config reference input, carrying the real preview {@code LMConfigDatum}. */
    public static Utxo lmConfigUtxo(String txHash, int outputIndex) {
        return configUtxo(txHash, outputIndex, LM_CONFIG_POLICY_ID, fixture("fourth-deployment-lm-config-datum.hex"));
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
