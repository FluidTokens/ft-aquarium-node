package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Leg;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Signer;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-223: the recorded mainnet NIGHT→USDM convert — the first convert fixture with a TOKEN principal, so the
 * first in which {@code loan_claim_action} reads a second oracle leg.
 *
 * <h2>Recorded, and hypothetical</h2>
 * The loan {@code 328e44f9…#1} (4,000 NIGHT collateral, 108.77 USDM principal), its lender bond {@code …#3},
 * the NIGHT/USDM Minswap V2 pool and the 2026-10-23 / 2026-10-26 config datums are recorded mainnet data, at
 * their recorded coordinates, addresses and values ({@code mainnet-token-to-token-convert.PROVENANCE.md}). The
 * instant and the prices are not: the scenario is anchored at {@link #T0}, after every recorded UTxO, and both
 * feeds are signed at runtime by two {@link TestOracleRig}s — FluidTokens' oracle code (control-hashed to the
 * deployed NIGHT v2 oracle {@code 756897fd}) re-parameterised with keys generated in memory, never by the oracle
 * operator. The collateral leg is the NIGHT v2 NFT and the principal leg the USDM v2 NFT, each from its OWN rig,
 * so the two legs withdraw from two distinct credentials, as on mainnet.
 *
 * <h2>The knobs</h2>
 * {@link Spec}: each leg's signed feed (price and window) and the tx window independently; the feeds the plan and
 * the claim are computed at; either leg's entry replaced; the principal declared ada with no entry (the shape
 * the builder built before FAB-223); the plan at an equity different from the claim's; one rig making both legs.
 * Test-only; it carries no {@code @Test}.
 */
final class ConvertTokenToTokenFixture {

    static final Network NETWORK = Networks.mainnet();
    static final String REGISTRY_PAYLOAD = "/loans-v4/mainnet-oracle-registry-2026-10-01.json";

    /** 2026-11-13T00:33:20Z: after every recorded UTxO. A hypothetical instant. */
    static final long T0 = 1_794_530_000_000L;

    // ---- the shipped mainnet deployment (ConvertProductionWiringTest's coordinates) ------------------

    static final String CONFIG_POLICY_ID = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    static final String LM_CONFIG_POLICY_ID = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    static final LoansContractRegistry REGISTRY = new LoansContractRegistry(CONFIG_POLICY_ID,
            LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND, MS_POOL_POLICY, MS_POOL_SPEND,
            MS_ORDER_SPEND);

    // ---- the recorded candidate (PROVENANCE) --------------------------------------------------------

    static final String LOAN_TX = "328e44f99600ee3ba60450054685a4400716112af4a92ed0c56c00724635de59";
    static final int LOAN_IX = 1;
    static final int BOND_IX = 3;
    static final String LOAN_ID = "04d78e6a50e4969ca8d8b164fe5796ce7a91ee85a5e0fab09bfaede6";
    static final String LOAN_ADDRESS =
            "addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfpexr32us7n8gzrgqt7d7ar570gw5vmyy9xsak7xqht9tsafqtg0";
    static final String BOND_ADDRESS =
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczwldyrcklqdeynrlhty9wn6wehlr78jcmnmrnqnnt57mqqqs07924";
    static final AssetType NIGHT = new AssetType("0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa",
            "4e49474854");
    static final AssetType USDM = new AssetType("c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad",
            "0014df105553444d");
    static final AssetType NIGHT_V2_NFT = new AssetType("26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26",
            "6f7261636c654e69676874");
    static final AssetType USDM_V2_NFT = new AssetType("26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26",
            "6f7261636c655553444d4333");
    static final BigInteger COLLATERAL_AMOUNT = BigInteger.valueOf(4_000_000_000L);
    static final BigInteger LOAN_LOVELACE = BigInteger.valueOf(3_000_000L);
    static final BigInteger BOND_LOVELACE = BigInteger.valueOf(1_969_670L);

