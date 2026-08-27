package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bloxbean.cardano.client.common.model.Networks.preview;

/**
 * <h2>⛔ WARNING — THIS RUNNER'S COIN SELECTION CAN SPEND A REFERENCE SCRIPT</h2>
 * It builds through {@code new QuickTxBuilder(...)} with cardano-client-lib's <b>default</b> coin
 * selection, which takes any UTxO at the funding address — <b>including one carrying a
 * {@code scriptRef}</b>. It also submits for real when its gate is set.
 * <p>
 * On 2026-08-25 a sibling builder did exactly this and destroyed a published reference-script UTxO
 * that had been explicitly preserved. That script was dead, so the damage was nil and the locked
 * min-ada returned as change. <b>A live reference script destroyed this way is unrecoverable, and
 * everything referencing it breaks at once.</b>
 * <p>
 * <b>Before running this against any wallet, check that the wallet holds no UTxO with a reference
 * script.</b> The proper fix is {@code ReferenceScriptSafeUtxoSelection} — wired into
 * {@code LiquidateTransactionBuilder}, {@code LiquidatePayInAdvanceTransactionBuilder},
 * {@code ScheduledTransactionService} and {@code ReferenceScriptPublisher}, and <b>deliberately not
 * yet</b> into this runner or the other five test-tree builders ({@code PoolCreate}, {@code PoolCancel},
 * {@code PoolBorrow}, {@code RequestMint}, {@code RequestCancel},
 * {@code LiquidatePayInAdvanceAndCompound}). That is a known, recorded gap rather than an oversight —
 * but this runner is the one that submits, so it is the one that carries the warning.
 *
 * <h2>What it does</h2>
 * The T-016-X on-chain runner: the same {@link LoanFactory} origination pipeline as
 * {@link LoanFactoryRunnerTest}, but built against the <b>real preview chain</b> — real wallet UTxOs,
 * the real config reference input, the real published reference-script UTxOs, real protocol
 * parameters — then <b>signed</b> with wallet B and printed. Submission is a separate, explicitly
 * gated act.
 *
 * <h2>Three gates, and the submit gate is the last of them</h2>
 * <ul>
 *   <li>{@code WALLET_B_MNEMONIC} and {@code BLOCKFROST_KEY} (class level) — without both, every
 *       method below is skipped, so an ordinary keyless build never runs any of this.</li>
 *   <li>{@code AQUARIUM_X_PHASE} (method level) — {@code create}, {@code borrow} or {@code split}.
 *       Exactly one phase runs per invocation. The borrow phase needs a pool that only exists once
 *       the create transaction is <em>confirmed</em>, so the two cannot be one run.</li>
 *   <li>{@code AQUARIUM_X_SUBMIT} — compared by explicit string equality against {@code "true"}.
 *       Anything else (unset, empty, {@code TRUE}, {@code 1}, {@code yes}) prints
 *       {@code NOT SUBMITTED} and the signed CBOR, and puts nothing on the network.</li>
 * </ul>
 *
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   AQUARIUM_X_PHASE=create ./gradlew cleanTest test --tests '*LoanFactoryOnChainRunnerTest' -i
 *   # ... wait for confirmation, then:
 *   AQUARIUM_X_PHASE=borrow AQUARIUM_X_POOL_TX=<create tx hash> \
 *     ./gradlew cleanTest test --tests '*LoanFactoryOnChainRunnerTest' -i
 * }</pre>
 *
 * <h2>Everything is resolved from chain, and every resolution is checked</h2>
 * {@link LoanFactoryRunnerTest} fabricates its seed, funder, config and reference-script UTxOs. This
 * one resolves each of them through the Blockfrost-backed {@link UtxoSupplier} and refuses to
 * continue unless:
 * <ul>
 *   <li>the mnemonic derives exactly {@link #WALLET_B_ADDRESS} (so a wrong mnemonic cannot quietly
 *       originate a loan from some other wallet);</li>
 *   <li>the config reference input carries the pinned config NFT <em>and</em> an inline datum
 *       byte-identical to the committed {@code preview-config-datum.hex} — the redeploy detector
 *       {@code LoansConfigVerifier} structurally cannot be (CLAUDE.md, findings §12);</li>
 *   <li>every one of the seven published reference-script UTxOs exists on chain and publishes a
 *       {@code reference_script_hash} equal to the hash {@link LoansContractRegistry} derives for
 *       that validator.</li>
 * </ul>
 * A redeploy shows up as a loud failure in exactly those two places.
 *
 * <h2>The price is the live one, not the fixture's</h2>
 * {@link LoanFactoryRunnerTest} checks born-liquidatable at the design price {@code 1/2}. This runner
 * uses the live Charli3 tFLDT price read off {@code testapi.fluidtokens.com/get-oracle-tokens}
 * ({@link #PRICE_NUMERATOR}/{@link #PRICE_DENOMINATOR}), so the born-liquidatable gate is asserted at
 * the price a liquidation would actually be assessed at.
 *
 * <h2>Real ex-units — the blocker this runner used to only be able to measure</h2>
 * The three pool builders {@link LoanFactory} composes used to wire no {@code TransactionEvaluator},
 * so cardano-client-lib's {@code ScriptCostEvaluators.evaluateScriptCost()} threw, the error was
 * swallowed ({@code ignoreScriptCostEvaluationError} defaults to true) and every redeemer kept
 * {@code ScriptTx}'s dummy {@code mem 10000 / steps 10000} — orders of magnitude below what these
 * validators really cost, with a fee understated to match, which is to say unsubmittable.
 * <p>
 * This runner now hands {@link LoanFactory} a real {@link AikenTransactionEvaluator} over the
 * Blockfrost-backed suppliers ({@link #evaluator}), so every phase's redeemers carry measured budgets
 * and a fee computed from them, and {@link LoanFactory}'s {@code EX_UNITS_GATE} refuses any body whose
 * declared budget falls short of a fresh measurement. {@link #logExUnits} still prints declared beside
 * measured on every phase — with the evaluator wired the two now agree, and that agreement is the
 * evidence, exactly as the gap used to be.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_B_MNEMONIC", matches = ".+",
        disabledReason = "manual on-chain origination script: needs wallet B's mnemonic, "
                + "run with `set -a; . ./.env.preview; set +a`")
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "needs a preview Blockfrost key: every UTxO here is resolved from chain")
public class LoanFactoryOnChainRunnerTest {

    /**
     * ⛔ {@code shippedPreviewRegistry()}, NOT {@code registry()}.
     * <p>
     * This runner creates REAL loans on preview, so it must use the coordinates the running node is
     * pinned to. It was on {@code LoanFixtures.registry()} — the THIRD deployment, superseded
     * 2026-08-25 — which meant every loan it created would have landed at credentials
     * {@code TankUtxoStorage} does not index. <b>The bot would have reported {@code 0 live bonds} and
     * that reads as "the experiment failed" or "the indexer is broken", neither of which is true.</b>
     * Caught 2026-08-27 before any ada was spent.
     * <p>
     * The fixture registry deliberately stays on the third deployment: 25 test files replay recorded
     * third-deployment data through it, and re-pointing THAT is the open decision about the 37.
     */
    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();

    private static final String PREVIEW_BLOCKFROST_URL = "https://cardano-preview.blockfrost.io/api/v0/";

    /**
     * Wallet B's preview base address at derivation index 0 — lender, borrower, funder, fee payer and
     * change. Asserted against what the mnemonic derives, so the wrong mnemonic stops the run rather
     * than originating a loan from an unintended wallet.
     */
    private static final String WALLET_B_ADDRESS =
            "addr_test1qqzkww0vlvkc058y3ynu5gke7x8wk8al9gxt0dj6ruuzga0456s4c8hzmxeuv8nyxaw3du764j3afhn9lxcwwamcr6sq6mhxsc";

    /**
     * The transaction that minted both config NFTs of the THIRD preview deployment
     * ({@code loans.config.ref-utxo-tx-hash} of the preview profile). Outputs 0 and 1 carry the config
     * and the LenderManager config; which is which is discovered rather than assumed.
     */
    private static final String CONFIG_MINT_TX =
            "7374a98596cf03c323a0dd1643178861301f1060646789ae4d385ec3e54be781";

    /**
     * The live Charli3 tFLDT price, {@code tokenPriceInLovelaces / tokenPriceDenominator} as served by
     * {@code https://testapi.fluidtokens.com/get-oracle-tokens} — 0.338163 lovelace per tFLDT base
     * unit. At this price 10,000,000 units of collateral are worth 3,381,630 lovelace against a
     * 5,000,000 lovelace debt: LTV ≈ 1.48 against the pool's 0.80 liquidation LTV, so the loan is born
     * liquidatable and the 100‰ fee slice floors to 1,000,000 units (1 tFLDT).
     */
    private static final long PRICE_NUMERATOR = 338_163L;
    private static final long PRICE_DENOMINATOR = 1_000_000L;

    /** The loan output's lovelace leg, as {@link LoanFactoryRunnerTest} sizes it. */
    private static final long LOAN_OUTPUT_LOVELACE = 2_500_000L;
    /** Both bond outputs' lovelace leg. */
    private static final long BOND_LOVELACE = 1_500_000L;

    /** The borrow validity window, in slots, around the chain tip. */
    private static final long VALIDITY_LOWER_MARGIN_SLOTS = 60L;
    private static final long VALIDITY_UPPER_MARGIN_SLOTS = 120L;

    /** The lovelace the split phase peels off into a second, pure-ada wallet UTxO. */
    private static final long SPLIT_LOVELACE = 50_000_000L;

    /** How many 100-UTxO pages the unspent check will walk before calling the answer inconclusive. */
    private static final int UNSPENT_SCAN_PAGES = 10;

    // ==============================================================================================
    // Phase: create
    // ==============================================================================================

    /**
     * Builds, gates, signs and prints the pool-creation transaction against the real chain. Submits
     * only under {@code AQUARIUM_X_SUBMIT=true}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "AQUARIUM_X_PHASE", matches = "create",
            disabledReason = "phase gate: run with AQUARIUM_X_PHASE=create")
    public void createPhase() throws Exception {
        BackendService backend = backend();
        UtxoSupplier utxoSupplier = utxoSupplierOf(backend);
        ProtocolParamsSupplier protocolParams = protocolParamsOf(backend);

        Account wallet = walletB();
        Address lender = new Address(wallet.baseAddress());

        List<Utxo> walletUtxos = walletUtxos(utxoSupplier, lender.getAddress());
        Utxo seedUtxo = largestLovelace(walletUtxos, "the pool-create seed");
        Utxo funderUtxo = collateralBearing(walletUtxos, seedUtxo);

        log.info("PHASE create — seed {}#{}, funder {}#{} (same UTxO: {})",
                seedUtxo.getTxHash(), seedUtxo.getOutputIndex(),
                funderUtxo.getTxHash(), funderUtxo.getOutputIndex(),
                seedUtxo.equals(funderUtxo));

        Utxo configUtxo = resolveConfigUtxo(utxoSupplier);
        Utxo poolPolicyRefScript = resolveReferenceScript(utxoSupplier, REGISTRY.getPoolPolicyId(),
                "pool.pool");
        List<Utxo> borrowRefScripts = borrowReferenceScripts(utxoSupplier);
        List<Utxo> cancelRefScripts = cancelReferenceScripts(utxoSupplier);

        LoanFactory factory = new LoanFactory(REGISTRY, LoanFixtures.NETWORK, utxoSupplier, protocolParams,
                evaluator(utxoSupplier, protocolParams));
        LoanFactory.Recipe recipe = recipe(lender, seedUtxo, funderUtxo, configUtxo, poolPolicyRefScript,
                borrowRefScripts, cancelRefScripts, tipSlot(backend));

        // Gates 1 (DRY_EVAL), 5 (EX_UNITS) and 6 (LEDGER_PREFLIGHT) fire inside buildCreate: a non-green
        // evaluation, a redeemer declaring less than it costs, or a script travelling both in the witness
        // set and by reference input, each throws GateFailure.
        Transaction create = factory.buildCreate(recipe);

        String poolAssetName = PoolTxEncoder.poolAssetName(0,
                new TransactionInput(seedUtxo.getTxHash(), seedUtxo.getOutputIndex()));
        TransactionOutput poolOutput = create.getBody().getOutputs().get(0);

        log.info("pool NFT name: {}", poolAssetName);
        log.info("pool unit: {}", REGISTRY.getPoolPolicyId() + poolAssetName);
        log.info("output 0 (pool) address: {}", poolOutput.getAddress());
        log.info("output 0 (pool) value: {}", describe(poolOutput));
        logBody("CREATE", create);
        logExUnits("CREATE", create,
                universeOf(create, utxoSupplier, List.of(poolPolicyRefScript, configUtxo)),
                List.of(REGISTRY.getPoolScript()));

        // Gates 2 and 3 do not run at create — the lender bond and the collateral only exist at
        // borrow — so they are asserted here against the same recipe the borrow phase will use, and
        // the run stops now if either would refuse later.
        assertFeeIsHundredPerMille(recipe);
        assertBornLiquidatable(recipe);

        signPrintAndMaybeSubmit(backend, wallet, create, "CREATE");
    }

    // ==============================================================================================
    // Phase: rehearse
    // ==============================================================================================

    /**
     * The dress rehearsal: build create, then borrow and recovery-cancel <em>against that unconfirmed
     * create</em>, so all six {@link LoanFactory} gates — DRY_EVAL, EX_UNITS, LEDGER_PREFLIGHT, FEE_100,
     * BORN_LIQUIDATABLE and RECOVERY_DESTINATION — fire over real chain data before anything is on
     * chain. Nothing here is
     * ever signed and nothing is ever submitted: the borrow and the cancel both spend the same wallet
     * UTxO the create spends, so they are structurally unsubmittable and exist only to run the gates
     * and to measure the real ex-units and sizes.
     *
     * <h2>Why it cannot simply be part of the create phase</h2>
     * The create phase's job is to produce one signed transaction the orchestrator can submit; a
     * rehearsal failure there would redden the very run that carries that evidence. Kept separate, the
     * rehearsal is free to be adversarial.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "AQUARIUM_X_PHASE", matches = "rehearse",
            disabledReason = "phase gate: run with AQUARIUM_X_PHASE=rehearse")
    public void rehearsePhase() throws Exception {
        BackendService backend = backend();
        UtxoSupplier utxoSupplier = utxoSupplierOf(backend);
        ProtocolParamsSupplier protocolParams = protocolParamsOf(backend);

        Account wallet = walletB();
        Address lender = new Address(wallet.baseAddress());

        List<Utxo> walletUtxos = walletUtxos(utxoSupplier, lender.getAddress());
        Utxo seedUtxo = largestLovelace(walletUtxos, "the pool-create seed");
        Utxo funderUtxo = collateralBearing(walletUtxos, seedUtxo);

        Utxo configUtxo = resolveConfigUtxo(utxoSupplier);
        Utxo poolPolicyRefScript = resolveReferenceScript(utxoSupplier, REGISTRY.getPoolPolicyId(),
                "pool.pool");
        List<Utxo> borrowRefScripts = borrowReferenceScripts(utxoSupplier);
        List<Utxo> cancelRefScripts = cancelReferenceScripts(utxoSupplier);

        long tipSlot = tipSlot(backend);
        LoanFactory factory = new LoanFactory(REGISTRY, LoanFixtures.NETWORK, utxoSupplier, protocolParams,
                evaluator(utxoSupplier, protocolParams));
        LoanFactory.Recipe recipe = recipe(lender, seedUtxo, funderUtxo, configUtxo, poolPolicyRefScript,
                borrowRefScripts, cancelRefScripts, tipSlot);

        log.info("PHASE rehearse — NOTHING here is signed and NOTHING is submittable; the borrow and "
                + "the cancel both spend the create's own input");

        Transaction create = factory.buildCreate(recipe);
        log.info("REHEARSAL gates DRY_EVAL + EX_UNITS + LEDGER_PREFLIGHT green on the create");
        logBody("REHEARSAL CREATE", create);

        Utxo poolUtxo = poolContinuationOf(create);

        // The borrow and the cancel spend the create's *unconfirmed* continuation, which the chain
        // cannot resolve, so their evaluator needs a supplier that knows about it — see #resolving.
        LoanFactory poolFactory = new LoanFactory(REGISTRY, LoanFixtures.NETWORK, utxoSupplier,
                protocolParams, evaluator(resolving(utxoSupplier, poolUtxo), protocolParams));

        // DRY_EVAL + EX_UNITS + LEDGER_PREFLIGHT + FEE_100 + BORN_LIQUIDATABLE, all inside buildBorrow.
        Transaction borrow = poolFactory.buildBorrow(recipe, create);
        log.info("REHEARSAL gates DRY_EVAL + EX_UNITS + LEDGER_PREFLIGHT + FEE_100 + BORN_LIQUIDATABLE "
                + "green on the borrow");
        logBody("REHEARSAL BORROW", borrow);
        logExUnits("REHEARSAL BORROW", borrow,
                universeOf(borrow, resolving(utxoSupplier, poolUtxo),
                        concat(borrowRefScripts, List.of(configUtxo))),
                borrowExtraScripts());

        // DRY_EVAL + EX_UNITS + LEDGER_PREFLIGHT + RECOVERY_DESTINATION, inside buildRecoveryCancel.
        Transaction cancel = poolFactory.buildRecoveryCancel(recipe, create);
        log.info("REHEARSAL gates DRY_EVAL + EX_UNITS + LEDGER_PREFLIGHT + RECOVERY_DESTINATION green on "
                + "the recovery cancel");
        logBody("REHEARSAL CANCEL", cancel);
        logExUnits("REHEARSAL CANCEL", cancel,
                universeOf(cancel, resolving(utxoSupplier, poolUtxo),
                        concat(cancelRefScripts, List.of(configUtxo))),
                cancelExtraScripts());

        log.info("REHEARSAL complete — all six LoanFactory gates fired green against real chain data. "
                + "NOTHING WAS SIGNED AND NOTHING WAS SUBMITTED.");
    }

    /** Output 0 of a pool-bearing transaction, as a {@link Utxo}, for the measurement universe. */
    private Utxo poolContinuationOf(Transaction poolTx) {
        TransactionOutput output = poolTx.getBody().getOutputs().get(0);
        Utxo utxo = new Utxo();
        utxo.setTxHash(TransactionUtil.getTxHash(poolTx));
        utxo.setOutputIndex(0);
        utxo.setAddress(output.getAddress());
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(output.getValue().getCoin()));
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            for (Asset asset : multiAsset.getAssets()) {
                amounts.add(Amount.asset(multiAsset.getPolicyId() + strip(asset.getNameAsHex()),
                        asset.getValue()));
            }
        }
        utxo.setAmount(amounts);
        utxo.setInlineDatum(output.getInlineDatum() == null
                ? null : output.getInlineDatum().serializeToHex());
        return utxo;
    }

    // ==============================================================================================
    // Phase: borrow
    // ==============================================================================================

    /**
     * Builds, gates, signs and prints the pool-borrow transaction against the pool the create phase
     * put on chain. {@code AQUARIUM_X_POOL_TX} is the <b>confirmed</b> create transaction's hash; the
     * pool UTxO is resolved from chain at {@code (thatHash, 0)} and cross-checked against the create
     * transaction's own output 0.
     *
     * <h2>Why the create transaction is fetched back rather than rebuilt</h2>
     * {@link LoanFactory#buildBorrow} takes the create {@link Transaction} because it derives the bond
     * asset name from {@code (getTxHash(createTx), 0)}. Rebuilding the create offline would produce a
     * <em>different</em> hash the moment coin selection or the fee moved, and the bond would be minted
     * under a name no validator accepts. So the confirmed transaction's own CBOR is fetched from
     * Blockfrost, deserialised, and refused unless its hash is exactly {@code AQUARIUM_X_POOL_TX} and
     * its output 0 is byte-for-byte the pool UTxO the chain reports.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "AQUARIUM_X_PHASE", matches = "borrow",
            disabledReason = "phase gate: run with AQUARIUM_X_PHASE=borrow and AQUARIUM_X_POOL_TX set")
    public void borrowPhase() throws Exception {
        String poolTxHash = required("AQUARIUM_X_POOL_TX");

        BackendService backend = backend();
        UtxoSupplier utxoSupplier = utxoSupplierOf(backend);
        ProtocolParamsSupplier protocolParams = protocolParamsOf(backend);

        Account wallet = walletB();
        Address lender = new Address(wallet.baseAddress());

        Utxo configUtxo = resolveConfigUtxo(utxoSupplier);
        Utxo poolPolicyRefScript = resolveReferenceScript(utxoSupplier, REGISTRY.getPoolPolicyId(),
                "pool.pool");
        List<Utxo> borrowRefScripts = borrowReferenceScripts(utxoSupplier);
        List<Utxo> cancelRefScripts = cancelReferenceScripts(utxoSupplier);

        // The pool as the chain holds it, and the create transaction as the chain recorded it.
        Utxo poolUtxo = utxoSupplier.getTxOutput(poolTxHash, 0)
                .orElseThrow(() -> new IllegalStateException(
                        "no output 0 on " + poolTxHash + " — is the create transaction confirmed?"));
        Transaction createTx = fetchTransaction(poolTxHash);
        assertPoolOutputAgrees(createTx, poolUtxo, poolTxHash);

        // The seed is whichever create input hashes to the pool NFT the pool output actually holds.
        Utxo seedUtxo = seedBehind(createTx, poolUtxo);
        List<Utxo> walletUtxos = walletUtxos(utxoSupplier, lender.getAddress());
        Utxo funderUtxo = collateralBearing(walletUtxos, null);

        long tipSlot = tipSlot(backend);
        LoanFactory factory = new LoanFactory(REGISTRY, LoanFixtures.NETWORK, utxoSupplier, protocolParams,
                evaluator(resolving(utxoSupplier, poolUtxo), protocolParams));
        LoanFactory.Recipe recipe = recipe(lender, seedUtxo, funderUtxo, configUtxo, poolPolicyRefScript,
                borrowRefScripts, cancelRefScripts, tipSlot);

        String poolAssetName = PoolTxEncoder.poolAssetName(0,
                new TransactionInput(seedUtxo.getTxHash(), seedUtxo.getOutputIndex()));
        log.info("PHASE borrow — pool {}#0, pool NFT {}, seed {}#{}, funder {}#{}",
                poolTxHash, poolAssetName, seedUtxo.getTxHash(), seedUtxo.getOutputIndex(),
                funderUtxo.getTxHash(), funderUtxo.getOutputIndex());
        log.info("validity slots {} .. {} (tip {})", recipe.validFromSlot(), recipe.validToSlot(), tipSlot);

        // All four gates fire inside buildBorrow: DRY_EVAL, EX_UNITS against a fresh measurement,
        // FEE_100 off the emitted bond, and BORN_LIQUIDATABLE at the live price.
        Transaction borrow = factory.buildBorrow(recipe, createTx);

        String bondAssetName = PoolTxEncoder.bondAssetName(new TransactionInput(poolTxHash, 0));
        log.info("bond / loan asset name: {}", bondAssetName);
        logBody("BORROW", borrow);
        logExUnits("BORROW", borrow,
                universeOf(borrow, resolving(utxoSupplier, poolUtxo),
                        concat(borrowRefScripts, List.of(configUtxo))),
                borrowExtraScripts());

        signPrintAndMaybeSubmit(backend, wallet, borrow, "BORROW");
    }

    // ==============================================================================================
    // Phase: split
    // ==============================================================================================

    /**
     * The optional pre-phase: peel {@link #SPLIT_LOVELACE} off wallet B into a second, pure-ada UTxO
     * at the same address, so a script transaction has a collateral input that is not also its spend
     * input. An ordinary {@link Tx} with no script and no redeemer, so nothing here depends on the
     * ex-units defect the class javadoc records. Needed only if the create phase cannot build against
     * a single wallet UTxO — run the create phase first and read its outcome.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "AQUARIUM_X_PHASE", matches = "split",
            disabledReason = "phase gate: run with AQUARIUM_X_PHASE=split")
    public void splitPhase() throws Exception {
        BackendService backend = backend();
        Account wallet = walletB();
        String address = wallet.baseAddress();
        assertWalletB(address);

        List<Utxo> before = walletUtxos(utxoSupplierOf(backend), address);
        log.info("PHASE split — wallet B holds {} UTxO(s) before the split", before.size());

        Tx tx = new Tx()
                .payToAddress(address, List.of(Amount.lovelace(BigInteger.valueOf(SPLIT_LOVELACE))))
                .from(address);

        // Null TransactionProcessor: this builder cannot submit either. mergeOutputs(false) keeps the
        // peeled output and the change apart — merged, the split would produce one UTxO again.
        Transaction split = new QuickTxBuilder(utxoSupplierOf(backend),
                protocolParamsOf(backend), null)
                .compose(tx)
                .feePayer(address)
                .mergeOutputs(false)
                .build();

        logBody("SPLIT", split);
        signPrintAndMaybeSubmit(backend, wallet, split, "SPLIT");
    }

    // ==============================================================================================
    // The recipe
    // ==============================================================================================

    /**
     * The approved parameters — {@link PoolFixtures#defaults()} (5 ada principal, 52 ada pool
     * liquidity, a 2/1 no-oracle collateral floor giving 10,000,000 tFLDT units, a 90‰ partial
     * liquidation penalty and the load-bearing 100‰ liquidation fee) — at the live tFLDT price, with
     * one wallet playing lender and borrower.
     */
    private LoanFactory.Recipe recipe(Address wallet, Utxo seedUtxo, Utxo funderUtxo, Utxo configUtxo,
                                      Utxo poolPolicyRefScript, List<Utxo> borrowRefScripts,
                                      List<Utxo> cancelRefScripts, long tipSlot) {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();
        return new LoanFactory.Recipe(
                params, wallet, wallet, PRICE_NUMERATOR, PRICE_DENOMINATOR,
                seedUtxo, funderUtxo, configUtxo, poolPolicyRefScript,
                borrowRefScripts, cancelRefScripts,
                PoolFixtures.TFLDT, params.principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE,
                tipSlot - VALIDITY_LOWER_MARGIN_SLOTS, tipSlot + VALIDITY_UPPER_MARGIN_SLOTS);
    }

    /**
     * The fee-100 invariant, asserted off the recipe at create time. {@link LoanFactory}'s own FEE_100
     * gate reads the fee back off the <em>emitted lender bond</em>, which only exists at borrow; this
     * is the same number one step earlier, so a mis-parameterised pool is refused before its NFT is
     * minted rather than after.
     */
    private void assertFeeIsHundredPerMille(LoanFactory.Recipe recipe) {
        long fee = recipe.params().liquidationFeePerMille();
        if (fee != 100L) {
            throw new LoanFactory.GateFailure(
                    "FEE_GATE (pre-flight): the recipe carries fee " + fee + " per mille, not 100");
        }
        log.info("FEE_100 pre-flight: the pool's liquidationFeePerMille is {}", fee);
    }

    /** The born-liquidatable property at the live price, asserted before the pool exists. */
    private void assertBornLiquidatable(LoanFactory.Recipe recipe) {
        try {
            PoolFixtures.assertBornLiquidatable(recipe.params(),
                    recipe.collateralPriceNumerator(), recipe.collateralPriceDenominator());
        } catch (AssertionError e) {
            throw new LoanFactory.GateFailure("BORN_LIQUIDATABLE_GATE (pre-flight): " + e.getMessage());
        }
        log.info("BORN_LIQUIDATABLE pre-flight: green at the live price {}/{} lovelace per tFLDT unit",
                recipe.collateralPriceNumerator(), recipe.collateralPriceDenominator());
    }

    // ==============================================================================================
    // Chain resolution — every coordinate checked, nothing assumed
    // ==============================================================================================

    private static UtxoSupplier utxoSupplierOf(BackendService backend) {
        return new DefaultUtxoSupplier(backend.getUtxoService());
    }

    private static ProtocolParamsSupplier protocolParamsOf(BackendService backend) {
        return new DefaultProtocolParamsSupplier(backend.getEpochService());
    }

    /**
     * The evaluator {@link LoanFactory} hands to all three pool builders, so their redeemers carry real
     * execution budgets instead of {@code ScriptTx}'s placeholders — the whole point of this slice.
     * <p>
     * It runs the real UPLC machine locally over the <em>live</em> protocol parameters (so the cost model
     * is preview's own, not a fixture's) and resolves every input through {@code supplier}.
     * cardano-client-lib calls {@code evaluateTx(cbor)} with no explicit input set, so that supplier is
     * the only thing the evaluator can resolve from: a phase whose transaction spends an unconfirmed
     * output must pass a supplier that knows about it.
     *
     * <h2>Why the script supplier carries {@link #referencedValidators()}</h2>
     * A validator that travels by <b>reference input</b> reaches the evaluator through neither the
     * witness set nor the input UTxO: a Blockfrost {@link Utxo} publishes only the reference script's
     * <em>hash</em>, never its bytes. Measured on preview, a borrow evaluated with the bare
     * {@code EvalFixtures.scriptSupplier(REGISTRY)} fails with {@code RequiredRedeemersMismatch
     * {missing: [pool general_spend, pool_borrow_action, pool.pool]}} — exactly the spend and the two
     * withdraw validators, while the three mint policies resolve fine because {@code mintAsset} leaves
     * their witness copies in place. So every validator this pipeline can reference is handed to the
     * supplier. Extras that a given phase does not use are inert: the supplier is a lookup by hash.
     */
    private TransactionEvaluator evaluator(UtxoSupplier supplier, ProtocolParamsSupplier protocolParams) {
        return new AikenTransactionEvaluator(supplier, protocolParams,
                EvalFixtures.scriptSupplier(REGISTRY, referencedValidators()), SlotConfigs.preview());
    }

    /** Every validator the create / borrow / cancel transactions may carry by reference input. */
    private List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> referencedValidators() {
        return List.of(REGISTRY.getPoolScript(), REGISTRY.getPoolSpendScript(),
                REGISTRY.getPoolBorrowActionScript(), REGISTRY.getPoolCancelActionScript(),
                REGISTRY.getLoanScript(), REGISTRY.getLenderBondScript(),
                REGISTRY.getBorrowerBondScript());
    }

    private BackendService backend() {
        return new BFBackendService(PREVIEW_BLOCKFROST_URL, required("BLOCKFROST_KEY"));
    }

    /** Wallet B at derivation index 0. The mnemonic is never logged, printed or otherwise emitted. */
    private Account walletB() {
        Account wallet = new Account(preview(), required("WALLET_B_MNEMONIC"));
        assertWalletB(wallet.baseAddress());
        return wallet;
    }

    private void assertWalletB(String derived) {
        if (!WALLET_B_ADDRESS.equals(derived)) {
            throw new IllegalStateException("WALLET_B_MNEMONIC derives " + derived
                    + ", not the expected wallet B address " + WALLET_B_ADDRESS);
        }
        log.info("wallet B (preview base, index 0): {}", derived);
        log.info("wallet B payment key hash: {}", HexUtil.encodeHexString(
                new Address(derived).getPaymentCredentialHash().orElseThrow()));
    }

    private List<Utxo> walletUtxos(UtxoSupplier utxoSupplier, String address) {
        List<Utxo> utxos = new ArrayList<>(utxoSupplier.getPage(address, 100, 0, OrderEnum.asc));
        if (utxos.isEmpty()) {
            throw new IllegalStateException("wallet B holds no UTxOs at " + address);
        }
        for (Utxo utxo : utxos) {
            log.info("wallet UTxO {}#{}: {}", utxo.getTxHash(), utxo.getOutputIndex(), utxo.getAmount());
        }
        return utxos;
    }

    private Utxo largestLovelace(List<Utxo> utxos, String what) {
        return utxos.stream()
                .max((a, b) -> lovelaceOf(a).compareTo(lovelaceOf(b)))
                .orElseThrow(() -> new IllegalStateException("no UTxO to serve as " + what));
    }

    /**
     * The funder: a UTxO carrying at least the collateral the loan output must post. Prefers one that
     * is not {@code exclude} (the seed), and falls back to the seed itself when wallet B holds a single
     * UTxO — the case this wallet is actually in.
     */
    private Utxo collateralBearing(List<Utxo> utxos, Utxo exclude) {
        long needed = PoolFixtures.neededCollateral(
                PoolFixtures.defaults(), PoolFixtures.defaults().principalLovelace()).longValueExact();
        String unit = LoanFixtures.unit(PoolFixtures.TFLDT);
        List<Utxo> bearing = utxos.stream()
                .filter(u -> quantityOf(u, unit).compareTo(BigInteger.valueOf(needed)) >= 0)
                .toList();
        if (bearing.isEmpty()) {
            throw new IllegalStateException("no wallet UTxO carries the " + needed + " units of "
                    + unit + " the loan output must post");
        }
        return bearing.stream()
                .filter(u -> exclude == null || !sameCoordinate(u, exclude))
                .findFirst()
                .orElse(bearing.get(0));
    }

    /**
     * The config reference input, located among the two outputs of the config-mint transaction by the
     * config NFT it carries, then refused unless its inline datum is byte-identical to the committed
     * {@code preview-config-datum.hex}. That comparison is the redeploy detector: a redeployed v4
     * mints fresh config NFTs under a fresh policy id, and this coordinate would stop carrying the
     * pinned one.
     */
    private Utxo resolveConfigUtxo(UtxoSupplier utxoSupplier) {
        String unit = REGISTRY.getConfigPolicyId() + LoanFixtures.CONFIG_ASSET_NAME;
        for (int index = 0; index <= 1; index++) {
            Optional<Utxo> candidate = utxoSupplier.getTxOutput(CONFIG_MINT_TX, index);
            if (candidate.isEmpty()
                    || quantityOf(candidate.get(), unit).compareTo(BigInteger.ONE) < 0) {
                continue;
            }
            Utxo configUtxo = candidate.get();
            String expected = LoanFixtures.fixture("preview-config-datum.hex");
            if (configUtxo.getInlineDatum() == null
                    || !configUtxo.getInlineDatum().equalsIgnoreCase(expected)) {
                throw new IllegalStateException("the config UTxO " + CONFIG_MINT_TX + "#" + index
                        + " carries an inline datum this repo has not pinned — SUSPECT A REDEPLOY. "
                        + "on chain: " + configUtxo.getInlineDatum());
            }
            assertUnspent(utxoSupplier, configUtxo, "the config reference input");
            log.info("config reference input {}#{} at {} — NFT {} present, datum matches the pinned one",
                    CONFIG_MINT_TX, index, configUtxo.getAddress(), unit);
            return configUtxo;
        }
        throw new IllegalStateException("neither output 0 nor output 1 of " + CONFIG_MINT_TX
                + " carries the config NFT " + unit + " — SUSPECT A REDEPLOY");
    }

    /**
     * A published reference-script UTxO at its {@link PoolFixtures#PUBLISHED_REFERENCE_SCRIPTS}
     * coordinate. Refuses unless the output exists on chain and publishes a
     * {@code reference_script_hash} equal to the hash the registry derives for that validator — the
     * place a redeploy surfaces if the config datum somehow did not catch it.
     */
    private Utxo resolveReferenceScript(UtxoSupplier utxoSupplier, String scriptHash, String what) {
        String coordinate = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(scriptHash);
        if (coordinate == null) {
            throw new IllegalStateException("no published coordinate for " + what + " (" + scriptHash + ")");
        }
        String txHash = coordinate.substring(0, coordinate.indexOf('#'));
        int index = Integer.parseInt(coordinate.substring(coordinate.indexOf('#') + 1));

        Utxo utxo = utxoSupplier.getTxOutput(txHash, index)
                .orElseThrow(() -> new IllegalStateException("the published reference script for " + what
                        + " is not on chain at " + coordinate));
        if (utxo.getReferenceScriptHash() == null
                || !utxo.getReferenceScriptHash().equalsIgnoreCase(scriptHash)) {
            throw new IllegalStateException("the UTxO at " + coordinate + " publishes reference script "
                    + utxo.getReferenceScriptHash() + ", not " + what + "'s " + scriptHash);
        }
        assertUnspent(utxoSupplier, utxo, "the reference script for " + what);
        log.info("reference script {} ({}) resolved at {} — address {}, {}",
                what, scriptHash, coordinate, utxo.getAddress(), utxo.getAmount());
        return utxo;
    }

    /** The six reference scripts a pool borrow reads, in the order {@link LoanFactory} expects them. */
    private List<Utxo> borrowReferenceScripts(UtxoSupplier utxoSupplier) {
        return List.of(
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolPolicyId(), "pool.pool"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolSpendScriptHash(), "pool general_spend"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolBorrowActionScriptHash(),
                        "pool_borrow_action"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getLoanPolicyId(), "loan.loan"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getLenderBondPolicyId(), "bond.bond(1) lender"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getBorrowerBondPolicyId(),
                        "bond.bond(0) borrower"));
    }

    /** The three reference scripts a pool recovery-cancel reads. */
    private List<Utxo> cancelReferenceScripts(UtxoSupplier utxoSupplier) {
        return List.of(
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolSpendScriptHash(), "pool general_spend"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolPolicyId(), "pool.pool"),
                resolveReferenceScript(utxoSupplier, REGISTRY.getPoolCancelActionScriptHash(),
                        "pool_cancel_action"));
    }

    /**
     * Refuses if the coordinate is no longer among the UTxOs at its own address — i.e. it has been
     * spent. {@code getTxOutput} answers "this output existed in that transaction", which stays true
     * forever after the output is consumed; only the address's live UTxO set answers "it is still
     * there". Every input this runner reads is a reference input, and a spent one fails on chain as an
     * opaque {@code BadInputsUTxO} long after the gates have all gone green.
     * <p>
     * Bounded at {@link #UNSPENT_SCAN_PAGES} pages of 100. A scan that ends before the cap is
     * conclusive and a miss is a hard refusal; a scan that hits the cap is inconclusive and says so
     * rather than pretending either way.
     */
    private void assertUnspent(UtxoSupplier utxoSupplier, Utxo utxo, String what) {
        for (int page = 0; page < UNSPENT_SCAN_PAGES; page++) {
            List<Utxo> live = utxoSupplier.getPage(utxo.getAddress(), 100, page, OrderEnum.asc);
            for (Utxo candidate : live) {
                if (sameCoordinate(candidate, utxo)) {
                    return;
                }
            }
            if (live.size() < 100) {
                throw new IllegalStateException(what + " at " + utxo.getTxHash() + "#"
                        + utxo.getOutputIndex() + " is NOT among the live UTxOs at " + utxo.getAddress()
                        + " — it has been spent");
            }
        }
        log.warn("could not confirm {} at {}#{} is unspent: more than {} pages of UTxOs at {}",
                what, utxo.getTxHash(), utxo.getOutputIndex(), UNSPENT_SCAN_PAGES, utxo.getAddress());
    }

    private long tipSlot(BackendService backend) throws Exception {
        Result<com.bloxbean.cardano.client.backend.model.Block> latest =
                backend.getBlockService().getLatestBlock();
        if (!latest.isSuccessful()) {
            throw new IllegalStateException("cannot read the preview tip: " + latest.getResponse());
        }
        return latest.getValue().getSlot();
    }

    /**
     * The create transaction as the chain recorded it, fetched as raw CBOR. cardano-client-lib's
     * {@code TransactionService} exposes no CBOR endpoint, so this is a plain read-only Blockfrost GET;
     * {@code AQUARIUM_X_POOL_TX_CBOR} overrides it for the case where the endpoint is unavailable.
     * Refused unless the deserialised body hashes back to the coordinate it was fetched for.
     */
    private Transaction fetchTransaction(String txHash) throws Exception {
        String cborHex = System.getenv("AQUARIUM_X_POOL_TX_CBOR");
        if (cborHex == null || cborHex.isBlank()) {
            cborHex = fetchCborFromBlockfrost(txHash);
        }
        Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex.trim()));
        String rederived = TransactionUtil.getTxHash(tx);
        if (!rederived.equalsIgnoreCase(txHash)) {
            throw new IllegalStateException("the fetched transaction re-serialises to " + rederived
                    + ", not " + txHash + " — its body did not survive the round trip, so every asset "
                    + "name derived from it would be wrong");
        }
        return tx;
    }

    private String fetchCborFromBlockfrost(String txHash) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(PREVIEW_BLOCKFROST_URL + "txs/" + txHash + "/cbor"))
                .header("project_id", required("BLOCKFROST_KEY"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Blockfrost refused the cbor of " + txHash + ": HTTP "
                    + response.statusCode() + " " + response.body()
                    + " — set AQUARIUM_X_POOL_TX_CBOR to the signed create transaction instead");
        }
        Matcher matcher = Pattern.compile("\"cbor\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("no cbor field in Blockfrost's answer for " + txHash);
        }
        return matcher.group(1);
    }

    /**
     * Refuses unless the fetched create transaction's output 0 is the very pool UTxO the chain reports
     * at {@code (poolTxHash, 0)} — same address, same inline datum bytes, same lovelace.
     */
    private void assertPoolOutputAgrees(Transaction createTx, Utxo poolUtxo, String poolTxHash) {
        TransactionOutput output = createTx.getBody().getOutputs().get(0);
        if (!output.getAddress().equals(poolUtxo.getAddress())) {
            throw new IllegalStateException("output 0 of " + poolTxHash + " is at " + output.getAddress()
                    + " in the fetched body but at " + poolUtxo.getAddress() + " on chain");
        }
        String bodyDatum = output.getInlineDatum() == null ? null : output.getInlineDatum().serializeToHex();
        if (bodyDatum == null || !bodyDatum.equalsIgnoreCase(poolUtxo.getInlineDatum())) {
            throw new IllegalStateException("output 0 of " + poolTxHash + " carries a different inline "
                    + "datum in the fetched body than on chain");
        }
        if (!output.getValue().getCoin().equals(lovelaceOf(poolUtxo))) {
            throw new IllegalStateException("output 0 of " + poolTxHash + " holds "
                    + output.getValue().getCoin() + " lovelace in the fetched body but "
                    + lovelaceOf(poolUtxo) + " on chain");
        }
        log.info("pool UTxO {}#0 confirmed on chain: {} at {}", poolTxHash,
                poolUtxo.getAmount(), poolUtxo.getAddress());
    }

    /**
     * The create input whose {@code 0x00 ‖ blake2b_224(serialise_data(ref))} is the pool NFT the pool
     * output actually holds. Derived rather than passed in, so the borrow can never be built against a
     * seed the pool was not created from.
     */
    private Utxo seedBehind(Transaction createTx, Utxo poolUtxo) {
        for (TransactionInput input : createTx.getBody().getInputs()) {
            String assetName = PoolTxEncoder.poolAssetName(0, input);
            if (quantityOf(poolUtxo, REGISTRY.getPoolPolicyId() + assetName)
                    .compareTo(BigInteger.ONE) >= 0) {
                Utxo seed = new Utxo();
                seed.setTxHash(input.getTransactionId());
                seed.setOutputIndex(input.getIndex());
                seed.setAddress(WALLET_B_ADDRESS);
                seed.setAmount(List.of(Amount.lovelace(BigInteger.ZERO)));
                return seed;
            }
        }
        throw new IllegalStateException("no input of the create transaction hashes to the pool NFT the "
                + "pool output holds — the pool was not created by this transaction's inputs");
    }

    // ==============================================================================================
    // Signing, printing, and the submit gate
    // ==============================================================================================

    /**
     * Signs with wallet B, prints the computed transaction hash and the signed CBOR, and submits only
     * under an exact {@code AQUARIUM_X_SUBMIT=true}. Every other value of that variable — unset,
     * empty, {@code TRUE}, {@code 1}, {@code yes} — takes the printing branch.
     */
    private void signPrintAndMaybeSubmit(BackendService backend, Account wallet, Transaction tx,
                                         String what) throws Exception {
        Transaction signed = wallet.sign(tx);
        byte[] cbor = signed.serialize();
        String txHash = TransactionUtil.getTxHash(signed);

        log.info("{} computed tx hash: {}", what, txHash);
        log.info("{} signed size: {} bytes", what, cbor.length);
        log.info("{} signed cbor: {}", what, HexUtil.encodeHexString(cbor));

        if (!"true".equals(System.getenv("AQUARIUM_X_SUBMIT"))) {
            log.info("{} NOT SUBMITTED (AQUARIUM_X_SUBMIT unset)", what);
            return;
        }

        Result<String> result = backend.getTransactionService().submitTransaction(cbor);
        if (!result.isSuccessful()) {
            throw new IllegalStateException(what + " submission refused: " + result.getResponse());
        }
        log.info("{} SUBMITTED — tx hash returned by the backend: {}", what, result.getValue());
    }

    // ==============================================================================================
    // Measurement and logging
    // ==============================================================================================

    private void logBody(String what, Transaction tx) throws Exception {
        log.info("{} fee: {} lovelace", what, tx.getBody().getFee());
        log.info("{} unsigned size: {} bytes", what, tx.serialize().length);
        log.info("{} inputs: {}", what, coordinates(tx.getBody().getInputs()));
        log.info("{} collateral: {}", what, coordinates(tx.getBody().getCollateral()));
        log.info("{} total collateral: {}, collateral return: {}", what, tx.getBody().getTotalCollateral(),
                tx.getBody().getCollateralReturn() == null
                        ? "none" : describe(tx.getBody().getCollateralReturn()));
        log.info("{} reference inputs: {}", what, coordinates(tx.getBody().getReferenceInputs()));
        log.info("{} witness-set script hashes: {}", what, witnessScriptHashes(tx));
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            log.info("{} output {}: {} -> {}", what, i, outputs.get(i).getAddress(),
                    describe(outputs.get(i)));
        }
    }

    /**
     * Every Plutus script hash the finished witness set carries — the other half of the phase-1 rejection
     * of 2026-08-18, where the create carried {@code pool.pool} ({@code 65a0bc5e…}) both here and on a
     * reference input and the node answered {@code ExtraneousScriptWitnessesUTXOW}. On the submittable
     * path every validator travels by reference input, so this must print {@code none} for all three
     * phases; {@link LoanFactory}'s {@code LEDGER_PREFLIGHT} gate has already refused the body otherwise.
     */
    private static String witnessScriptHashes(Transaction tx) {
        if (tx.getWitnessSet() == null || tx.getWitnessSet().getPlutusV3Scripts() == null
                || tx.getWitnessSet().getPlutusV3Scripts().isEmpty()) {
            return "none";
        }
        return tx.getWitnessSet().getPlutusV3Scripts().stream()
                .map(script -> {
                    try {
                        return HexUtil.encodeHexString(script.getScriptHash());
                    } catch (Exception e) {
                        return "unhashable(" + e + ")";
                    }
                })
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    /**
     * The declared budget beside the measured one. With the evaluator wired (see the class javadoc) the
     * declared numbers are real, and {@link LoanFactory}'s {@code EX_UNITS_GATE} has already refused the
     * body if any of them fell short — so anything printed here has passed that gate.
     * <p>
     * <b>Expect the two columns to differ in the last few digits of {@code steps}, and only there.</b>
     * {@code MEASURED} comes from {@link EvalFixtures}, whose PlutusV3 cost model is the one bundled with
     * cardano-client-lib; {@code DECLARED} was written by an evaluator running under preview's own live
     * cost model. Measured 2026-08-18, the gap is ~0.003–0.006% of {@code steps} and exactly zero on
     * {@code mem}. The ledger charges under the chain's model, which is the one the gate judges against
     * ({@link LoanFactory#measureBudgets}), so this residual is expected rather than a shortfall.
     */
    private void logExUnits(String what, Transaction tx, List<Utxo> universe,
                            List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> extra) {
        List<Redeemer> redeemers = tx.getWitnessSet().getRedeemers();
        if (redeemers == null || redeemers.isEmpty()) {
            return;
        }
        for (Redeemer redeemer : redeemers) {
            log.info("{} DECLARED ex-units: tag={} index={} mem={} steps={}", what, redeemer.getTag(),
                    redeemer.getIndex(), redeemer.getExUnits().getMem(), redeemer.getExUnits().getSteps());
        }
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(tx, universe, REGISTRY, extra);
        if (!outcome.successful()) {
            log.warn("{} MEASURED ex-units unavailable: {}", what, outcome.detail());
            return;
        }
        for (EvaluationResult result : outcome.results()) {
            log.info("{} MEASURED ex-units: tag={} index={} mem={} steps={}", what,
                    result.getRedeemerTag(), result.getIndex(),
                    result.getExUnits().getMem(), result.getExUnits().getSteps());
        }
    }

    /**
     * The UTxO set the measurement resolves the finished body against: spend and collateral inputs
     * through {@code supplier}, reference inputs from {@code known} (the hash-bearing reference-script
     * coordinates and the config) falling back to the supplier.
     */
    private static List<Utxo> universeOf(Transaction tx, UtxoSupplier supplier, List<Utxo> known) {
        Map<String, Utxo> byCoordinate = new LinkedHashMap<>();
        for (Utxo utxo : known) {
            byCoordinate.put(utxo.getTxHash() + "#" + utxo.getOutputIndex(), utxo);
        }

        List<TransactionInput> all = new ArrayList<>(tx.getBody().getInputs());
        if (tx.getBody().getCollateral() != null) {
            all.addAll(tx.getBody().getCollateral());
        }
        if (tx.getBody().getReferenceInputs() != null) {
            all.addAll(tx.getBody().getReferenceInputs());
        }

        List<Utxo> universe = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TransactionInput input : all) {
            String key = input.getTransactionId() + "#" + input.getIndex();
            if (!seen.add(key)) {
                continue;
            }
            Utxo resolved = byCoordinate.get(key);
            if (resolved == null) {
                resolved = supplier.getTxOutput(input.getTransactionId(), input.getIndex())
                        .orElseThrow(() -> new IllegalStateException("cannot resolve " + key));
            }
            universe.add(resolved);
        }
        return universe;
    }

    /** The construction supplier, wrapped so it also resolves the pool UTxO. */
    private static UtxoSupplier resolving(UtxoSupplier delegate, Utxo poolUtxo) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return delegate.getPage(address, nrOfItems, page, order);
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                if (poolUtxo.getTxHash().equals(txHash) && poolUtxo.getOutputIndex() == outputIndex) {
                    return Optional.of(poolUtxo);
                }
                return delegate.getTxOutput(txHash, outputIndex);
            }
        };
    }

    private List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> borrowExtraScripts() {
        return List.of(REGISTRY.getPoolScript(), REGISTRY.getPoolSpendScript(),
                REGISTRY.getPoolBorrowActionScript(), REGISTRY.getLoanScript(),
                REGISTRY.getLenderBondScript(), REGISTRY.getBorrowerBondScript());
    }

    private List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> cancelExtraScripts() {
        return List.of(REGISTRY.getPoolSpendScript(), REGISTRY.getPoolScript(),
                REGISTRY.getPoolCancelActionScript());
    }

    // ==============================================================================================
    // Small helpers
    // ==============================================================================================

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }

    private static List<Utxo> concat(List<Utxo> a, List<Utxo> b) {
        List<Utxo> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static boolean sameCoordinate(Utxo a, Utxo b) {
        return a.getTxHash().equals(b.getTxHash()) && a.getOutputIndex() == b.getOutputIndex();
    }

    private static BigInteger lovelaceOf(Utxo utxo) {
        return quantityOf(utxo, "lovelace");
    }

    private static BigInteger quantityOf(Utxo utxo, String unit) {
        return utxo.getAmount().stream()
                .filter(a -> unit.equalsIgnoreCase(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static String coordinates(List<TransactionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return "none";
        }
        return inputs.stream()
                .map(i -> i.getTransactionId() + "#" + i.getIndex())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    private static String describe(TransactionOutput output) {
        StringBuilder value = new StringBuilder(output.getValue().getCoin() + " lovelace");
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            for (Asset asset : multiAsset.getAssets()) {
                value.append(" + ").append(asset.getValue()).append(' ')
                        .append(multiAsset.getPolicyId())
                        .append(strip(asset.getNameAsHex()));
            }
        }
        return value.toString();
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
