package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Derives the FluidTokens Lending v4 script hashes at startup from the two one-shot
 * config NFT policy ids, by applying parameters to the <em>unapplied</em>
 * {@code ft-cardano-loans-v4} blueprint ({@code loans-v4.plutus.json}).
 * <p>
 * Same approach as {@link ContractRegistry}: the committed {@code compiledCode} is
 * parameterised, never recompiled, so the aiken compiler version is irrelevant.
 * <p>
 * {@code LoansContractDerivationTest} asserts every value produced here against the
 * hashes published in the live preview config datums. If that test is green, no v4
 * address is ever hardcoded.
 *
 * <h2>Three rules that are easy to get wrong</h2>
 * <ol>
 *   <li><b>UTxOs never sit at the logic validator.</b> {@code loan} has only
 *       {@code mint}/{@code withdraw} handlers; spending is delegated withdraw-0 style
 *       through a {@code general_spend} wrapper. A validator's own hash is
 *       simultaneously its minting policy id and its withdraw script hash.</li>
 *   <li><b>The LenderManager subtree wraps with the LM config policy</b>, everything
 *       else with the main one. Guessing wrong still yields a plausible 28-byte hash.</li>
 *   <li><b>{@code loanClaimCredential} is a {@code Credential}</b>, so constructor
 *       alternative 1 ({@code Script}) — not raw bytes.</li>
 * </ol>
 */