    static final TransactionInput POOL_REF = new TransactionInput(
            "5d6a281cebf8d2c168bd446a7120f5ff3f46286ab96106709876c80d513631af", 1);
    static final String POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";
    static final String POOL_LP_ASSET_NAME = "126bbf31110c7232ca88cfc1463ecbc7fe3256ea37876975d0dc42b8f156f6a0";
    /** The pool's recorded value, in full: lovelace, NIGHT, USDM, the pool NFT and the LP tokens. */
    static final List<Amount> POOL_VALUE = List.of(
            Amount.lovelace(BigInteger.valueOf(4_500_000L)),
            Amount.asset(NIGHT.toUnit(), new BigInteger("287017579860")),
            Amount.asset(USDM.toUnit(), new BigInteger("13212989659")),
            Amount.asset(MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, BigInteger.ONE),
            Amount.asset(MS_POOL_POLICY + POOL_LP_ASSET_NAME, new BigInteger("9223371982099091585")));

    /** The live Config / LMConfig holders, re-read unspent and byte-equal on 2026-11-12 (PROVENANCE). */
    static final TransactionInput CONFIG_REF = new TransactionInput(
            "75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5", 0);
    static final String CONFIG_ADDRESS = "addr1wy34kvsypls3wlqrk8f5l67ywpzqcm425g3g48qmpcm4yqqy7cfve";
    static final TransactionInput LM_CONFIG_REF = new TransactionInput(
            "c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36", 0);
    static final String LM_CONFIG_ADDRESS = "addr1w8ak4cszwdvtfg9kyugwh9gs9krl5ylkdm84tky5x6vufysp3u5d6";

    // ---- the bot (synthetic, as in ConvertProductionWiringTest) -----------------------------------

