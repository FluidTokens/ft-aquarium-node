package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
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
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Leg;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Signer;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.BOT_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.COLLATERAL_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONFIG_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONFIG_ASSET_NAME;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONFIG_POLICY_ID;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONFIG_REF;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONVERTERS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.LM_CONFIG_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.LM_CONFIG_POLICY_ID;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.LM_CONFIG_REF;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_ORDER_SPEND;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_POOL_POLICY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.NETWORK;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.POOL_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY_PAYLOAD;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.USDM;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.USDM_V2_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.WALLET_UTXO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-224: the recorded mainnet ADA→USDM convert — the first convert fixture with an ADA collateral, so the
 * first in which the collateral is an ada leg, the equity output holds ada only and the build is paid from the
 * loan's own lovelace, with no wallet input.
 *
 * <h2>Recorded, and hypothetical</h2>
 * The loan {@code 2d067dae…#1} (8,100 ADA collateral, 551 USDM principal), its lender bond {@code …#3}, the
 * ADA/USDM Minswap V2 pool and the 2026-10-23 / 2026-10-26 config datums are recorded mainnet data, at their recorded
 * coordinates, addresses and values ({@code mainnet-ada-collateral-convert.PROVENANCE.md}). The instant and the price
 * are not: the scenario is anchored at {@link #T0}, after every recorded UTxO, and the USDM feed is signed at runtime
 * by a {@link TestOracleRig} — FluidTokens' v2 oracle code (control-hashed to the deployed {@code 756897fd}, the same
 * v2 code as the deployed USDM v2 oracle {@code df6f292e}) re-parameterised with keys generated in memory, never by the
 * oracle operator. The collateral is ada and has no oracle leg, so there is one rig and one leg.
 *
 * <h2>The knobs</h2>
 * {@link Spec}: the signed USDM feed (price and window) and the planned one independently, and the tx window; the
 * bond's liquidation fee per mille (a SYNTHETIC bond, derived by replacing the recorded datum's single {@code 1828});
 * the wallet utxo handed to the Request ({@code null} by default — the universe always holds {@code WALLET_UTXO});
 * the plan at an equity different from the claim's; an oracle entry handed to the ada collateral leg. Test-only; it
 * carries no {@code @Test}.
 */
final class ConvertAdaCollateralFixture {

    /** 2026-11-13T03:20:00Z: after every recorded UTxO. A hypothetical instant. */
    static final long T0 = 1_794_540_000_000L;

    // ---- the recorded candidate (PROVENANCE) --------------------------------------------------------

    static final String LOAN_TX = "2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c";
    static final int LOAN_IX = 1;
    static final int BOND_IX = 3;
    static final String LOAN_ID = "441301d34a34125e10f0ea9bd01c5f08dfe5b91d3c2c3101508430fd";
    static final String LOAN_ADDRESS =
            "addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfd3m9rcvrnrasmk5qw7wzn2u995ensxshafrmy666kdkqsmrd6hj";
    static final String BOND_ADDRESS =
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczfg9cfhunxjkr3vjanr2tmgvkq0l0hlau7cggjmsm0knwws7g0e4k";
    /** The whole lovelace of the loan UTxO: an ADA collateral is {@code quantity_of(value, "", "")}. */
    static final BigInteger COLLATERAL_AMOUNT = BigInteger.valueOf(8_100_000_000L);
    static final BigInteger BOND_LOVELACE = BigInteger.valueOf(1_969_670L);
    /** The recorded bond's {@code liquidationFeePerMille}, 40, as the one CBOR integer the datum carries it in. */
    static final String BOND_FEE_ANCHOR = "1828";

    static final TransactionInput POOL_REF = new TransactionInput(
            "e2e08f2e28b118ad4cfdf06f94581652e51ba67749e59b94361e0ab04fb15459", 1);
    static final String POOL_LP_ASSET_NAME = "7dd6988c5a86693c76aeec1ea94afa41770be0de21a775ca7a2a1eabdb6a0171";
    /** The pool's recorded value, in full: lovelace, USDM, the pool NFT and the LP tokens. */
    static final List<Amount> POOL_VALUE = List.of(
            Amount.lovelace(new BigInteger("2020169734229")),
            Amount.asset(USDM.toUnit(), new BigInteger("351296019357")),
            Amount.asset(MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, BigInteger.ONE),
            Amount.asset(MS_POOL_POLICY + POOL_LP_ASSET_NAME, new BigInteger("9223371398395780719")));