@Service
@Getter
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LoansContractRegistry {

    private static final String BLUEPRINT_RESOURCE = "loans-v4.plutus.json";

    private final String configPolicyId;
    private final String lmConfigPolicyId;
    private final String configAssetName;

    // Tier 0 — bonds. `bond.ak` is parameterised only by an Int discriminator (borrower == 0,
    // lender == 1) which the validator body never reads; it exists purely to fork one source
    // into two policy ids. The lender bond policy is what identifies a lender position in the
    // LenderManager, i.e. step 1 of the bot scan loop.
    private final String borrowerBondPolicyId;
    private final String lenderBondPolicyId;

    // Tier 1 — parameterised directly by the main config NFT. Each of these doubles as
    // the validator's minting policy id and its withdraw script hash.
    private final String loanPolicyId;
    private final String poolPolicyId;
    private final String requestPolicyId;
    private final String assetManagerWithdrawScriptHash;
    private final String lockedBorrowerManagerWithdrawScriptHash;

    // Tier 2 — general_spend wrappers: the payment credentials UTxOs actually sit at.
    private final String loanSpendScriptHash;
    private final String poolSpendScriptHash;
    private final String requestSpendScriptHash;
    private final String assetManagerSpendScriptHash;
    private final String lockedBorrowerManagerSpendScriptHash;

    // Tier 3/4 — loan and pool actions.
    private final String loanClaimActionScriptHash;
    private final String loanRepayActionScriptHash;
    private final String loanRecastActionScriptHash;
    private final String loanChangeCollateralActionScriptHash;
    private final String poolCancelActionScriptHash;
    private final String poolBorrowActionScriptHash;
    private final String poolSellLenderPositionActionScriptHash;
    private final String poolCompoundActionScriptHash;

    // Tier 5 — LenderManager. Neither hash is published on chain; both must be derived.
    private final String lenderManagerWithdrawScriptHash;
    private final String lenderManagerSpendScriptHash;
    private final String lmWithdrawBondsActionScriptHash;
    private final String lmLiquidateActionScriptHash;
    private final String lmLiquidateAndPayInAdvanceActionScriptHash;
    private final String lmLiquidateConvertAndCompoundActionScriptHash;

    // Tier 6/7 — pool manager and the LM actions that depend on it. These additionally
    // need smartTokensSpendScriptHash, which is not derivable from the bundled blueprint
    // (no smart_tokens validator is shipped) and so has to be supplied from config or the
    // on-chain ConfigDatum. Null when it is absent — the liquidation path does not use them.
    private final String poolManagerPolicyId;
    private final String poolManagerSpendScriptHash;
    /**
     * {@code pool_manager/pm_cancel_pool_manager} — the withdraw script that validates burning a
     * PoolManager NFT alongside its pool. Unlike the two above it is <b>not</b> published in the
     * {@code ConfigDatum} (the pool-manager action hashes are baked into {@code pool_manager.ak}'s
     * parameters instead), so nothing cross-checks it on chain and {@code LoansConfigVerifier} must
     * not grow an expectation for it — the same standing as the LenderManager hashes.
     */
    private final String pmCancelPoolManagerScriptHash;
    private final String lmCompoundActionScriptHash;
    private final String lmLiquidatePayInAdvanceAndCompoundActionScriptHash;

    private final Map<String, String> code;

    /**
     * The <em>applied</em> compiled code of every validator this registry derives, keyed by its
     * script hash. Filled by {@link #derive}, so a script is retained exactly when its hash was
     * derived — the two can never disagree.
     */
    @Getter(AccessLevel.NONE)
    private final Map<String, String> appliedCompiledCode = new LinkedHashMap<>();

    @Getter(AccessLevel.NONE)
    private final Map<String, PlutusScript> scriptCache = new ConcurrentHashMap<>();

    @Autowired
    public LoansContractRegistry(AppConfig.LoansConfiguration cfg) {
        this(require(cfg.getConfigPolicyId(), "loans.config.policy-id"),
                require(cfg.getLmConfigPolicyId(), "loans.lm-config.policy-id"),
                require(cfg.getConfigAssetName(), "loans.config.asset-name"),
                cfg.getSmartTokensSpendScriptHash());
    }

    @SneakyThrows
    public LoansContractRegistry(String configPolicyId, String lmConfigPolicyId,
                                 String configAssetName, String smartTokensSpendScriptHash) {
        this.code = loadUnappliedCompiledCodes();
        this.configPolicyId = configPolicyId;
        this.lmConfigPolicyId = lmConfigPolicyId;
        this.configAssetName = configAssetName;

        PlutusData mainCfg = b(configPolicyId);
        PlutusData name = b(configAssetName);

        this.borrowerBondPolicyId = derive("bond.bond", i(0));
        this.lenderBondPolicyId = derive("bond.bond", i(1));

        this.loanPolicyId = derive("loan.loan", mainCfg, name);
        this.poolPolicyId = derive("pool.pool", mainCfg, name);
        this.requestPolicyId = derive("request.request", mainCfg, name);
        this.assetManagerWithdrawScriptHash = derive("asset_manager.assetManager", mainCfg, name);
        this.lockedBorrowerManagerWithdrawScriptHash =
                derive("locked_borrower_manager.lockedBorrowerManager", mainCfg, name);

        this.loanSpendScriptHash = generalSpend(loanPolicyId, configPolicyId);
        this.poolSpendScriptHash = generalSpend(poolPolicyId, configPolicyId);
        this.requestSpendScriptHash = generalSpend(requestPolicyId, configPolicyId);
        this.assetManagerSpendScriptHash = generalSpend(assetManagerWithdrawScriptHash, configPolicyId);
        this.lockedBorrowerManagerSpendScriptHash =
                generalSpend(lockedBorrowerManagerWithdrawScriptHash, configPolicyId);

        PlutusData amSpend = b(assetManagerSpendScriptHash);
        PlutusData amWithdraw = b(assetManagerWithdrawScriptHash);

        this.loanClaimActionScriptHash =
                derive("loan/loan_claim_action.loan_claim_action", mainCfg, name, amSpend, amWithdraw);
        this.loanRepayActionScriptHash =
                derive("loan/loan_repay_action.loan_repay_action", mainCfg, name, amSpend, amWithdraw);
        this.loanRecastActionScriptHash =
                derive("loan/loan_recast_action.loan_recast_action", mainCfg, name, amSpend, amWithdraw);
        this.loanChangeCollateralActionScriptHash =
                derive("loan/loan_change_collateral_action.loan_change_collateral_action", mainCfg, name);

        this.poolCancelActionScriptHash = derive("pool/pool_cancel_action.pool_cancel_action", mainCfg, name);
        this.poolBorrowActionScriptHash = derive("pool/pool_borrow_action.pool_borrow_action", mainCfg, name);
        this.poolSellLenderPositionActionScriptHash =
                derive("pool/pool_sell_lender_position.pool_sell_lender_position_action", mainCfg, name);
        this.poolCompoundActionScriptHash = derive("pool/pool_compound_action.pool_compound_action", mainCfg, name);

        // Rule 2: the LenderManager wraps with the LM config policy, not the main one.
        this.lenderManagerWithdrawScriptHash = derive("lender_manager.lenderManager", b(lmConfigPolicyId), name);
        this.lenderManagerSpendScriptHash = generalSpend(lenderManagerWithdrawScriptHash, lmConfigPolicyId);
        PlutusData lmSpend = b(lenderManagerSpendScriptHash);

        this.lmWithdrawBondsActionScriptHash =
                derive("lender_manager/lm_withdraw_bonds_action.actionValidator", lmSpend);
        // Rule 3: loanClaimCredential is a Credential, hence the Script constructor.
        PlutusData loanClaimCredential = scriptCredential(loanClaimActionScriptHash);
        this.lmLiquidateActionScriptHash = derive("lender_manager/lm_liquidate_action.actionValidator",
                mainCfg, name, lmSpend, amSpend, amWithdraw, loanClaimCredential);
        this.lmLiquidateAndPayInAdvanceActionScriptHash =
                derive("lender_manager/lm_liquidate_and_pay_in_advance_action.actionValidator",
                        mainCfg, name, lmSpend, amSpend, amWithdraw, loanClaimCredential);
        this.lmLiquidateConvertAndCompoundActionScriptHash =
                derive("lender_manager/lm_liquidate_convert_and_compound_action.actionValidator");

        String smartTokens = smartTokensSpendScriptHash;
        if (smartTokens == null || smartTokens.isBlank()) {
            log.warn("loans.smart-tokens-spend-script-hash not set — pool manager, lmCompound and " +
                    "lmLiquidatePayInAdvanceAndCompound hashes will not be derived");
            this.poolManagerPolicyId = null;
            this.poolManagerSpendScriptHash = null;
            this.pmCancelPoolManagerScriptHash = null;
            this.lmCompoundActionScriptHash = null;
            this.lmLiquidatePayInAdvanceAndCompoundActionScriptHash = null;
        } else {
            PlutusData poolSpend = b(poolSpendScriptHash);
            PlutusData poolPolicy = b(poolPolicyId);
            PlutusData smartTokensSpend = b(smartTokens);
            String pmCancel = derive("pool_manager/pm_cancel_pool_manager.poolManager",
                    mainCfg, name, poolSpend, poolPolicy, smartTokensSpend);
            String pmUpdate = derive("pool_manager/pm_update_pool_manager.poolManager",
                    mainCfg, name, poolSpend, poolPolicy, smartTokensSpend);
            String pmCompound = derive("pool_manager/pm_compound_liquidity.poolManager",
                    b(lenderManagerWithdrawScriptHash), b(poolPolicyId));
            this.pmCancelPoolManagerScriptHash = pmCancel;
            this.poolManagerPolicyId = derive("pool_manager.poolManager",
                    mainCfg, name, poolSpend, poolPolicy, b(pmCancel), b(pmUpdate), b(pmCompound));
            this.poolManagerSpendScriptHash = generalSpend(poolManagerPolicyId, configPolicyId);
            this.lmCompoundActionScriptHash = derive("lender_manager/lm_compound_action.actionValidator",
                    mainCfg, name, lmSpend, b(lenderManagerWithdrawScriptHash), amSpend, amWithdraw,
                    b(poolManagerSpendScriptHash), b(poolManagerPolicyId));
            this.lmLiquidatePayInAdvanceAndCompoundActionScriptHash =
                    derive("lender_manager/lm_liquidate_pay_in_advance_and_compound_action.actionValidator",
                            mainCfg, name, lmSpend, amSpend, amWithdraw,
                            b(poolManagerSpendScriptHash), b(poolManagerPolicyId), loanClaimCredential);
        }

        log.info("Derived Lending v4 contract hashes: {}", derivedHashes());
    }

    /**
     * Every derived hash, keyed by the field name used in the on-chain config datums.
     * Used for logging and for cross-checking against the live ConfigDatum / LMConfigDatum.
     */
    public Map<String, String> derivedHashes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("loanPolicyId", loanPolicyId);
        m.put("poolPolicyId", poolPolicyId);
        m.put("requestPolicyId", requestPolicyId);
        m.put("loanSpendScriptHash", loanSpendScriptHash);
        m.put("poolSpendScriptHash", poolSpendScriptHash);
        m.put("requestSpendScriptHash", requestSpendScriptHash);
        m.put("assetManagerSpendScriptHash", assetManagerSpendScriptHash);
        m.put("lockedBorrowerManagerSpendScriptHash", lockedBorrowerManagerSpendScriptHash);
        m.put("loanClaimActionScriptHash", loanClaimActionScriptHash);
        m.put("loanRepayActionScriptHash", loanRepayActionScriptHash);
        m.put("loanRecastActionScriptHash", loanRecastActionScriptHash);
        m.put("loanChangeCollateralActionScriptHash", loanChangeCollateralActionScriptHash);
        m.put("poolCancelActionScriptHash", poolCancelActionScriptHash);
        m.put("poolBorrowActionScriptHash", poolBorrowActionScriptHash);
        m.put("poolSellLenderPositionActionScriptHash", poolSellLenderPositionActionScriptHash);
        m.put("poolCompoundActionScriptHash", poolCompoundActionScriptHash);
        m.put("lenderManagerWithdrawScriptHash", lenderManagerWithdrawScriptHash);
        m.put("lenderManagerSpendScriptHash", lenderManagerSpendScriptHash);
        m.put("borrowerBondPolicyId", borrowerBondPolicyId);
        m.put("lenderBondPolicyId", lenderBondPolicyId);
        m.put("lmWithdrawBondsActionScriptHash", lmWithdrawBondsActionScriptHash);
        m.put("lmLiquidateActionScriptHash", lmLiquidateActionScriptHash);
        m.put("lmLiquidateAndPayInAdvanceActionScriptHash", lmLiquidateAndPayInAdvanceActionScriptHash);
        m.put("lmLiquidateConvertAndCompoundActionScriptHash", lmLiquidateConvertAndCompoundActionScriptHash);
        m.put("poolManagerPolicyId", poolManagerPolicyId);
        m.put("poolManagerSpendScriptHash", poolManagerSpendScriptHash);
        // Logged and cross-checkable, but deliberately absent from LoansConfigVerifier's expectations:
        // the ConfigDatum does not publish it (see the field's own javadoc).
        m.put("pmCancelPoolManagerScriptHash", pmCancelPoolManagerScriptHash);
        m.put("lmCompoundActionScriptHash", lmCompoundActionScriptHash);
        m.put("lmLiquidatePayInAdvanceAndCompoundActionScriptHash", lmLiquidatePayInAdvanceAndCompoundActionScriptHash);
        return m;
    }

    /**
     * The payment credentials the indexer has to keep UTxOs for.
     * <p>
     * Payment credential, never full address: loan/pool/lender-manager outputs carry the
     * lender's stake credential ({@code LenderManagerDatum.lenderStakeCredential}), so the
     * bech32 address varies per lender while the payment credential is fixed. Verified
     * against live preview loans — see docs/lending-v4-findings.md §5.
     * <p>
     * Includes both config policy ids: each config NFT sits at its own validator's script
     * address, so the policy id doubles as the payment credential holding the config UTxO
     * we need as a reference input.
     */
    public List<String> indexedPaymentCredentials() {
        return Stream.of(
                        configPolicyId,
                        lmConfigPolicyId,
                        loanSpendScriptHash,
                        poolSpendScriptHash,
                        requestSpendScriptHash,
                        assetManagerSpendScriptHash,
                        lockedBorrowerManagerSpendScriptHash,
                        lenderManagerSpendScriptHash,
                        poolManagerSpendScriptHash)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    // ---- Applied scripts (witness attachment) --------------------------------------------
    //
    // A transaction has to put the actual script bytes somewhere: either the caller points at a
    // published reference-script UTxO, or the script travels in the witness set. The getters below
    // serve the second path, for the validators this repo builds transactions against. Nothing is
    // exposed speculatively: a script that is not invoked and still ends up in the witness set is
    // an ExtraneousScriptWitness error, so each accessor here exists because some transaction
    // builder needs to attach that exact script.
    //
    // Seven of them serve the T-008 `Liquidate` transaction. The eighth, `getRequestScript()`, is
    // exposed for the `src/test`-only loan-origination fixtures of T-016 and is never reached from
    // a `src/main` code path — origination is not something the node does. The ninth,
    // `getRequestSpendScript()`, is likewise `src/test`-only, reached from the T-016 Cancel fixture:
    // a Cancel spends the request UTxO, so it needs the `general_spend` *wrapper* the UTxO sits at
    // as well as the policy — three script purposes, two distinct scripts.
    //
    // The next six — `getPoolScript()`, `getPoolSpendScript()`, `getPoolBorrowActionScript()`,
    // `getPoolCancelActionScript()`, `getLenderBondScript()`, `getBorrowerBondScript()` — are
    // `src/test`-only too, serving the T-016 S1 pool-origination fixtures. Each delegates to
    // `scriptOf(<existing hash field>)`; none introduces a new derivation or a new field. Pool
    // creation, borrowing and cancelling are not things the node does either — it indexes pools
    // and liquidates loans — so these stay out of every `src/main` code path for the same reason
    // the request accessors do.
    //
    // The last three — `getPoolManagerScript()`, `getPoolManagerSpendScript()` and
    // `getPmCancelPoolManagerScript()` — are `src/test`-only on the same terms, serving T-024: a pool
    // this factory creates must mint a PoolManager NFT beside its pool NFT, and cancelling it must burn
    // that NFT in the same transaction (`pool_manager.ak`'s mint pairs the two, and
    // `pm_cancel_pool_manager.ak` refuses a burn that does not also spend the PoolManager UTxO). Only
    // `pmCancelPoolManagerScriptHash` is a new field, and it is a hash this constructor already derived
    // and then discarded — no new derivation.
    //
    // Every one of them is the same applied compiled code the hash above was taken from, so
    // `script.getScriptHash()` is the matching `…ScriptHash`/`…PolicyId` by construction rather
    // than by coincidence (asserted in LoansContractDerivationTest).

    /** {@code loan.loan} — simultaneously the loan minting policy and the loan withdraw script. */
    public PlutusScript getLoanScript() {
        return scriptOf(loanPolicyId);
    }

    /** The loan {@code general_spend} wrapper — the spending validator loan UTxOs sit at. */
    public PlutusScript getLoanSpendScript() {
        return scriptOf(loanSpendScriptHash);
    }

    /** {@code lender_manager.lenderManager} — the LenderManager withdraw script. */
    public PlutusScript getLenderManagerScript() {
        return scriptOf(lenderManagerWithdrawScriptHash);
    }

    /** The LenderManager {@code general_spend} wrapper — where lender-bond UTxOs sit. */
    public PlutusScript getLenderManagerSpendScript() {
        return scriptOf(lenderManagerSpendScriptHash);
    }

    /** {@code loan/loan_claim_action} — the withdraw script that validates the claim numbers. */
    public PlutusScript getLoanClaimActionScript() {
        return scriptOf(loanClaimActionScriptHash);
    }

    /** {@code lender_manager/lm_liquidate_action} — the withdraw script for a plain liquidation. */
    public PlutusScript getLmLiquidateActionScript() {
        return scriptOf(lmLiquidateActionScriptHash);
    }

    /**
     * {@code lender_manager/lm_liquidate_and_pay_in_advance_action} — the withdraw script for a
     * liquidation that pays the principal in advance and takes the collateral. Mirrors
     * {@link #getLmLiquidateActionScript()}; the matching hash getter
     * {@code getLmLiquidateAndPayInAdvanceActionScriptHash()} is the class-level {@code @Getter}.
     */
    public PlutusScript getLmLiquidateAndPayInAdvanceActionScript() {
        return scriptOf(lmLiquidateAndPayInAdvanceActionScriptHash);
    }

    /** {@code asset_manager.assetManager} — the withdraw script guarding the collateral outputs. */
    public PlutusScript getAssetManagerScript() {
        return scriptOf(assetManagerWithdrawScriptHash);
    }

    /** {@code request.request} — the request minting policy, and the request withdraw script. */
    public PlutusScript getRequestScript() {
        return scriptOf(requestPolicyId);
    }

    /** The request {@code general_spend} wrapper — the spending validator request UTxOs sit at. */
    public PlutusScript getRequestSpendScript() {
        return scriptOf(requestSpendScriptHash);
    }

    /** {@code pool.pool} — simultaneously the pool minting policy and the pool withdraw script. */
    public PlutusScript getPoolScript() {
        return scriptOf(poolPolicyId);
    }

    /** The pool {@code general_spend} wrapper — the spending validator pool UTxOs sit at. */
    public PlutusScript getPoolSpendScript() {
        return scriptOf(poolSpendScriptHash);
    }

    /** {@code pool/pool_borrow_action} — the withdraw script that validates the Borrow action. */
    public PlutusScript getPoolBorrowActionScript() {
        return scriptOf(poolBorrowActionScriptHash);
    }

    /** {@code pool/pool_cancel_action} — the withdraw script that validates the Cancel action. */
    public PlutusScript getPoolCancelActionScript() {
        return scriptOf(poolCancelActionScriptHash);
    }

    /** {@code bond.bond} applied to {@code 1} — the lender-bond minting policy. */
    public PlutusScript getLenderBondScript() {
        return scriptOf(lenderBondPolicyId);
    }

    /** {@code bond.bond} applied to {@code 0} — the borrower-bond minting policy. */
    public PlutusScript getBorrowerBondScript() {
        return scriptOf(borrowerBondPolicyId);
    }

    /**
     * {@code pool_manager.poolManager} — simultaneously the PoolManager minting policy and the
     * PoolManager withdraw script. Serves the T-024 pool-create transaction, which mints the
     * PoolManager NFT beside the pool NFT, and the pool-cancel transaction, which burns it.
     */
    public PlutusScript getPoolManagerScript() {
        return scriptOf(poolManagerPolicyId);
    }

    /**
     * The PoolManager {@code general_spend} wrapper — the spending validator PoolManager UTxOs sit at.
     * Serves the T-024 pool-cancel transaction: {@code pm_cancel_pool_manager} requires the PoolManager
     * UTxO to be <em>spent</em>, not merely read, so the cancel needs the wrapper as well as the policy.
     */
    public PlutusScript getPoolManagerSpendScript() {
        return scriptOf(poolManagerSpendScriptHash);
    }

    /**
     * {@code pool_manager/pm_cancel_pool_manager} — the withdraw script that validates the PoolManager
     * burn. Serves the T-024 pool-cancel transaction, whose {@code pool_manager.poolManager} withdraw
     * routes its {@code CancelPoolManager} action here.
     */
    public PlutusScript getPmCancelPoolManagerScript() {
        return scriptOf(pmCancelPoolManagerScriptHash);
    }

    private PlutusScript scriptOf(String scriptHash) {
        // Only the pool-manager family can be null here, and only when smartTokensSpendScriptHash was
        // absent so its hashes were never derived (see the constructor). Say so, rather than letting
        // computeIfAbsent throw a bare NullPointerException with nothing in it to act on.
        if (scriptHash == null) {
            throw new IllegalStateException("no script hash was derived: "
                    + "loans.smart-tokens-spend-script-hash is not set, so the pool-manager validators "
                    + "(pool_manager.poolManager, its general_spend wrapper and pm_cancel_pool_manager) "
                    + "have no hash and no script");
        }
        return scriptCache.computeIfAbsent(scriptHash, hash -> {
            String applied = appliedCompiledCode.get(hash);
            if (applied == null) {
                throw new IllegalStateException("no applied compiled code retained for script hash " + hash);
            }
            try {
                return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3);
            } catch (Exception e) {
                throw new IllegalStateException("cannot build PlutusScript for " + hash, e);
            }
        });
    }

    // ---- Derivation primitives ----------------------------------------------------------

    /**
     * Wraps a withdraw script hash in {@code general_spend}. Every "…SpendScriptHash" in
     * the config datum is built this way.
     */
    private String generalSpend(String withdrawScriptHash, String configNftPolicyId) {
        return derive("general_spend.general_spend",
                b(withdrawScriptHash), b(configNftPolicyId), b(configAssetName));
    }

    private String derive(String validator, PlutusData... params) {
        String unapplied = code.get(validator);
        if (unapplied == null) {
            throw new IllegalStateException("no such validator in " + BLUEPRINT_RESOURCE + ": " + validator);
        }
        ListPlutusData list = ListPlutusData.builder().build();
        for (PlutusData p : params) {
            list.add(p);
        }
        String applied = AikenScriptUtil.applyParamToScript(list, unapplied);
        String hash = hashOf(applied);
        appliedCompiledCode.put(hash, applied);
        return hash;
    }

    private static String hashOf(String compiledCode) {
        try {
            return HexUtil.encodeHexString(PlutusBlueprintUtil
                    .getPlutusScriptFromCompiledCode(compiledCode, PlutusVersion.v3).getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash compiled code", e);
        }
    }

    private static BytesPlutusData b(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    /** Integer validator parameter — only {@code bond.ak}'s borrower/lender discriminator uses one. */
    private static BigIntPlutusData i(long value) {
        return BigIntPlutusData.of(BigInteger.valueOf(value));
    }

    /** A {@code cardano/address.Credential} holding a script hash — {@code Script} is its second constructor. */
    private static ConstrPlutusData scriptCredential(String scriptHash) {
        return ConstrPlutusData.of(1, b(scriptHash));
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set when loans.enabled=true");
        }
        return value;
    }

    private static Map<String, String> loadUnappliedCompiledCodes() throws Exception {
        Map<String, String> m = new HashMap<>();
        try (InputStream is = new ClassPathResource(BLUEPRINT_RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(is);
            for (JsonNode v : root.get("validators")) {
                // All handlers of a validator share one compiled code / hash; first wins.
                m.putIfAbsent(v.get("title").asText().replaceAll("\\.[^.]+$", ""), v.get("compiledCode").asText());
            }
        }
        return m;
    }
}
