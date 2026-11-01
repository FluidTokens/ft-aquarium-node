package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.fluidtokens.aquarium.offchain.service.loans.EscrowCompoundRig.ADA;
import static com.fluidtokens.aquarium.offchain.service.loans.EscrowCompoundRig.REGISTRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-168: every escrow the bot's OWN liquidations create, carried through to the compound that would
 * collect it — producer → the REAL {@link CompoundCandidateScanner} → the REAL compound builder → the
 * 2026-10-26 {@code lm_compound_action} ({@code 4713a925…}).
 *
 * <p>{@code lm_compound_action} (upstream {@code a02d4e2}, {@code validators/lender-manager/lm_compound_action.ak}
 * ~:113–165) checks an escrow's SHAPE only at the moment it is compounded: an
 * {@code AssetManagerDatumWithToken} whose {@code ownerAsset} is a lender bond in the transaction, a principal
 * quantity {@code assetAmount > 0}, and a flattened value of length 1 (+receipts) for an ADA principal or 2
 * (+receipts) for a token. A wrong shape written at liquidation time would therefore sit at the asset
 * manager, uncompoundable, with nothing to say so. Each row below either compounds through the new script or
 * is refused by it for the reason its actual value dictates — and the scanner agrees with the validator.
 *
 * <h2>How each half is real</h2>
 * <ul>
 *   <li><b>Producers</b> are the production builders: {@link LiquidatePayInAdvanceTransactionBuilder} (through
 *       {@link LiquidatePayInAdvanceDryEvalTest}'s rig, whose ADA build this class re-evaluates against the
 *       deployed preview validators), {@link LiquidateTransactionBuilder}, and — for convert — the ten
 *       mainnet convert liquidations FluidTokens' third-party operators actually made, whose orders
 *       {@link ConvertOrderPlan} is shown to reproduce byte for byte. The escrow a builder writes is taken
 *       off the BUILT body; nothing here hand-writes an expected escrow.</li>
 *   <li><b>The compound half</b> is {@link EscrowCompoundRig}: FAB-167's production wiring against the shipped
 *       mainnet artefact. Preview-built outputs become mainnet UTxOs by replacing only their payment
 *       credential (value and datum bytes untouched; the lender-bond policy is deployment-independent, which
 *       is asserted). The pool and pool manager are the REAL mainnet pair {@code 1b921dd6…#0} /
 *       {@code 4462a6e4…#1} that the on-chain compound {@code a793085f…} consumed; for a bond naming another
 *       pool their NFTs are re-named (neither datum carries the pool id), and for a token principal the pool
 *       datum's principal is swapped as {@link CompoundMainnetGateTest} does.</li>
 *   <li><b>A validator refusal</b> is isolated by {@link EscrowCompoundRig#refusedByLmCompound}: the same escrow
 *       coordinate and datum with an accepted value compounds; the SAME signed bytes, re-evaluated with the real
 *       value, are refused at {@code lm_compound_action}'s withdrawal. The deployed script carries no traces,
 *       so which {@code expect} fired is read off the value, not off the error.</li>
 * </ul>
 *
 * <p>Fixture provenance: {@code mainnet-convert-settlements-2026-10-26.json} and its PROVENANCE file.
 */
@Slf4j
class LiquidationEscrowCompoundRoundTripTest {

    private static final LoansContractRegistry PREVIEW = LoanFixtures.registry();
    private static final JsonNode SETTLEMENTS = EscrowCompoundRig.json("mainnet-convert-settlements-2026-10-26.json");

    /** The real mainnet pool and pool manager the on-chain compound a793085f… spent (pool id 004c3793…). */
    private static final Utxo REAL_POOL = EscrowCompoundRig.utxo(SETTLEMENTS.get("onChainCompound").get("pool"));
    private static final Utxo REAL_POOL_MANAGER =
            EscrowCompoundRig.utxo(SETTLEMENTS.get("onChainCompound").get("poolManager"));
    private static final String REAL_POOL_ID = "004c37934cdc27ead225f5c576103dc5beae5d11b002e5392ce9feb6e3";

    /** A synthetic native principal and a synthetic native collateral: nothing here depends on which tokens. */
    private static final AssetType PRINCIPAL_TOKEN = new AssetType("c0ffee".repeat(9) + "c0", "5553444d");
    private static final AssetType COLLATERAL_TOKEN = new AssetType("ab".repeat(28), "434f4c4c");
    private static final AssetType COLLATERAL_ORACLE = new AssetType("ac".repeat(28), "434f4c4c");
    private static final AssetType PRINCIPAL_ORACLE = new AssetType("ad".repeat(28), "5553444d");
    private static final String COLLATERAL_ORACLE_CREDENTIAL = "ae".repeat(28);
    private static final String PRINCIPAL_ORACLE_CREDENTIAL = "af".repeat(28);

    private static final String CONVERTED_TO_LIQUIDITY = ConvertTxEncoder.CONVERTED_TO_LIQUIDITY_ACTION;
    private static final String CLAIMED_COLLATERAL = LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX;
    private static final String PARTIAL_LIQUIDATION = LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX;

    // ======================================================================================
    // The rig's own footing
    // ======================================================================================

    /** The real pool pair sits at the shipped mainnet credentials, so it needs no substitution of its own. */
    @Test
    void theRealMainnetPoolPairIsUnderTheShippedCoordinates() {
        assertEquals(REGISTRY.getPoolPolicyId() + REAL_POOL_ID, nftUnder(REAL_POOL, REGISTRY.getPoolPolicyId()));
        assertEquals(REGISTRY.getPoolManagerPolicyId() + REAL_POOL_ID,
                nftUnder(REAL_POOL_MANAGER, REGISTRY.getPoolManagerPolicyId()));
        assertEquals(REGISTRY.getPoolSpendScriptHash(), EscrowCompoundRig.paymentCredential(REAL_POOL.getAddress()));
        assertEquals(REGISTRY.getPoolManagerSpendScriptHash(),
                EscrowCompoundRig.paymentCredential(REAL_POOL_MANAGER.getAddress()));
        assertEquals(0L, CompoundCandidateScanner.compoundingFeePerMille(REAL_POOL_MANAGER),
                "the real pool manager publishes c = 0, so an ADA compound pays exactly the 1 ADA floor");
        assertEquals(PREVIEW.getLenderBondPolicyId(), REGISTRY.getLenderBondPolicyId(),
                "the lender-bond policy is deployment-independent, so a preview-built datum names a mainnet bond");
    }

    // ======================================================================================
    // Row 1 — pay-in-advance, ADA principal
    // ======================================================================================

    @Test
    void row1PayInAdvanceAdaPrincipalEscrowCompoundsThroughTheNewLmCompound() throws Exception {
        LiquidatePayInAdvanceDryEvalTest.Fixture fx = LiquidatePayInAdvanceDryEvalTest.fixture();
        Transaction produced = LiquidatePayInAdvanceDryEvalTest.build(fx);
        // The producer is accepted by the deployed preview validators — the escrow is not from a refused build.
        EvalFixtures.evaluate(produced, LiquidatePayInAdvanceDryEvalTest.universe(fx), PREVIEW,
                List.of(LiquidatePayInAdvanceDryEvalTest.oracleScript()));

        Produced escrow = escrowOf(produced, CONVERTED_TO_LIQUIDITY, fx.loan().loanId());
        assertEquals(Set.of("lovelace"), escrow.units().keySet(), "ADA principal: lovelace only (flatten == 1)");

        EscrowCompoundRig.Parts parts = piaParts(produced, escrow, fx.bondUtxo(), null);
        EscrowCompoundRig.Compounded c = EscrowCompoundRig.compound(parts, "row 1 PIA ADA");
        assertEquals(escrow.units().get("lovelace"), c.q(), "Q is the escrow's entire lovelace");
        assertEquals(ADA, c.fee(), "c = 0 on an ADA pool pays the 1 ADA floor");
        log.info("FAB-168 row 1: PIA ADA escrow Q={} compounded, pool +{}", c.q(), c.poolAdded());
    }

    // ======================================================================================
    // Row 2 — pay-in-advance, native principal
    // ======================================================================================

    @Test
    void row2PayInAdvanceNativePrincipalEscrowCompoundsThroughTheNewLmCompound() throws Exception {
        LiquidatePayInAdvanceDryEvalTest.Fixture fx = LiquidatePayInAdvanceDryEvalTest.fixture();
        var base = fx.request();

        LoanDatum ada = fx.loan().datum();
        LoanDatum tokenDatum = new LoanDatum(ada.doneRecasts(), ada.principalAmount(), ada.lendDate(),
                ada.repaidInstallments(), ada.interestRate(), ada.totalInstallments(), PRINCIPAL_TOKEN,
                PRINCIPAL_ORACLE, ada.installmentPeriod(), ada.initialGracePeriod(), ada.liquidationMode(),
                ada.repaymentMode(), ada.repaymentTimeWindow(), ada.penaltyFeeForLateRepayment(),
                ada.repaymentReceipts(), ada.originId(), ada.collateral());
        Loan l = fx.loan();
        Loan loan = new Loan(l.txHash(), l.outputIndex(), l.address(), l.loanId(), l.collateralAmount(),
                l.lovelace(), tokenDatum);
        Utxo loanUtxo = EscrowCompoundRig.withAmounts(fx.loanUtxo(), fx.loanUtxo().getAmount());
        loanUtxo.setInlineDatum(LoanFixtures.hex(tokenDatum));

        LenderManagerDatum adaBond = fx.bond().datum();
        assertEquals(fx.bondUtxo().getInlineDatum(), LoanFixtures.hex(adaBond),
                "the real bond datum re-encodes byte-identically, so only the principal moves below");
        LenderManagerDatum tokenBond = new LenderManagerDatum(adaBond.lenderAuth(), adaBond.lenderStakeCredential(),
                adaBond.shouldLiquidationConvertToPrincipal(), adaBond.liquidationFeePerMille(), adaBond.poolId(),
                PRINCIPAL_TOKEN);
        String tokenBondHex = LoanFixtures.hex(tokenBond);
        Utxo bondUtxo = EscrowCompoundRig.withAmounts(fx.bondUtxo(), fx.bondUtxo().getAmount());
        bondUtxo.setInlineDatum(tokenBondHex);
        LenderBond bond = new LenderBond(fx.bond().txHash(), fx.bond().outputIndex(), fx.bond().address(),
                fx.bond().loanId(), tokenBondHex, tokenBond);

        OraclePriceFeed collateralFeed = base.oracle().feed();
        OracleEntry principalOracle = LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE,
                PRINCIPAL_ORACLE_CREDENTIAL, OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.ONE,
                        BigInteger.ONE, collateralFeed.validFrom(), collateralFeed.validTo()),
                LoanFixtures.input("fe".repeat(32), 0), LoanFixtures.input("8d".repeat(32), 0),
                LoanFixtures.input("8e".repeat(32), 0));
        Utxo wallet = LoanFixtures.utxo("e1".repeat(32), 0, LoanFixtures.botAddress(), List.of(
                Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                Amount.asset(PRINCIPAL_TOKEN.toUnit(), BigInteger.valueOf(50_000_000L))), null);

        List<Utxo> universe = new ArrayList<>(LiquidatePayInAdvanceDryEvalTest.universe(fx));
        universe.removeIf(u -> u.getTxHash().equals(fx.loanUtxo().getTxHash()));
        universe.addAll(List.of(loanUtxo, bondUtxo, wallet, LoanFixtures.utxo("fe".repeat(32), 0,
                LoanFixtures.rewardAddress(PRINCIPAL_ORACLE_CREDENTIAL), List.of(
                        Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(PRINCIPAL_ORACLE.toUnit(), BigInteger.ONE)), null)));

        var request = new LiquidatePayInAdvanceTransactionBuilder.Request(loan, loanUtxo, bond, bondUtxo, wallet,
                base.configUtxo(), base.lmConfigUtxo(), base.oracle(), principalOracle, base.validFromMillis(),
                base.validToMillis(), base.validFromSlot(), base.validToSlot(), base.changeAddress(),
                base.referenceScripts(), base.oracleWindowMarginMillis());
        Transaction produced = new LiquidatePayInAdvanceTransactionBuilder(PREVIEW, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams()).build(request);

        Produced escrow = escrowOf(produced, CONVERTED_TO_LIQUIDITY, l.loanId());
        assertEquals(Set.of("lovelace", PRINCIPAL_TOKEN.toUnit()), escrow.units().keySet(),
                "native principal: min-ADA plus the principal token (flatten == 2)");

        EscrowCompoundRig.Parts parts = piaParts(produced, escrow, bondUtxo, PRINCIPAL_TOKEN);
        EscrowCompoundRig.Compounded c = EscrowCompoundRig.compound(parts, "row 2 PIA native");
        assertEquals(escrow.units().get(PRINCIPAL_TOKEN.toUnit()), c.q(), "Q is the escrow's principal token");
        assertEquals(BigInteger.ZERO, c.fee(), "c = 0 on a token pool pays no fee and has no floor");
        log.info("FAB-168 row 2: PIA native escrow Q={} compounded, pool +{}", c.q(), c.poolAdded());
    }

    // ======================================================================================
    // Row 3 — convert, settled SUCCESS
    // ======================================================================================

    /**
     * 3(a). The order {@code lm_liquidate_and_convert_action} dictates is fixed by {@code equals_data}, so a
     * third-party operator's order is the shape ours must be — VERIFIED rather than assumed: for each of the
     * ten mainnet convert liquidations, the production {@link ConvertOrderPlan#plan} is fed that liquidation's
     * own loan, bond and borrower compensation and must reproduce the order datum BYTE FOR BYTE, the order
     * address through the production {@code ConvertLiquidationRouter.minswapOrderAddress}, the order's value,
     * and the success / refund datum that the batcher later wrote at the asset manager.
     *
     * <p>Only {@code minimum_receive} is read off the real order: it is the loan's remaining debt at that
     * transaction's validity start, a number the validator fixes, not a shape. The Minswap pool is a deep
     * synthetic one with ADA as {@code asset_a}; that the LP asset name then matches the chain is the evidence
     * that the real pools order the pair that way.
     */
    @Test
    void row3aEveryMainnetConvertOrderIsTheOrderOurPlanBuilds() {
        String minswapPolicy = EscrowCompoundRig.shippedMainnet("minswap", "pool-policy-id");
        String orderSpend = EscrowCompoundRig.shippedMainnet("minswap", "order-spend-script-hash");
        int successes = 0;
        int refunds = 0;
        for (JsonNode c : SETTLEMENTS.get("converts")) {
            String label = "convert " + c.get("convertTx").asText().substring(0, 12);
            Utxo loanUtxo = EscrowCompoundRig.utxo(c.get("loan"));
            Utxo bondUtxo = EscrowCompoundRig.utxo(c.get("bond"));
            Utxo order = EscrowCompoundRig.utxo(c.get("order"));
            Utxo compensation = EscrowCompoundRig.utxo(c.get("compensation"));
            Utxo settled = EscrowCompoundRig.utxo(c.get("settledEscrow"));

            LoanDatum loan = new LoanDatumConverter().deserialize(loanUtxo.getInlineDatum());
            LenderManagerDatum bond = new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum());
            String loanId = nftUnder(bondUtxo, REGISTRY.getLenderBondPolicyId()).substring(56);
            AssetType collateral = loan.collateral().assetType();
            AssetType lenderBond = new AssetType(REGISTRY.getLenderBondPolicyId(), loanId);

            // The borrower compensation is exactly what the loan claim pays out: partial_liquidation, borrower-owned.
            AssetManagerDatumWithToken comp = new AssetManagerDatumConverter().deserialize(compensation.getInlineDatum());
            assertEquals(PARTIAL_LIQUIDATION, comp.action(), label);
            assertEquals(new AssetType(REGISTRY.getBorrowerBondPolicyId(), loanId), comp.ownerAsset(), label);
            BigInteger equity = quantity(compensation, collateral.toUnit());

            List<PlutusData> step = fields(fields(order.getInlineDatum()).get(6));
            BigInteger minimumReceive = ((BigIntPlutusData) step.get(2)).getValue();

            MinswapPoolDatum deepPool = new MinswapPoolDatum(AssetType.ada(), collateral,
                    BigInteger.TEN.pow(18), BigInteger.TEN.pow(18), BigInteger.TEN.pow(15),
                    BigInteger.valueOf(30), BigInteger.valueOf(30), false);
            ConvertOrderPlan plan = ConvertOrderPlan.plan(collateral, loan.principalAsset(),
                    quantity(loanUtxo, collateral.toUnit()), equity, minimumReceive,
                    bond.liquidationFeePerMille().longValueExact(), bond.shouldLiquidationConvertToPrincipal(),
                    ((LiquidationMode.Liquidation) loan.liquidationMode()).equityInPrincipalCurrency(),
                    deepPool, minswapPolicy, lenderBond, bond.lenderAuth(),
                    ConvertTxEncoder.plainScriptAddress(REGISTRY.getAssetManagerSpendScriptHash()),
                    loanUtxo.getTxHash(), loanUtxo.getOutputIndex());

            assertEquals(order.getInlineDatum(), plan.orderDatum().serializeToHex(),
                    label + ": our order datum differs from the one the chain accepted");
            assertEquals(order.getAddress(), ConvertLiquidationRouter.minswapOrderAddress(orderSpend,
                    bond.lenderStakeCredential(), loanId, Networks.mainnet()), label + ": order address");
            assertEquals(plan.orderLovelace(), quantity(order, "lovelace"), label + ": order ada");
            assertEquals(plan.swappableCollateralAmount(), quantity(order, collateral.toUnit()),
                    label + ": collateral - equity - fee, with our fee arithmetic");

            String kind = c.get("settlement").asText();
            if ("success".equals(kind)) {
                assertEquals(plan.successDatum().serializeToHex(), settled.getInlineDatum(), label);
                assertEquals(plan.successDatumHash(), blake2b256(settled.getInlineDatum()), label);
                successes++;
            } else {
                assertEquals("refund", kind);
                assertEquals(plan.refundDatum().serializeToHex(), settled.getInlineDatum(), label);
                assertEquals(plan.refundDatumHash(), blake2b256(settled.getInlineDatum()), label);
                refunds++;
            }
        }
        assertEquals(5, successes, "the five ASCEND orders filled");
        assertEquals(5, refunds, "the five IAG orders of 2026-10-23 were refunded");
    }

    /**
     * 3(b)+(c). The settled escrow {@code e4a98c45…#1} — 208,102,980 lovelace and the success datum, captured —
     * with the REAL bond echo {@code bb885b16…#2} and the REAL pool pair, no substitution at all, through the
     * scanner, the builder and the new {@code lm_compound_action}. On chain the 2026-10-01 script
     * ({@code 71515a89…}) compounded exactly this set in {@code a793085f…} and the pool received all of Q
     * (c = 0, no floor then); under the new script it receives Q − 1 ADA.
     */
    @Test
    void row3cTheSettledConvertEscrowCompoundsThroughTheNewLmCompound() throws Exception {
        JsonNode onChain = SETTLEMENTS.get("onChainCompound");
        Utxo escrow = EscrowCompoundRig.utxo(onChain.get("escrow"));
        assertEquals("e4a98c459830000d4fa8017888204c42b00db42fdf94bf9ff4ee84594f3b8ea4#1",
                escrow.getTxHash() + "#" + escrow.getOutputIndex());
        assertEquals(Map.of("lovelace", BigInteger.valueOf(208_102_980L)), EscrowCompoundRig.units(escrow.getAmount()));
        AssetManagerDatumWithToken datum = new AssetManagerDatumConverter().deserialize(escrow.getInlineDatum());
        assertEquals(CONVERTED_TO_LIQUIDITY, datum.action());
        assertEquals(REGISTRY.getLenderBondPolicyId(), datum.ownerAsset().policyId());

        EscrowCompoundRig.Parts parts = new EscrowCompoundRig.Parts(escrow, EscrowCompoundRig.utxo(onChain.get("bond")),
                REAL_POOL, REAL_POOL_MANAGER, null);
        EscrowCompoundRig.Compounded c = EscrowCompoundRig.compound(parts, "row 3 convert success e4a98c45#1");
        assertEquals(BigInteger.valueOf(208_102_980L), c.q());
        assertEquals(ADA, c.fee());

        BigInteger onChainAdded = new BigInteger(onChain.get("poolOutputLovelace").asText())
                .subtract(EscrowCompoundRig.units(REAL_POOL.getAmount()).get("lovelace"));
        assertEquals(c.q(), onChainAdded, "the 2026-10-01 script let the pool keep all of Q (c = 0, no floor)");
        assertEquals(onChainAdded.subtract(ADA), c.poolAdded(), "the new one keeps back exactly the 1 ADA floor");
    }

    /** The other four filled ASCEND escrows of the wave, each with its own real bond echo. */
    @Test
    void row3cEverySettledSuccessOfTheWaveCompounds() throws Exception {
        int compounded = 0;
        for (JsonNode c : SETTLEMENTS.get("converts")) {
            if (!"success".equals(c.get("settlement").asText())) {
                continue;
            }
            Utxo escrow = EscrowCompoundRig.utxo(c.get("settledEscrow"));
            Utxo bond = EscrowCompoundRig.utxo(c.get("bondEcho"));
            String poolId = new LenderManagerDatumConverter().deserialize(bond.getInlineDatum()).poolId();
            EscrowCompoundRig.Compounded done = EscrowCompoundRig.compound(new EscrowCompoundRig.Parts(escrow, bond,
                            poolFor(poolId), poolManagerFor(poolId), null),
                    "convert success " + escrow.getTxHash().substring(0, 12));
            assertEquals(ADA, done.fee());
            compounded++;
        }
        assertEquals(5, compounded);
    }

    // ======================================================================================
    // Row 4 — convert, pending and refunded
    // ======================================================================================

    /**
     * Pending: the only output a convert writes for the lender is the Minswap ORDER, at Minswap's order
     * script — not at the asset manager. The scanner's single index read is keyed on the asset-manager spend
     * credential (driven here through the production {@code escrows()} against a recording repository), so
     * no order can reach the classification at all.
     */
    @Test
    void row4aAPendingOrderIsNeverAnEscrowTheScannerCanSee() {
        List<Object> queried = new ArrayList<>();
        UtxoRepository repository = (UtxoRepository) Proxy.newProxyInstance(UtxoRepository.class.getClassLoader(),
                new Class<?>[]{UtxoRepository.class}, (proxy, method, args) -> {
                    if ("findUnspentByOwnerPaymentCredential".equals(method.getName())) {
                        queried.add(args[0]);
                        return Optional.empty();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return "recording UtxoRepository";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertTrue(new CompoundCandidateScanner(repository, REGISTRY, null, null).escrows().isEmpty());
        assertEquals(List.of(REGISTRY.getAssetManagerSpendScriptHash()), queried);

        String orderSpend = EscrowCompoundRig.shippedMainnet("minswap", "order-spend-script-hash");
        for (JsonNode c : SETTLEMENTS.get("converts")) {
            Utxo order = EscrowCompoundRig.utxo(c.get("order"));
            assertEquals(orderSpend, EscrowCompoundRig.paymentCredential(order.getAddress()));
            assertNotEquals(REGISTRY.getAssetManagerSpendScriptHash(),
                    EscrowCompoundRig.paymentCredential(order.getAddress()));
        }
    }

    /**
     * Refund: the five IAG orders of 2026-10-23 were batched one block later and REFUNDED — 2 ADA plus the
     * whole swap amount of IAG landed at the asset manager under the refund ({@code claimed_collateral}) datum.
     * For an ADA-principal lender that value flattens to 2, so the scanner refuses it as
     * {@link CompoundExclusion#ESCROW_SHAPE_REJECTED} and {@code lm_compound_action} refuses it at its
     * flatten check ({@code assetAmount} is the 2 ADA, so {@code > 0} holds; length 2 ≠ 1 does not).
     */
    @Test
    void row4bEveryRefundedIagOrderIsRefusedByTheScannerAndByLmCompound() throws Exception {
        int refunds = 0;
        for (JsonNode c : SETTLEMENTS.get("converts")) {
            if (!"refund".equals(c.get("settlement").asText())) {
                continue;
            }
            Utxo refund = EscrowCompoundRig.utxo(c.get("settledEscrow"));
            Utxo bond = EscrowCompoundRig.utxo(c.get("bondEcho"));
            String label = "IAG refund " + refund.getTxHash().substring(0, 12) + "#" + refund.getOutputIndex();
            AssetManagerDatumWithToken datum = new AssetManagerDatumConverter().deserialize(refund.getInlineDatum());
            assertEquals(CLAIMED_COLLATERAL, datum.action(), label);
            assertEquals(2, refund.getAmount().size(), label + ": 2 ADA + IAG");

            String poolId = new LenderManagerDatumConverter().deserialize(bond.getInlineDatum()).poolId();
            EscrowCompoundRig.Parts real = new EscrowCompoundRig.Parts(refund, bond, poolFor(poolId),
                    poolManagerFor(poolId), null);
            CompoundCandidate seen = EscrowCompoundRig.classify(real);
            assertEquals(CompoundExclusion.ESCROW_SHAPE_REJECTED, seen.exclusion(), label + ": " + seen.detail());

            Utxo control = EscrowCompoundRig.withAmounts(refund, List.of(Amount.lovelace(quantity(refund, "lovelace"))));
            String why = EscrowCompoundRig.refusedByLmCompound(real.withEscrow(control), refund, label);
            log.info("FAB-168 row 4 {}: scanner {}, lm_compound_action refused: {}", label, seen.exclusion(), why);
            refunds++;
        }
        assertEquals(5, refunds);
    }

    /**
     * The other asset-manager output every convert writes — the borrower's equity, {@code partial_liquidation},
     * owned by the BORROWER bond. No lender bond names it, so it is outside {@code lm_compound_action}'s reach
     * by construction and the scanner says so by name.
     */
    @Test
    void row4cEveryConvertBorrowerCompensationIsNotLenderOwned() throws Exception {
        JsonNode first = SETTLEMENTS.get("converts").get(0);
        Utxo bond = EscrowCompoundRig.utxo(first.get("bondEcho"));
        for (JsonNode c : SETTLEMENTS.get("converts")) {
            Utxo compensation = EscrowCompoundRig.utxo(c.get("compensation"));
            CompoundCandidate seen = EscrowCompoundRig.classify(
                    new EscrowCompoundRig.Parts(compensation, bond, REAL_POOL, REAL_POOL_MANAGER, null));
            assertEquals(CompoundExclusion.NOT_LENDER_OWNED, seen.exclusion(), seen.detail());
        }
    }

    // ======================================================================================
    // Row 5 — the PLAIN collateral escrow
    // ======================================================================================

    /**
     * 5(a). The usual plain case, ADA principal against a token collateral: the lender's escrow is the
     * collateral payout plus its min-ADA rider. Scanner: refused. Validator: refused at the flatten check (the
     * rider is lovelace, so {@code assetAmount > 0} holds; length 2 ≠ 1 does not).
     */
    @Test
    void row5aPlainCollateralEscrowWithAnAdaPrincipalIsRefusedByBoth() throws Exception {
        Plain plain = plainLiquidation(AssetType.ada(), COLLATERAL_TOKEN, BigInteger.valueOf(50_000_000L));
        assertEquals(Set.of("lovelace", COLLATERAL_TOKEN.toUnit()), plain.escrow().units().keySet());

        EscrowCompoundRig.Parts real = plainParts(plain, null);
        CompoundCandidate seen = EscrowCompoundRig.classify(real);
        assertEquals(CompoundExclusion.ESCROW_SHAPE_REJECTED, seen.exclusion(), seen.detail());

        Utxo control = EscrowCompoundRig.withAmounts(real.escrow(),
                List.of(Amount.lovelace(quantity(real.escrow(), "lovelace"))));
        String why = EscrowCompoundRig.refusedByLmCompound(real.withEscrow(control), real.escrow(), "row 5a");
        log.info("FAB-168 row 5a: scanner {}, lm_compound_action refused: {}", seen.exclusion(), why);

        // The borrower's compensation the same transaction writes: borrower-owned, refused by name.
        Produced compensation = escrowOf(plain.tx(), PARTIAL_LIQUIDATION, plain.loanId());
        CompoundCandidate comp = EscrowCompoundRig.classify(real.withEscrow(EscrowCompoundRig.onMainnet(
                plain.txHash(), compensation.index(), compensation.output(),
                REGISTRY.getAssetManagerSpendScriptHash())));
        assertEquals(CompoundExclusion.NOT_LENDER_OWNED, comp.exclusion(), comp.detail());
    }

    /**
     * 5(b). Native principal, a DIFFERENT token as collateral: the escrow carries none of the principal.
     * Scanner: refused. Validator: refused at {@code expect assetAmount > 0}.
     */
    @Test
    void row5bPlainCollateralEscrowWithANativePrincipalIsRefusedByBoth() throws Exception {
        Plain plain = plainLiquidation(PRINCIPAL_TOKEN, COLLATERAL_TOKEN, BigInteger.valueOf(500_000L));
        assertEquals(BigInteger.ZERO, plain.escrow().units().getOrDefault(PRINCIPAL_TOKEN.toUnit(), BigInteger.ZERO),
                "the plain escrow carries no principal at all");

        EscrowCompoundRig.Parts real = plainParts(plain, PRINCIPAL_TOKEN);
        CompoundCandidate seen = EscrowCompoundRig.classify(real);
        assertEquals(CompoundExclusion.ESCROW_SHAPE_REJECTED, seen.exclusion(), seen.detail());

        Utxo control = EscrowCompoundRig.withAmounts(real.escrow(), List.of(
                Amount.lovelace(quantity(real.escrow(), "lovelace")),
                Amount.asset(PRINCIPAL_TOKEN.toUnit(), BigInteger.valueOf(100_000L))));
        String why = EscrowCompoundRig.refusedByLmCompound(real.withEscrow(control), real.escrow(), "row 5b");
        log.info("FAB-168 row 5b: scanner {}, lm_compound_action refused: {}", seen.exclusion(), why);
    }

    /**
     * 5(c). Same asset, ADA collateral against an ADA principal: the plain escrow is pure lovelace under the
     * {@code claimed_collateral} datum — exactly the ADA shape, so it IS compounded. The producer here is also
     * evaluated against the deployed preview validators. No exclusion is added: this is what the validator
     * permits, recorded as such.
     */
    @Test
    void row5cPlainSameAssetAdaEscrowCompounds() throws Exception {
        Plain plain = plainLiquidation(AssetType.ada(), AssetType.ada(), BigInteger.valueOf(100_000_000L));
        EvalFixtures.evaluate(plain.tx(), plain.universe(), PREVIEW);
        assertEquals(Set.of("lovelace"), plain.escrow().units().keySet());

        EscrowCompoundRig.Compounded c = EscrowCompoundRig.compound(plainParts(plain, null), "row 5c ADA/ADA");
        assertEquals(ADA, c.fee());
        log.info("FAB-168 row 5c: plain ADA/ADA claimed collateral Q={} compounded", c.q());
    }

    /** 5(d). Same asset, one token as both collateral and principal: min-ADA + principal, the native shape. */
    @Test
    void row5dPlainSameAssetTokenEscrowCompounds() throws Exception {
        Plain plain = plainLiquidation(COLLATERAL_TOKEN, COLLATERAL_TOKEN, BigInteger.valueOf(500_000L));
        assertEquals(Set.of("lovelace", COLLATERAL_TOKEN.toUnit()), plain.escrow().units().keySet());

        EscrowCompoundRig.Compounded c = EscrowCompoundRig.compound(plainParts(plain, COLLATERAL_TOKEN),
                "row 5d token/token");
        assertEquals(BigInteger.ZERO, c.fee());
        log.info("FAB-168 row 5d: plain token/token claimed collateral Q={} compounded", c.q());
    }

    // ======================================================================================
    // Producers
    // ======================================================================================

    /** An output of a built transaction, by its absolute index, with its datum decoded. */
    private record Produced(int index, TransactionOutput output, AssetManagerDatumWithToken datum,
                            Map<String, BigInteger> units) {
    }

    /** The single asset-manager output carrying {@code action} for {@code loanId}, located off the BUILT body. */
    private static Produced escrowOf(Transaction built, String action, String loanId) throws Exception {
        Transaction tx = Transaction.deserialize(built.serialize());
        List<Produced> found = new ArrayList<>();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput o = outputs.get(i);
            if (!PREVIEW.getAssetManagerSpendScriptHash().equals(EscrowCompoundRig.paymentCredential(o.getAddress()))
                    || o.getInlineDatum() == null) {
                continue;
            }
            AssetManagerDatumWithToken d = new AssetManagerDatumConverter().deserialize(o.getInlineDatum().serializeToHex());
            if (action.equals(d.action()) && loanId.equals(d.ownerAsset().assetName())) {
                found.add(new Produced(i, o, d, EscrowCompoundRig.units(o)));
            }
        }
        assertEquals(1, found.size(), "exactly one " + action + " output for " + loanId);
        Produced p = found.getFirst();
        String owner = CONVERTED_TO_LIQUIDITY.equals(action) || CLAIMED_COLLATERAL.equals(action)
                ? PREVIEW.getLenderBondPolicyId() : PREVIEW.getBorrowerBondPolicyId();
        assertEquals(owner, p.datum().ownerAsset().policyId(), action + " is owned by the right bond");
        return p;
    }

    /** The PIA escrow and bond echo as the mainnet UTxOs they become, with the real pool pair for the bond's pool. */
    private static EscrowCompoundRig.Parts piaParts(Transaction produced, Produced escrow, Utxo bondInput,
                                                    AssetType token) throws Exception {
        Transaction tx = Transaction.deserialize(produced.serialize());
        String txHash = TransactionUtil.getTxHash(tx);
        int echoAt = -1;
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput o = outputs.get(i);
            if (bondInput.getAddress().equals(o.getAddress()) && o.getInlineDatum() != null
                    && bondInput.getInlineDatum().equals(o.getInlineDatum().serializeToHex())) {
                echoAt = i;
            }
        }
        assertTrue(echoAt >= 0, "the producer echoes its bond");
        Utxo escrowUtxo = EscrowCompoundRig.onMainnet(txHash, escrow.index(), escrow.output(),
                REGISTRY.getAssetManagerSpendScriptHash());
        Utxo bond = EscrowCompoundRig.onMainnet(txHash, echoAt, outputs.get(echoAt),
                REGISTRY.getLenderManagerSpendScriptHash());
        String poolId = new LenderManagerDatumConverter().deserialize(bond.getInlineDatum()).poolId();
        return new EscrowCompoundRig.Parts(escrowUtxo, bond, poolFor(poolId, token), poolManagerFor(poolId),
                token == null ? null : token.toUnit());
    }

    private record Plain(Transaction tx, String txHash, String loanId, Produced escrow, Utxo bondUtxo,
                         List<Utxo> universe) {
    }

    /**
     * A plain {@code Liquidate} by the production {@link LiquidateTransactionBuilder}: 30 days late, 10% over
     * one installment, liquidationFeePerMille 10, the lender bond naming the real mainnet pool. Token legs are
     * Charli3-priced at 100 lovelace a unit; ADA legs are the synthesised unit feed.
     */
    private static Plain plainLiquidation(AssetType principal, AssetType collateral, BigInteger principalAmount)
            throws Exception {
        long now = 1_700_000_000_000L;
        long lendDate = now - 30L * 24 * 3_600_000L;
        String loanId = "a1b2c3d4e5f6a1b2";
        AssetType principalOracle = principal.isAda() ? AssetType.ada()
                : principal.equals(collateral) ? COLLATERAL_ORACLE : PRINCIPAL_ORACLE;
        LoanDatum datum = LoanFixtures.loanDatum(principal, principalOracle, principalAmount, BigInteger.valueOf(1000),
                collateral.isAda() ? LoanFixtures.adaCollateral()
                        : LoanFixtures.tokenCollateral(collateral, COLLATERAL_ORACLE),
                lendDate, LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = collateral.isAda()
                ? LoanFixtures.loanUtxo("aa".repeat(32), 0, loanId, datum, 100_000_000L, List.of())
                : LoanFixtures.loanUtxo("aa".repeat(32), 0, loanId, datum, 2_000_000L,
                List.of(LoanFixtures.token(collateral, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo("dd".repeat(32), 0, loanId,
                LoanFixtures.bondDatum(BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential("33".repeat(28)),
                        principal, REAL_POOL_ID), 2_000_000L);

        OraclePriceFeed tokenFeed = OraclePriceFeed.priceDataCharlie(collateral.isAda() ? principal : collateral,
                BigInteger.valueOf(100), BigInteger.ONE, now - 60_000L, now + 600_000L);
        OraclePriceFeed collateralFeed = collateral.isAda() ? OraclePriceFeed.unit() : tokenFeed;
        OraclePriceFeed principalFeed = principal.isAda() ? OraclePriceFeed.unit()
                : principal.equals(collateral) ? tokenFeed
                : OraclePriceFeed.priceDataCharlie(principal, BigInteger.valueOf(100), BigInteger.ONE,
                now - 60_000L, now + 600_000L);
        Map<String, OracleEntry> oracles = new java.util.LinkedHashMap<>();
        if (!collateral.isAda()) {
            oracles.put(COLLATERAL_ORACLE.toUnit(), LoanFixtures.charli3(collateral, COLLATERAL_ORACLE,
                    COLLATERAL_ORACLE_CREDENTIAL, collateralFeed, LoanFixtures.input("9a".repeat(32), 0),
                    LoanFixtures.input("9b".repeat(32), 0), LoanFixtures.input("9c".repeat(32), 0)));
        }
        if (!principal.isAda() && !principal.equals(collateral)) {
            oracles.put(PRINCIPAL_ORACLE.toUnit(), LoanFixtures.charli3(principal, PRINCIPAL_ORACLE,
                    PRINCIPAL_ORACLE_CREDENTIAL, principalFeed, LoanFixtures.input("9a".repeat(32), 1),
                    LoanFixtures.input("9b".repeat(32), 1), LoanFixtures.input("9c".repeat(32), 1)));
        }
        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(), principalFeed,
                collateralFeed, now);

        Utxo config = LoanFixtures.syntheticLatestConfigUtxo("f1".repeat(32), 0);
        Utxo lmConfig = LoanFixtures.syntheticLatestLmConfigUtxo("f2".repeat(32), 0);
        Utxo wallet = LoanFixtures.adaUtxo("e0".repeat(32), 0, LoanFixtures.botAddress(), 50_000_000L);
        Utxo spare = LoanFixtures.adaUtxo("e1".repeat(32), 0, LoanFixtures.botAddress(), 10_000_000L);
        List<Utxo> universe = List.of(config, lmConfig, wallet, spare, loan.utxo(), bond.utxo());
        Transaction tx = new LiquidateTransactionBuilder(PREVIEW, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams())
                .build(new LiquidateTransactionBuilder.Request(
                        List.of(new LiquidateTransactionBuilder.LoanLiquidation(assessment, loan.utxo(), bond.utxo())),
                        config, lmConfig, oracles, wallet, LoanFixtures.botAddress(), now, now + 120_000L, 300_000L,
                        LiquidateTransactionBuilder.ReferenceScripts.none()));
        Transaction body = Transaction.deserialize(tx.serialize());
        return new Plain(tx, TransactionUtil.getTxHash(body), loanId, escrowOf(tx, CLAIMED_COLLATERAL, loanId),
                bond.utxo(), universe);
    }

    private static EscrowCompoundRig.Parts plainParts(Plain plain, AssetType token) throws Exception {
        Transaction tx = Transaction.deserialize(plain.tx().serialize());
        int echoAt = -1;
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput o = outputs.get(i);
            if (o.getInlineDatum() != null && plain.bondUtxo().getInlineDatum().equals(o.getInlineDatum().serializeToHex())
                    && PREVIEW.getLenderManagerSpendScriptHash().equals(EscrowCompoundRig.paymentCredential(o.getAddress()))) {
                echoAt = i;
            }
        }
        assertTrue(echoAt >= 0, "the plain liquidation echoes its bond");
        Utxo escrow = EscrowCompoundRig.onMainnet(plain.txHash(), plain.escrow().index(), plain.escrow().output(),
                REGISTRY.getAssetManagerSpendScriptHash());
        Utxo bond = EscrowCompoundRig.onMainnet(plain.txHash(), echoAt, outputs.get(echoAt),
                REGISTRY.getLenderManagerSpendScriptHash());
        return new EscrowCompoundRig.Parts(escrow, bond, poolFor(REAL_POOL_ID, token), poolManagerFor(REAL_POOL_ID),
                token == null ? null : token.toUnit());
    }

    // ======================================================================================
    // The real pool pair, for another pool id and/or a token principal
    // ======================================================================================

    private static Utxo poolFor(String poolId) {
        return poolFor(poolId, null);
    }

    /**
     * The real mainnet pool for {@code poolId}; for a token principal its datum's principal (the first
     * {@code Asset { "", "" }}, as {@link CompoundMainnetGateTest} substitutes it) becomes the token and the
     * pool holds a reserve of it.
     */
    private static Utxo poolFor(String poolId, AssetType token) {
        Utxo pool = REAL_POOL_ID.equals(poolId) ? REAL_POOL
                : EscrowCompoundRig.renamedNft(REAL_POOL, REGISTRY.getPoolPolicyId(), poolId);
        if (token == null) {
            return pool;
        }
        String hex = pool.getInlineDatum();
        int at = hex.indexOf("d8799f4040ff");
        assertTrue(at >= 0, "the pool datum names an ADA principal");
        String swapped = hex.substring(0, at) + "d8799f581c" + token.policyId()
                + Integer.toHexString(0x40 + token.assetName().length() / 2) + token.assetName() + "ff"
                + hex.substring(at + "d8799f4040ff".length());
        List<Amount> amounts = new ArrayList<>(pool.getAmount());
        amounts.add(Amount.asset(token.toUnit(), BigInteger.valueOf(9_000_000_000L)));
        Utxo out = EscrowCompoundRig.withAmounts(pool, amounts);
        out.setInlineDatum(swapped);
        return out;
    }

    private static Utxo poolManagerFor(String poolId) {
        return REAL_POOL_ID.equals(poolId) ? REAL_POOL_MANAGER
                : EscrowCompoundRig.renamedNft(REAL_POOL_MANAGER, REGISTRY.getPoolManagerPolicyId(), poolId);
    }

    // ======================================================================================
    // Primitives
    // ======================================================================================

    private static String nftUnder(Utxo utxo, String policyId) {
        return utxo.getAmount().stream().map(Amount::getUnit).filter(u -> u.startsWith(policyId))
                .findFirst().orElseThrow(() -> new AssertionError("no NFT under " + policyId));
    }

    private static BigInteger quantity(Utxo utxo, String unit) {
        return EscrowCompoundRig.units(utxo.getAmount()).getOrDefault(unit, BigInteger.ZERO);
    }

    private static List<PlutusData> fields(String hex) {
        try {
            return fields(PlutusData.deserialize(CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex))));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<PlutusData> fields(PlutusData data) {
        return ((ConstrPlutusData) data).getData().getPlutusDataList();
    }

    private static String blake2b256(String hex) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(hex)));
    }
}