    static final String BOT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("11".repeat(28))), NETWORK).getAddress();
    static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo("e0".repeat(32), 0, BOT_ADDRESS, 50_000_000L);
    /** Wallet role C: ada-only, exactly {@code WalletShape.collateralLovelace} of the rig's params. */
    static final Utxo COLLATERAL_UTXO = LoanFixtures.adaUtxo("c5".repeat(32), 0, BOT_ADDRESS,
            WalletShape.collateralLovelace(EvalFixtures.protocolParams().getProtocolParams()).longValueExact());

    /** The six loans-v4 validators a mainnet convert references, at synthetic {@code a1…}–{@code a6…}. */
    static final List<PlutusScript> REGISTRY_SCRIPTS = List.of(REGISTRY.getLoanSpendScript(),
            REGISTRY.getLenderManagerSpendScript(), REGISTRY.getLoanScript(), REGISTRY.getLoanClaimActionScript(),
            REGISTRY.getLenderManagerScript(), REGISTRY.getLmLiquidateAndConvertActionScript());

    // ---- the rig feeds -----------------------------------------------------------------------------

    static final BigInteger DENOMINATOR = BigInteger.valueOf(100_000_000L);
    static final BigInteger NIGHT_PRICE = BigInteger.valueOf(12_500_000L);
    static final BigInteger USDM_PRICE = BigInteger.valueOf(400_000_000L);
    static final Feed NIGHT_FEED = new Feed(NIGHT_PRICE, DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
    static final Feed USDM_FEED = new Feed(USDM_PRICE, DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
    static final long TX_FROM = T0;
    static final long TX_TO = T0 + 120_000L;

    static final CardanoConverters CONVERTERS = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    /** CCL's placeholder ex-units (trap 8): a redeemer still carrying them was never costed. */
    static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000);

    /** A rig on the deployed NIGHT v2 oracle's code: three runtime keys, at its threshold (1). */
    static TestOracleRig newRig() {
        return TestOracleRig.of(TestOracleRig.controlHashed("756897fd", Blueprint.LOANS_V4), 3);
    }

    // ======================================================================================

    /** The knobs; every one defaults to the positive control. */
    static final class Spec {
        TestOracleRig collateralRig;
        TestOracleRig principalRig;
        Feed collateralFeed = NIGHT_FEED;
        Feed principalFeed = USDM_FEED;
        Feed plannedCollateralFeed;
        Feed plannedPrincipalFeed;
        long txFrom = TX_FROM;
        long txTo = TX_TO;
        UnaryOperator<OracleEntry> collateralEntry = UnaryOperator.identity();
        UnaryOperator<OracleEntry> principalEntry = UnaryOperator.identity();
        boolean principalAsAda;
        BigInteger planEquityDelta = BigInteger.ZERO;

        private Spec(TestOracleRig collateralRig, TestOracleRig principalRig) {
            this.collateralRig = collateralRig;
            this.principalRig = principalRig;
        }

        /** (i) The feed the collateral (NIGHT) rig signs; the plan and claim follow unless overridden. */
        Spec collateralFeed(Feed feed) {
            this.collateralFeed = feed;
            return this;
        }

        /** (i) The feed the principal (USDM) rig signs. */
        Spec principalFeed(Feed feed) {
            this.principalFeed = feed;
            return this;
        }

        /** (i) The collateral feed the plan and the claim are computed at, when it differs from the signed one. */
        Spec plannedCollateralFeed(Feed feed) {
            this.plannedCollateralFeed = feed;
            return this;
        }

        /** (i) The transaction window, in milliseconds. */
        Spec txWindow(long fromMillis, long toMillis) {
            this.txFrom = fromMillis;
            this.txTo = toMillis;
            return this;
        }

        /** (ii) Replace the collateral leg's entry as the Request carries it. */
        Spec collateralEntry(UnaryOperator<OracleEntry> replace) {
            this.collateralEntry = replace;
            return this;
        }

        /** (ii) Replace the principal leg's entry as the Request carries it. */
        Spec principalEntry(UnaryOperator<OracleEntry> replace) {
            this.principalEntry = replace;
            return this;
        }

        /** (iii) Declare the principal as ada with no entry: the single-oracle shape of before FAB-223. */
        Spec principalAsAda() {
            this.principalAsAda = true;
            return this;
        }

        /** (iv) Plan the order at the claim's equity plus {@code delta}. */
        Spec planEquityDelta(long delta) {
            this.planEquityDelta = BigInteger.valueOf(delta);
            return this;
        }

        /** (v) One rig makes both legs: two NFTs, one credential. */
        Spec oneRig() {
            this.principalRig = this.collateralRig;
            return this;
        }

        ConvertTokenToTokenFixture build() throws Exception {
            return new ConvertTokenToTokenFixture(this);
        }
    }

    /** The positive control's spec, with two fresh rigs. */
    static Spec spec() {
        return new Spec(newRig(), newRig());
    }

    /** The positive control's spec, with the given rigs (a same-test positive control shares them). */
    static Spec spec(TestOracleRig collateralRig, TestOracleRig principalRig) {
        return new Spec(collateralRig, principalRig);
    }

    // ======================================================================================

    final TestOracleRig collateralRig;
    final TestOracleRig principalRig;
    final Leg collateralLeg;
    final Leg principalLeg;
    /** The entries the Request carries (after any {@link Spec} replacement). */
    final OracleEntry collateralEntry;
    final OracleEntry principalEntry;
    final LoanDatum loan;
    final LenderManagerDatum bond;
    final MinswapPoolDatum pool;
    final BigInteger debt;
    /** The claim's equity, at the planned feeds. */
    final BigInteger equity;
    final ConvertOrderPlan plan;
    final ClaimData claim;
    final Map<String, TransactionInput> referenceScripts;
    final List<Utxo> universe;
    final ConvertTransactionBuilder.Request request;
    final OraclePriceFeed plannedCollateralFeed;
    final OraclePriceFeed plannedPrincipalFeed;

    private ConvertTokenToTokenFixture(Spec spec) throws Exception {
        String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-328e44f9.hex");
        String bondDatumHex = LoanFixtures.fixture("mainnet-lender-bond-datum-328e44f9.hex");
        String poolDatumHex = LoanFixtures.fixture("mainnet-minswap-pool-night-usdm.hex");
        this.loan = new LoanDatumConverter().deserialize(loanDatumHex);
        this.bond = new LenderManagerDatumConverter().deserialize(bondDatumHex);
        this.pool = new MinswapPoolDatumConverter().deserialize(poolDatumHex);
        assertEquals(NIGHT, loan.collateral().assetType(), "the recorded loan's collateral is NIGHT");
        assertEquals(USDM, loan.principalAsset(), "the recorded loan's principal is USDM");
        assertEquals(NIGHT_V2_NFT, loan.collateral().oracleTokenAsset(), "the loan names the NIGHT v2 oracle");
        assertEquals(USDM_V2_NFT, loan.principalOracleAsset(), "the loan names the USDM v2 oracle");

        this.collateralRig = spec.collateralRig;
        this.principalRig = spec.principalRig;
        this.collateralLeg = collateralRig.leg(REGISTRY_PAYLOAD, NIGHT_V2_NFT, spec.collateralFeed,
                List.of(Signer.at(0)), NETWORK);
        this.principalLeg = principalRig.leg(REGISTRY_PAYLOAD, USDM_V2_NFT, spec.principalFeed,
                List.of(Signer.at(0)), NETWORK);
        assertEquals(NIGHT, collateralLeg.entry().token(), "the NIGHT v2 entry prices NIGHT");
        assertEquals(USDM, principalLeg.entry().token(), "the USDM v2 entry prices USDM");
        this.collateralEntry = spec.collateralEntry.apply(collateralLeg.entry());
        this.principalEntry = spec.principalEntry.apply(principalLeg.entry());

        Feed plannedCollateral = spec.plannedCollateralFeed != null ? spec.plannedCollateralFeed : spec.collateralFeed;
        Feed plannedPrincipal = spec.plannedPrincipalFeed != null ? spec.plannedPrincipalFeed : spec.principalFeed;
        this.plannedCollateralFeed = OraclePriceFeed.aggregated(NIGHT, plannedCollateral.priceInLovelaces(),
                plannedCollateral.priceDenominator(), plannedCollateral.validFrom(), plannedCollateral.validTo());
        this.plannedPrincipalFeed = OraclePriceFeed.aggregated(USDM, plannedPrincipal.priceInLovelaces(),
                plannedPrincipal.priceDenominator(), plannedPrincipal.validFrom(), plannedPrincipal.validTo());

        long validFromSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(spec.txFrom / 1000L, 0,
                ZoneOffset.UTC));
        long validToSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(spec.txTo / 1000L, 0,
                ZoneOffset.UTC));

        // The figures at the tx window's validFrom, at the planned feeds — what loan_claim_action recomputes.
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.liquidationMode();
        this.debt = LoanFinance.remainingDebt(loan, spec.txFrom);
        this.equity = LoanFinance.redeemerEquity(liquidation, Rational.fromInt(COLLATERAL_AMOUNT),
                Rational.fromInt(debt), plannedPrincipalFeed, plannedCollateralFeed);
        AssetType lenderBond = new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID);
        this.plan = ConvertOrderPlan.plan(NIGHT, USDM, COLLATERAL_AMOUNT, equity.add(spec.planEquityDelta), debt,
                bond.liquidationFeePerMille().longValueExact(), bond.shouldLiquidationConvertToPrincipal(),
                liquidation.equityInPrincipalCurrency(), pool, MS_POOL_POLICY, lenderBond, bond.lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(REGISTRY.getAssetManagerSpendScriptHash()), LOAN_TX, LOAN_IX);
        this.claim = new ClaimData(liquidation, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                bond.lenderAuth(), equity, LOAN_ID, debt);

        // ---- the UTxO set: recorded addresses and values -----------------------------------------
        Utxo loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_IX, LOAN_ADDRESS,
                List.of(Amount.lovelace(LOAN_LOVELACE), Amount.asset(NIGHT.toUnit(), COLLATERAL_AMOUNT),
                        Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), loanDatumHex);
        Utxo bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_IX, BOND_ADDRESS,
                List.of(Amount.lovelace(BOND_LOVELACE),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), bondDatumHex);
        Utxo configUtxo = LoanFixtures.utxo(CONFIG_REF.getTransactionId(), CONFIG_REF.getIndex(), CONFIG_ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(4_249_660L)),
                        Amount.asset(CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-config-datum-2026-10-23.hex"));
        Utxo lmConfigUtxo = LoanFixtures.utxo(LM_CONFIG_REF.getTransactionId(), LM_CONFIG_REF.getIndex(),
                LM_CONFIG_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_150_690L)),
                        Amount.asset(LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-lm-config-datum-2026-10-26.hex"));
        Utxo poolUtxo = LoanFixtures.utxo(POOL_REF.getTransactionId(), POOL_REF.getIndex(), POOL_ADDRESS,
                POOL_VALUE, poolDatumHex);

        List<Utxo> all = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo, poolUtxo,
                WALLET_UTXO, COLLATERAL_UTXO));
        // Each rig leg's NFT holder and reference-script UTxO (the script's HASH, never its bytes). One rig
        // making both legs shares its script holder: added once.
        for (Utxo u : List.of(collateralLeg.referenceInputUtxo(), collateralLeg.referenceScriptUtxo(),
                principalLeg.referenceInputUtxo(), principalLeg.referenceScriptUtxo())) {
            if (all.stream().noneMatch(x -> x.getTxHash().equals(u.getTxHash())
                    && x.getOutputIndex() == u.getOutputIndex())) {
                all.add(u);
            }
        }
        Map<String, TransactionInput> scripts = new LinkedHashMap<>();
        for (int i = 0; i < REGISTRY_SCRIPTS.size(); i++) {
            String hash = hashOf(REGISTRY_SCRIPTS.get(i));
            TransactionInput at = new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
            scripts.put(hash, at);
            all.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        this.referenceScripts = Map.copyOf(scripts);
        this.universe = List.copyOf(all);

        String orderAddress = ConvertLiquidationRouter.minswapOrderAddress(MS_ORDER_SPEND,
                bond.lenderStakeCredential(), LOAN_ID, NETWORK);
        this.request = new ConvertTransactionBuilder.Request(loanUtxo, bondUtxo, poolUtxo, collateralEntry,
                configUtxo, lmConfigUtxo, WALLET_UTXO, scripts, plan, claim, NIGHT, lenderBond,
                loan.repaymentReceipts(), orderAddress, BOT_ADDRESS, validFromSlot, validToSlot, COLLATERAL_UTXO,
                spec.principalAsAda ? AssetType.ada() : USDM, spec.principalAsAda ? null : principalEntry);
    }

    // ---- the leaves ---------------------------------------------------------------------------------

    /** The rig scripts, distinct by hash (one rig making both legs has one). */
    List<PlutusScript> rigScripts() {
        return collateralRig == principalRig ? List.of(collateralRig.script())
                : List.of(collateralRig.script(), principalRig.script());
    }

    /** Every script the transaction runs, served by hash: the registry's and the rigs'. */
    ScriptSupplier scripts() {
        return EvalFixtures.scriptSupplier(REGISTRY, rigScripts());
    }

    /** Production's script-supplier shape over {@link #scripts()}. */
    HashCheckedScriptSupplier hashCheckedScripts() {
        return new HashCheckedScriptSupplier(scripts());
    }

    /** The real Aiken evaluator, mainnet slot config, over this universe and {@link #scripts()}. */
    AikenTransactionEvaluator aiken() {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                scripts(), SlotConfigs.mainnet());
    }

    /** The 6-argument production constructor, with only its leaves supplied. */
    ConvertTransactionBuilder production(TransactionEvaluator evaluator) {
        return new ConvertTransactionBuilder(REGISTRY, NETWORK, LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(), hashCheckedScripts(), evaluator);
    }

    /** The 7-argument production constructor, with a probe. */
    ConvertTransactionBuilder production(TransactionEvaluator evaluator, OracleReferenceInputProbe probe) {
        return new ConvertTransactionBuilder(REGISTRY, NETWORK, LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(), hashCheckedScripts(), evaluator, probe);
    }

    /** The bytes the six registry scripts reference, as cardano-client-lib measures them. */
    static long registryScriptBytes() throws Exception {
        long bytes = 0;
        for (PlutusScript script : REGISTRY_SCRIPTS) {
            bytes += script.scriptRefBytes().length;
        }
        return bytes;
    }

    Utxo utxoAt(TransactionInput input) {
        return universe.stream()
                .filter(u -> u.getTxHash().equals(input.getTransactionId()) && u.getOutputIndex() == input.getIndex())
                .findFirst()
                .orElseThrow(() -> new AssertionError("an input outside the universe: " + input));
    }

    /**
     * CCL trap 26 — per-asset conservation on a built body: for every unit, inputs + withdrawals + mint ==
     * outputs (+ fee, for lovelace), and no output carries a negative quantity.
     */
    void assertConservation(Transaction transaction) {
        Map<String, BigInteger> in = new TreeMap<>();
        Map<String, BigInteger> out = new TreeMap<>();
        for (TransactionInput input : transaction.getBody().getInputs()) {
            for (Amount amount : utxoAt(input).getAmount()) {
                in.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add);
            }
        }
        if (transaction.getBody().getWithdrawals() != null) {
            for (Withdrawal w : transaction.getBody().getWithdrawals()) {
                in.merge("lovelace", w.getCoin(), BigInteger::add);
            }
        }
        if (transaction.getBody().getMint() != null) {
            for (MultiAsset ma : transaction.getBody().getMint()) {
                for (Asset asset : ma.getAssets()) {
                    in.merge(ma.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()), asset.getValue(),
                            BigInteger::add);
                }
            }
        }
        for (TransactionOutput output : transaction.getBody().getOutputs()) {
            assertTrue(output.getValue().getCoin().signum() >= 0, "a negative lovelace output: " + output);
            out.merge("lovelace", output.getValue().getCoin(), BigInteger::add);
            if (output.getValue().getMultiAssets() != null) {
                for (MultiAsset ma : output.getValue().getMultiAssets()) {
                    for (Asset asset : ma.getAssets()) {
                        assertTrue(asset.getValue().signum() >= 0, "a negative quantity in an output: " + output);
                        out.merge(ma.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()),
                                asset.getValue(), BigInteger::add);
                    }
                }
            }
        }
        out.merge("lovelace", transaction.getBody().getFee(), BigInteger::add);
        in.values().removeIf(v -> v.signum() == 0);
        out.values().removeIf(v -> v.signum() == 0);
        assertEquals(in, out, "per-asset conservation (inputs + withdrawals + mint == outputs + fee)");
    }

    /** The quantity of {@code asset} in {@code output}. */
    static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        if (output.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return output.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** The distinct units an output holds, lovelace included when positive. */
    static Set<String> unitsOf(TransactionOutput output) {
        Set<String> units = new java.util.TreeSet<>();
        if (output.getValue().getCoin().signum() > 0) {
            units.add("lovelace");
        }
        if (output.getValue().getMultiAssets() != null) {
            for (MultiAsset ma : output.getValue().getMultiAssets()) {
                for (Asset asset : ma.getAssets()) {
                    units.add(ma.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()));
                }
            }
        }
        return units;
    }

    /**
     * The position of {@code credentialHash}'s withdrawal in {@code transaction}'s (canonically sorted)
     * withdrawals — the index the evaluator names that withdrawal's redeemer by (trap 14). Mirrors
     * {@code TestOracleRigTest.withdrawalIndex}.
     */
    static int withdrawalIndex(Transaction transaction, String credentialHash) {
        List<Withdrawal> withdrawals = transaction.getBody().getWithdrawals();
        int found = -1;
        for (int i = 0; i < withdrawals.size(); i++) {
            String credential = HexUtil.encodeHexString(new Address(withdrawals.get(i).getRewardAddress())
                    .getDelegationCredentialHash().orElseThrow());
            if (credential.equalsIgnoreCase(credentialHash)) {
                assertEquals(-1, found, "two withdrawals keyed to " + credentialHash);
                found = i;
            }
        }
        assertTrue(found >= 0, "no withdrawal keyed to " + credentialHash);
        return found;
    }

    /**
     * The refusal came from the SCRIPT whose withdrawal sits at {@code index}: the evaluator's
     * {@code RedeemerError} for that withdraw purpose, somewhere in the cause chain. Mirrors
     * {@code TestOracleRigTest.assertRefusedAt}.
     */
    static void assertRefusedAt(Throwable refused, int index, String label) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null; t = t.getCause()) {
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
            if (t.getCause() == t) {
                break;
            }
        }
        String expected = "RedeemerError { tag: \"Withdraw\", index: " + index + ",";
        assertTrue(chain.toString().contains(expected),
                label + ": not refused by the withdrawal at index " + index + " (" + expected + "): " + chain);
    }

    static String entAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), NETWORK)
                .getAddress();
    }

    static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash a script", e);
        }
    }

    /** Records how often it was asked, every body it was asked about, and what it last returned. */
    static final class Recording implements TransactionEvaluator {
        final TransactionEvaluator delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<EvaluationResult>> last = new AtomicReference<>();
        final List<byte[]> attempts = new CopyOnWriteArrayList<>();

        Recording(TransactionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, java.util.Set<Utxo> inputUtxos)
                throws com.bloxbean.cardano.client.api.exception.ApiException {
            calls.incrementAndGet();
            attempts.add(cbor);
            Result<List<EvaluationResult>> result = delegate.evaluateTx(cbor, inputUtxos);
            last.set(result.getValue());
            return result;
        }
    }

    // ======================================================================================
    // FAB-223-2: what a real executor cycle needs beyond the builder's leaves
    // ======================================================================================

    /**
     * The recorded USDM/STRIKE Minswap V2 pool (PROVENANCE, "STRIKE/USDM (POOL_TOO_THIN)"): asset_a USDM, asset_b
     * STRIKE, at the NIGHT/USDM pool's address.
     */
    static final TransactionInput USDM_STRIKE_POOL_REF = new TransactionInput(
            "8a25925a77299042c98aa91f7e061dfd395d9d1dfe01b8b4d38a7add2230ed90", 1);
    static final String USDM_STRIKE_POOL_LP_ASSET_NAME =
            "a0b3b8315e895f4fd22b780c24e2c87e3345e67f7fa99b408274ed8f6a4aa20d";
    /** The pool's recorded value, in full: lovelace, USDM, STRIKE, the pool NFT and the LP tokens. */
    static final List<Amount> USDM_STRIKE_POOL_VALUE = List.of(
            Amount.lovelace(BigInteger.valueOf(4_500_000L)),
            Amount.asset(USDM.toUnit(), BigInteger.valueOf(7_606_291L)),
            Amount.asset(StrikeUsdmFixture.STRIKE.toUnit(), BigInteger.valueOf(6_471_257L)),
            Amount.asset(MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, BigInteger.ONE),
            Amount.asset(MS_POOL_POLICY + USDM_STRIKE_POOL_LP_ASSET_NAME, new BigInteger("9223372036854775807")));

    /** The recorded NIGHT/USDM pool UTxO: recorded coordinate, address, full value and datum. */
    static Utxo nightUsdmPoolUtxo() {
        return LoanFixtures.utxo(POOL_REF.getTransactionId(), POOL_REF.getIndex(), POOL_ADDRESS, POOL_VALUE,
                LoanFixtures.fixture("mainnet-minswap-pool-night-usdm.hex"));
    }

    /** The recorded USDM/STRIKE pool UTxO: recorded coordinate, address, full value and datum. */
    static Utxo usdmStrikePoolUtxo() {
        return LoanFixtures.utxo(USDM_STRIKE_POOL_REF.getTransactionId(), USDM_STRIKE_POOL_REF.getIndex(),
                POOL_ADDRESS, USDM_STRIKE_POOL_VALUE, LoanFixtures.fixture("mainnet-minswap-pool-usdm-strike.hex"));
    }

    /** A pool UTxO as the index row {@link MinswapPoolResolver} reads: its coordinate, address, datum and value. */
    static AddressUtxoEntity poolIndexRow(Utxo pool) {
        var row = new AddressUtxoEntity();
        row.setTxHash(pool.getTxHash());
        row.setOutputIndex(pool.getOutputIndex());
        row.setOwnerAddr(pool.getAddress());
        row.setOwnerPaymentCredential(MS_POOL_SPEND);
        row.setInlineDatum(pool.getInlineDatum());
        List<Amt> amounts = new ArrayList<>();
        for (Amount amount : pool.getAmount()) {
            var amt = new Amt();
            amt.setUnit(amount.getUnit());
            amt.setQuantity(amount.getQuantity());
            amounts.add(amt);
        }
        row.setAmounts(amounts);
        return row;
    }

    /** The six registry reference-script UTxOs at the synthetic {@code a1…}–{@code a6…}, as the universe holds them. */
    static List<Utxo> referenceScriptUtxos() {
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < REGISTRY_SCRIPTS.size(); i++) {
            String hash = hashOf(REGISTRY_SCRIPTS.get(i));
            TransactionInput at = new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
            utxos.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        return utxos;
    }

    /** The same six coordinates in the {@code loans.liquidation.reference-scripts.*} slots, convert action included. */
    static LiquidateTransactionBuilder.ReferenceScripts referenceScriptSlots() {
        Map<String, TransactionInput> at = new LinkedHashMap<>();
        for (Utxo utxo : referenceScriptUtxos()) {
            at.put(utxo.getReferenceScriptHash(), new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex()));
        }
        return new LiquidateTransactionBuilder.ReferenceScripts(at.get(REGISTRY.getLoanPolicyId()),
                at.get(REGISTRY.getLoanSpendScriptHash()), at.get(REGISTRY.getLenderManagerWithdrawScriptHash()),
                at.get(REGISTRY.getLenderManagerSpendScriptHash()), at.get(REGISTRY.getLoanClaimActionScriptHash()),
                null, null, null, at.get(REGISTRY.getLmLiquidateAndConvertActionScriptHash()));
    }

    /**
     * This universe with its synthetic bot wallet ({@link #WALLET_UTXO}, {@link #COLLATERAL_UTXO}) replaced by
     * {@code wallet}: an executor lists its own wallet, whose role C sits at the same {@code c5…#0} out-ref.
     */
    List<Utxo> universeWith(List<Utxo> wallet) {
        List<Utxo> all = new ArrayList<>();
        for (Utxo utxo : universe) {
            if (utxo != WALLET_UTXO && utxo != COLLATERAL_UTXO) {
                all.add(utxo);
            }
        }
        all.addAll(wallet);
        return List.copyOf(all);
    }
}
