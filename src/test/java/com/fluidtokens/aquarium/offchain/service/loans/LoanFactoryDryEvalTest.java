package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoanFactory} exercised end to end against the <em>real deployed</em> validators through
 * {@link EvalFixtures}: a create → borrow → recovery-cancel pipeline over the factory's own preview
 * pool, entirely synthetic, so the test runs cold — no network, no key, no wallet. The lender and the
 * borrower are one wallet ({@link LoanFixtures#botAddress()}).
 *
 * <h2>What each case proves</h2>
 * <ul>
 *   <li><b>Positive</b> — create, borrow and recovery-cancel all build and dry-evaluate green; the
 *       emitted lender bond decodes to fee 100 off the finished borrow body, and the recovery returns
 *       every lovelace to the lender.</li>
 *   <li><b>F-a</b> — a fee-0 pool makes {@link LoanFactory#buildBorrow} throw the fee gate; a green
 *       suite with a fee-0 pool is therefore impossible.</li>
 *   <li><b>F-b</b> — a healthy collateral price makes the born-liquidatable gate fire.</li>
 *   <li><b>F-c</b> — a recovery cancel whose change address is not the lender is refused by the tool's
 *       own destination gate.</li>
 * </ul>
 * Each falsifiability case asserts on the <em>named</em> {@link LoanFactory.GateFailure} message, not on
 * an incidental exception, so it proves the specific gate fired.
 */
@Slf4j
class LoanFactoryDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();
    private static final String FUNDER = LoanFixtures.botAddress();
    private static final Address LENDER = new Address(FUNDER);

    private static final long LOAN_OUTPUT_LOVELACE = 2_500_000L;
    private static final long BOND_LOVELACE = 1_500_000L;
    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_100L;   // a 100-second window

    private static final String FUNDER_TX = "ee".repeat(32);
    private static final String COLLATERAL_TX = "ef".repeat(32);

    // ======================================================================================
    // The positive case
    // ======================================================================================

    @Test
    void createBorrowAndRecoveryCancelAllEvaluateGreen() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(params, 1, 2);   // born liquidatable at 0.5 lovelace/unit

        Transaction create = factory.buildCreate(recipe);
        Transaction borrow = factory.buildBorrow(recipe, create);
        Transaction cancel = factory.buildRecoveryCancel(recipe, create);

        // The emitted lender bond decodes to fee 100 off the finished borrow body.
        String bondAssetName = PoolTxEncoder.bondAssetName(
                new TransactionInput(TransactionUtil.getTxHash(create), 0));
        TransactionOutput bondOutput = outputWithNft(borrow, LoanFixtures.bondAddress(),
                REGISTRY.getLenderBondPolicyId(), bondAssetName);
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(100), bondDatum.liquidationFeePerMille(),
                "the factory must emit a fee-100 lender bond");

        // The recovery returns value to the lender: no pool continuation, every output at the lender.
        List<TransactionOutput> recovered = cancel.getBody().getOutputs();
        assertTrue(recovered.stream().noneMatch(o -> PoolFixtures.poolAddress().equals(o.getAddress())),
                "the recovery must leave no pool continuation");
        assertTrue(recovered.stream().allMatch(o -> FUNDER.equals(o.getAddress())),
                "every recovery output must return to the lender");
        BigInteger returned = recovered.stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        assertTrue(returned.compareTo(BigInteger.valueOf(params.poolLiquidityLovelace())) > 0,
                "the lender must recover more than the pool's own liquidity — it came back");

        log.info("LoanFactory pipeline green: create {} bytes, borrow, recovery-cancel; emitted bond fee {}",
                sizeOf(create), bondDatum.liquidationFeePerMille());
    }

    // ======================================================================================
    // F-a — a fee-0 pool is refused by the fee gate
    // ======================================================================================

    @Test
    void aFeeZeroPoolIsRefusedByTheFeeGate() {
        PoolFixtures.PoolParameters feeZero = withFee(PoolFixtures.defaults(), 0L);
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(feeZero, 1, 2);  // a liquidatable price, so only the fee gate can fire

        Transaction create = factory.buildCreate(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a fee-0 pool must not yield a returned borrow transaction");
        assertTrue(failure.getMessage().contains("FEE_GATE"),
                "the refusal must be the fee gate, not an incidental failure: " + failure.getMessage());
        log.info("F-a fee gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-d — a transaction the real validators reject is refused by the dry-eval gate
    // ======================================================================================

    /**
     * The dry-eval gate is the tool's first line: every returned transaction must have passed the real
     * PlutusV3 machine. This drives it in the refusing direction with a transaction that <em>builds</em>
     * cleanly (it passes the borrow builder's own structural assertion) but that the deployed
     * {@code pool_borrow_action} rejects — a validity window wider than one hour, which
     * {@link PoolBorrowDryEvalTest} pinned as a genuine {@code Withdraw#0} refusal. Because the dry-eval
     * gate runs <em>before</em> the fee and born-liquidatable gates, an otherwise-fee-100, liquidatable
     * recipe still fails here, proving this gate — not a later one — is what refuses. Without the gate the
     * tool would hand back a transaction the chain will not accept.
     */
    @Test
    void aTransactionTheValidatorsRejectIsRefusedByTheDryEvalGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();   // fee 100, liquidatable at 1/2
        LoanFactory factory = factory(baseUniverse());
        // A 3,700-slot (≈1h2m) window — wider than the validator's one-hour ceiling, but otherwise honest.
        LoanFactory.Recipe recipe = recipe(params, 1, 2, VALID_FROM_SLOT + 3_700L);

        Transaction create = factory.buildCreate(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a borrow the real validators reject must not be returned");
        assertTrue(failure.getMessage().contains("DRY_EVAL_GATE"),
                "the refusal must be the dry-eval gate, not a later gate: " + failure.getMessage());
        log.info("F-d dry-eval gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-b — a healthy price is refused by the born-liquidatable gate
    // ======================================================================================

    @Test
    void aHealthyCollateralPriceIsRefusedByTheBornLiquidatableGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();   // fee 100, so the fee gate passes
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(params, 1, 1);  // 1 lovelace/unit: the borrower has equity

        Transaction create = factory.buildCreate(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a loan that is not born liquidatable must be refused");
        assertTrue(failure.getMessage().contains("BORN_LIQUIDATABLE_GATE"),
                "the refusal must be the born-liquidatable gate: " + failure.getMessage());
        log.info("F-b born-liquidatable gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-c — a recovery to the wrong address is refused by the destination gate
    // ======================================================================================

    @Test
    void aRecoveryToTheWrongAddressIsRefusedByTheDestinationGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();
        Address wrong = wrongAddress();

        // The seam sends the change to `wrong` while the required signer stays the lender, so the cancel
        // dry-evaluates green and only the destination gate can refuse it. Coin selection needs a
        // collateral UTxO at `wrong`, so the construction supplier carries one.
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(LoanFixtures.adaUtxo("ea".repeat(32), 0, wrong.getAddress(), 60_000_000L));
        LoanFactory factory = factory(universe);
        LoanFactory.Recipe recipe = recipe(params, 1, 2);

        Transaction create = factory.buildCreate(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildRecoveryCancel(recipe, create, wrong),
                "a recovery whose change is not the lender must be refused");
        assertTrue(failure.getMessage().contains("RECOVERY_DESTINATION_GATE"),
                "the refusal must be the recovery-destination gate: " + failure.getMessage());
        log.info("F-c destination gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    private static LoanFactory factory(List<Utxo> universe) {
        return new LoanFactory(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams());
    }

    private static LoanFactory.Recipe recipe(PoolFixtures.PoolParameters params,
                                             long priceNumerator, long priceDenominator) {
        return recipe(params, priceNumerator, priceDenominator, VALID_TO_SLOT);
    }

    private static LoanFactory.Recipe recipe(PoolFixtures.PoolParameters params,
                                             long priceNumerator, long priceDenominator, long validToSlot) {
        return new LoanFactory.Recipe(
                params, LENDER, LENDER,
                priceNumerator, priceDenominator,
                seedUtxo(), funderUtxo(), PoolFixtures.configUtxo(), PoolFixtures.poolPolicyRefScriptUtxo(),
                borrowReferenceScriptUtxos(), cancelReferenceScriptUtxos(),
                PoolFixtures.TFLDT, params.principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE,
                VALID_FROM_SLOT, validToSlot);
    }

    private static PoolFixtures.PoolParameters withFee(PoolFixtures.PoolParameters p, long feePerMille) {
        return new PoolFixtures.PoolParameters(p.principalLovelace(), p.collateralPerPrincipalNumerator(),
                p.collateralPerPrincipalDivider(), p.interestRate(), p.liquidationLtv(),
                p.liquidationLtvDivider(), p.partialLiquidationPenaltyPerMille(), feePerMille,
                p.poolLiquidityLovelace(), p.loanOutputLovelace());
    }

    private static Utxo seedUtxo() {
        return PoolFixtures.seedUtxo(FUNDER);
    }

    /** The funder input: ada for fees and collateral, plus the tFLDT the loan output posts. */
    private static Utxo funderUtxo() {
        return LoanFixtures.utxo(FUNDER_TX, 0, FUNDER,
                List.of(Amount.lovelace(BigInteger.valueOf(100_000_000L)),
                        Amount.asset(LoanFixtures.unit(PoolFixtures.TFLDT), BigInteger.valueOf(20_000_000L))),
                null);
    }

    /**
     * The construction supplier's universe: the spend inputs coin selection draws on (the seed, the
     * funder input and a collateral UTxO), plus the config the reference-input resolution falls back to.
     * The published reference scripts are resolved from the recipe's own hash-bearing coordinates (see
     * {@code LoanFactory#universeOf}), so they are deliberately not added here.
     */
    private static List<Utxo> baseUniverse() {
        List<Utxo> universe = new ArrayList<>();
        universe.add(seedUtxo());
        universe.add(funderUtxo());
        universe.add(LoanFixtures.adaUtxo(COLLATERAL_TX, 1, FUNDER, 60_000_000L));
        universe.add(PoolFixtures.configUtxo());
        return universe;
    }

    private static List<Utxo> borrowReferenceScriptUtxos() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolPolicyId(), REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolBorrowActionScriptHash(), REGISTRY.getLoanPolicyId(),
                REGISTRY.getLenderBondPolicyId(), REGISTRY.getBorrowerBondPolicyId()));
    }

    private static List<Utxo> cancelReferenceScriptUtxos() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolSpendScriptHash(), REGISTRY.getPoolPolicyId(),
                REGISTRY.getPoolCancelActionScriptHash()));
    }

    private static List<Utxo> referenceScriptUtxos(List<String> hashes) {
        List<Utxo> utxos = new ArrayList<>();
        for (String hash : hashes) {
            String coord = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(hash);
            String txHash = coord.substring(0, coord.indexOf('#'));
            int index = Integer.parseInt(coord.substring(coord.indexOf('#') + 1));
            utxos.add(Utxo.builder()
                    .txHash(txHash).outputIndex(index)
                    .address(LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash)
                    .build());
        }
        return utxos;
    }

    /** A base address controlled by neither the lender's payment nor stake key — the "wrong" destination. */
    private static Address wrongAddress() {
        return new Address(AddressProvider.getBaseAddress(
                Credential.fromKey("33".repeat(28)), Credential.fromKey("44".repeat(28)),
                LoanFixtures.NETWORK).getAddress());
    }

    private static TransactionOutput outputWithNft(Transaction tx, String address, String policyId,
                                                   String assetNameHex) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress())
                        && o.getValue().getMultiAssets().stream()
                        .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                        .flatMap(ma -> ma.getAssets().stream())
                        .anyMatch(a -> strip(a.getNameAsHex()).equalsIgnoreCase(assetNameHex)))
                .findFirst().orElseThrow(() -> new AssertionError("no output at " + address + " holding "
                        + policyId + assetNameHex));
    }

    private static int sizeOf(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialise", e);
        }
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