    // ---- the rig feed -------------------------------------------------------------------------------

    static final BigInteger USDM_PRICE = BigInteger.valueOf(1_250_000_000L);
    static final Feed USDM_FEED = new Feed(USDM_PRICE, DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
    static final long TX_FROM = T0;
    static final long TX_TO = T0 + 120_000L;

    // ======================================================================================

    /** The knobs; every one defaults to the positive control. */
    static final class Spec {
        TestOracleRig rig;
        Feed usdmFeed = USDM_FEED;
        Feed plannedUsdmFeed;
        long txFrom = TX_FROM;
        long txTo = TX_TO;
        Long bondFeePerMille;
        Utxo walletUtxo;
        BigInteger planEquityDelta = BigInteger.ZERO;
        boolean collateralHandedPrincipalEntry;

        private Spec(TestOracleRig rig) {
            this.rig = rig;
        }

        /** (i) The feed the USDM rig signs; the plan and claim follow unless overridden. */
        Spec usdmFeed(Feed feed) {
            this.usdmFeed = feed;
            return this;
        }

        /** (i) The USDM feed the plan and the claim are computed at, when it differs from the signed one. */
        Spec plannedUsdmFeed(Feed feed) {
            this.plannedUsdmFeed = feed;
            return this;
        }

        /** (i) The transaction window, in milliseconds. */
        Spec txWindow(long fromMillis, long toMillis) {
            this.txFrom = fromMillis;
            this.txTo = toMillis;
            return this;
        }

        /** (ii) A SYNTHETIC bond at {@code perMille}: the recorded datum with its single {@code 1828} replaced. */
        Spec bondFeePerMille(long perMille) {
            this.bondFeePerMille = perMille;
            return this;
        }

        /** (iii) The wallet utxo the Request carries ({@code null}: no wallet input at all). */
        Spec walletUtxo(Utxo utxo) {
            this.walletUtxo = utxo;
            return this;
        }

        /** (iv) Plan the order at the claim's equity plus {@code delta}. */
        Spec planEquityDelta(long delta) {
            this.planEquityDelta = BigInteger.valueOf(delta);
            return this;
        }

        /** (v) Hand the ada collateral leg the principal leg's oracle entry. */
        Spec collateralHandedPrincipalEntry() {
            this.collateralHandedPrincipalEntry = true;
            return this;
        }

        ConvertAdaCollateralFixture build() throws Exception {
            return new ConvertAdaCollateralFixture(this);
        }
    }

    /** The positive control's spec, with a fresh rig. */
    static Spec spec() {
        return new Spec(ConvertTokenToTokenFixture.newRig());
    }

    /** The positive control's spec, with the given rig (a same-test positive control shares it). */
    static Spec spec(TestOracleRig rig) {
        return new Spec(rig);
    }

    // ======================================================================================

    final TestOracleRig rig;
    final Leg leg;
    /** The principal (USDM) leg's entry, as the Request carries it. */
    final OracleEntry principalEntry;
    final LoanDatum loan;
    final LenderManagerDatum bond;
    final MinswapPoolDatum pool;
    final BigInteger debt;
    /** The claim's equity, at the planned feed. */
    final BigInteger equity;
    final ConvertOrderPlan plan;
    final ClaimData claim;
    final Map<String, TransactionInput> referenceScripts;
    final List<Utxo> universe;
    final ConvertTransactionBuilder.Request request;
    final OraclePriceFeed plannedPrincipalFeed;
    final Utxo loanUtxo;
    final Utxo bondUtxo;
    final Utxo poolUtxo;

    private ConvertAdaCollateralFixture(Spec spec) throws Exception {
        String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-2d067dae.hex");
        String recordedBondHex = LoanFixtures.fixture("mainnet-lender-bond-datum-2d067dae.hex");
        String poolDatumHex = LoanFixtures.fixture("mainnet-minswap-pool-ada-usdm.hex");
        this.loan = new LoanDatumConverter().deserialize(loanDatumHex);
        this.pool = new MinswapPoolDatumConverter().deserialize(poolDatumHex);
        assertEquals(AssetType.ada(), loan.collateral().assetType(), "the recorded loan's collateral is ADA");
        assertEquals(USDM, loan.principalAsset(), "the recorded loan's principal is USDM");
        assertEquals(USDM_V2_NFT, loan.principalOracleAsset(), "the loan names the USDM v2 oracle");
        assertEquals(AssetType.ada(), pool.assetA(), "the pool's asset_a is ada");
        assertEquals(USDM, pool.assetB(), "the pool's asset_b is USDM");
        assertEquals(POOL_LP_ASSET_NAME, ConvertTxEncoder.computeLpAssetName(AssetType.ada(), USDM),
                "the recorded LP asset name is the ADA/USDM one");

        LenderManagerDatumConverter bondConverter = new LenderManagerDatumConverter();
        LenderManagerDatum recordedBond = bondConverter.deserialize(recordedBondHex);
        assertEquals(BigInteger.valueOf(40), recordedBond.liquidationFeePerMille(), "the recorded bond's fee is 40‰");
        String bondDatumHex = spec.bondFeePerMille == null ? recordedBondHex
                : syntheticBondHex(recordedBondHex, spec.bondFeePerMille);
        this.bond = bondConverter.deserialize(bondDatumHex);
        if (spec.bondFeePerMille != null) {
            assertSameBondButTheFee(recordedBond, bond, spec.bondFeePerMille);
        }

        this.rig = spec.rig;
        this.leg = rig.leg(REGISTRY_PAYLOAD, USDM_V2_NFT, spec.usdmFeed, List.of(Signer.at(0)), NETWORK);
        assertEquals(USDM, leg.entry().token(), "the USDM v2 entry prices USDM");
        this.principalEntry = leg.entry();

        Feed planned = spec.plannedUsdmFeed != null ? spec.plannedUsdmFeed : spec.usdmFeed;
        this.plannedPrincipalFeed = OraclePriceFeed.aggregated(USDM, planned.priceInLovelaces(),
                planned.priceDenominator(), planned.validFrom(), planned.validTo());

        long validFromSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(spec.txFrom / 1000L, 0,
                ZoneOffset.UTC));
        long validToSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(spec.txTo / 1000L, 0,
                ZoneOffset.UTC));

        // The figures at the tx window's validFrom, at the planned feed — what loan_claim_action recomputes. The
        // collateral is ada: its feed is the 1:1 unit feed retrieve_oracle_data synthesises for an empty policy id.
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.liquidationMode();
        this.debt = LoanFinance.remainingDebt(loan, spec.txFrom);
        this.equity = LoanFinance.redeemerEquity(liquidation, Rational.fromInt(COLLATERAL_AMOUNT),
                Rational.fromInt(debt), plannedPrincipalFeed, OraclePriceFeed.unit());
        AssetType lenderBond = new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID);
        this.plan = ConvertOrderPlan.plan(AssetType.ada(), USDM, COLLATERAL_AMOUNT, equity.add(spec.planEquityDelta),
                debt, bond.liquidationFeePerMille().longValueExact(), bond.shouldLiquidationConvertToPrincipal(),
                liquidation.equityInPrincipalCurrency(), pool, MS_POOL_POLICY, lenderBond, bond.lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(REGISTRY.getAssetManagerSpendScriptHash()), LOAN_TX, LOAN_IX);
        this.claim = new ClaimData(liquidation, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                bond.lenderAuth(), equity, LOAN_ID, debt);

        // ---- the UTxO set: recorded addresses and values -----------------------------------------
        this.loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_IX, LOAN_ADDRESS,
                List.of(Amount.lovelace(COLLATERAL_AMOUNT),
                        Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), loanDatumHex);
        this.bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_IX, BOND_ADDRESS,
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
        this.poolUtxo = LoanFixtures.utxo(POOL_REF.getTransactionId(), POOL_REF.getIndex(), POOL_ADDRESS,
                POOL_VALUE, poolDatumHex);

        // The universe always holds WALLET_UTXO and C at the bot address — whether or not the Request hands U over —
        // so a build that reached into the wallet would find something to take.
        List<Utxo> all = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo, poolUtxo,
                WALLET_UTXO, COLLATERAL_UTXO, leg.referenceInputUtxo(), leg.referenceScriptUtxo()));
        Map<String, TransactionInput> scripts = new LinkedHashMap<>();
        for (Utxo script : ConvertTokenToTokenFixture.referenceScriptUtxos()) {
            scripts.put(script.getReferenceScriptHash(),
                    new TransactionInput(script.getTxHash(), script.getOutputIndex()));
            all.add(script);
        }
        this.referenceScripts = Map.copyOf(scripts);
        this.universe = List.copyOf(all);

        String orderAddress = ConvertLiquidationRouter.minswapOrderAddress(MS_ORDER_SPEND,
                bond.lenderStakeCredential(), LOAN_ID, NETWORK);
        this.request = new ConvertTransactionBuilder.Request(loanUtxo, bondUtxo, poolUtxo,
                spec.collateralHandedPrincipalEntry ? principalEntry : null, configUtxo, lmConfigUtxo,
                spec.walletUtxo, scripts, plan, claim, AssetType.ada(), lenderBond, loan.repaymentReceipts(),
                orderAddress, BOT_ADDRESS, validFromSlot, validToSlot, COLLATERAL_UTXO, USDM, principalEntry);
    }

    /**
     * The recorded bond's hex with its one {@code 1828} ({@code liquidationFeePerMille} 40) replaced by the CBOR
     * encoding of {@code perMille}. SYNTHETIC: no such bond was recorded.
     */
    static String syntheticBondHex(String recordedHex, long perMille) {
        int first = recordedHex.indexOf(BOND_FEE_ANCHOR);
        assertTrue(first >= 0 && recordedHex.indexOf(BOND_FEE_ANCHOR, first + 1) < 0,
                "the recorded bond carries its fee as exactly one " + BOND_FEE_ANCHOR);
        String encoded;
        if (perMille >= 0 && perMille <= 23) {
            encoded = String.format("%02x", perMille);
        } else if (perMille >= 24 && perMille <= 255) {
            encoded = String.format("18%02x", perMille);
        } else {
            throw new IllegalArgumentException("a synthetic bond fee is 0..255 per mille, not " + perMille);
        }
        return recordedHex.substring(0, first) + encoded + recordedHex.substring(first + BOND_FEE_ANCHOR.length());
    }

    /** The synthetic bond decodes to the recorded one in every field but {@code liquidationFeePerMille}. */
    private static void assertSameBondButTheFee(LenderManagerDatum recorded, LenderManagerDatum synthetic,
                                                long perMille) {
        assertEquals(BigInteger.valueOf(perMille), synthetic.liquidationFeePerMille(), "the synthetic fee");
        assertEquals(recorded.lenderAuth(), synthetic.lenderAuth(), "lenderAuth");
        assertEquals(recorded.lenderStakeCredential().serializeToHex(),
                synthetic.lenderStakeCredential().serializeToHex(), "lenderStakeCredential");
        assertEquals(recorded.shouldLiquidationConvertToPrincipal(), synthetic.shouldLiquidationConvertToPrincipal(),
                "shouldLiquidationConvertToPrincipal");
        assertEquals(recorded.poolId(), synthetic.poolId(), "poolId");
        assertEquals(recorded.principalAsset(), synthetic.principalAsset(), "principalAsset");
    }

    // ---- the leaves ---------------------------------------------------------------------------------

    /** Every script the transaction runs, served by hash: the registry's and the rig's. */
    ScriptSupplier scripts() {
        return EvalFixtures.scriptSupplier(REGISTRY, List.of(rig.script()));
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
}
