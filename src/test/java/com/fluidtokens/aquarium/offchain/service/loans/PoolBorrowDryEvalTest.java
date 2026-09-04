package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool-borrow transaction handed to the real PlutusV3 machine and run against the
 * <em>real deployed</em> {@code pool_borrow_action}, {@code pool.pool}, {@code general_spend},
 * {@code loan.loan} and both {@code bond} validators, chained onto S1's own
 * {@link PoolCreateTransactionBuilder} output so there is one pool, not two. The applied compiled code
 * comes from {@link LoansContractRegistry}'s derivation over the committed {@code loans-v4.plutus.json};
 * the config reference input carries the {@code ConfigDatum} recorded off preview. Everything else is
 * synthetic, so the test runs cold: no network, no key, no wallet.
 *
 * <h2>Unlike the pool-create mint, the datum <em>is</em> arbitrated here</h2>
 * A pool borrow spends the pool, so {@code pool_borrow_action} reads the pool datum, and mints the loan
 * and both bonds, so {@code loan.loan} and both {@code bond} validators run. Every structural claim in
 * the ground truth — the pool continuation at absolute output 0, the exact-minus-principal pool value,
 * the loan output's {@code dosProtection} shape and byte-identical {@code equals_data} datum, the
 * lender bond's committed-hash datum, the shared bond asset name, the finite hour-bounded validity —
 * is checked by a live validator, not merely by this repo's model.
 *
 * <h2>Assertions read the artefact, never the report</h2>
 * The {@link EvaluationResult} list is used only for the redeemer count and ex-units. Every structural
 * claim is read off the <em>deserialised</em> {@link Transaction}.
 *
 * <h2>Falsifiability</h2>
 * Each adversarial case is produced by breaking the honest build — a byte-surgery on a fresh copy, or a
 * seam-disabled {@link PoolBorrowTransactionBuilder#buildNaive rebuild} — so the honest transaction is
 * never mutated, and each mutant is asserted to differ from it. Where a validator <em>returns False</em>
 * the refusal is asserted to name the exact {@code RedeemerError { tag, index }}; where the deployed code
 * <em>aborts</em> ({@code expect} on the pool datum, the bond datum or the validity bounds) the machine
 * cannot name a refuser, so the abort's shape is pinned instead. See {@link EvalFixtures}'s
 * <em>Harness limitations</em>: a failure proves the named redeemer refused and nothing about any
 * redeemer after it.
 */
@Slf4j
class PoolBorrowDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.thirdDeploymentRegistry();
    private static final String FUNDER = LoanFixtures.botAddress();
    private static final Address BORROWER = new Address(FUNDER);
    private static final PoolFixtures.PoolParameters PARAMS = PoolFixtures.defaults();

    private static final long WANTED_PRINCIPAL = PARAMS.principalLovelace();          // 5 ADA
    private static final long POOL_LOVELACE = PARAMS.poolLiquidityLovelace();          // 52 ADA
    private static final long LOAN_OUTPUT_LOVELACE = 2_500_000L;
    private static final long BOND_LOVELACE = 1_500_000L;
    private static final long NEEDED_COLLATERAL =
            PoolFixtures.neededCollateral(PARAMS, WANTED_PRINCIPAL).longValueExact(); // 10 tFLDT-M

    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_100L;                             // 100 s window
    private static final long VALID_TO_MILLIS = millisOf(VALID_TO_SLOT);

    /** S1's own pool-create transaction; its output 0 becomes the borrow's pool input. */
    private static final Transaction POOL_CREATE = poolCreate();
    private static final String POOL_CREATE_TX = TransactionUtil.getTxHash(POOL_CREATE);
    private static final TransactionInput POOL_REF = new TransactionInput(POOL_CREATE_TX, 0);
    private static final String BOND_ASSET_NAME = PoolTxEncoder.bondAssetName(POOL_REF);
    private static final String POOL_DATUM_HEX =
            PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PARAMS)).serializeToHex();

    private static final String FUNDER_TX = "ee".repeat(32);
    private static final String COLLATERAL_TX = "ef".repeat(32);

    /** The Mint redeemer index of the loan policy: policy ids sort loan < lender bond < borrower bond. */
    private static final BigInteger MINT_LOAN = BigInteger.ZERO;
    private static final BigInteger MINT_LENDER_BOND = BigInteger.ONE;
    /** The withdraw redeemer index of pool_borrow_action, as AIKEN names it: 2fd3… sorts before pool.pool. */
    private static final BigInteger WITHDRAW_POOL_BORROW_ACTION = BigInteger.ZERO;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * The honest pool borrow evaluates against all six deployed validators. Logs each redeemer's real
     * ex-units, the total, and the serialised size against the real 16,384-byte ceiling.
     */
    @Test
    void thePoolBorrowEvaluatesAgainstTheDeployedValidators() throws Exception {
        Transaction tx = honest();

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe(), REGISTRY, extraScripts());
        assertEquals(6, results.size(), "six scripts run: loan + 2 bond mints, the pool spend, 2 rewards");

        long mem = 0;
        long steps = 0;
        for (EvaluationResult r : results) {
            log.info("Pool borrow ex-units: tag={} index={} mem={} steps={}",
                    r.getRedeemerTag(), r.getIndex(), r.getExUnits().getMem(), r.getExUnits().getSteps());
            mem += r.getExUnits().getMem().longValue();
            steps += r.getExUnits().getSteps().longValue();
        }
        int size = tx.serialize().length;
        log.info("Pool borrow TOTAL ex-units: mem={} steps={}; serialised size {} of 16384 bytes",
                mem, steps, size);
        assertTrue(size <= 16_384, "the reference-script transaction must fit the real max tx size");
    }

    /**
     * The layout measurement the seam demands: the naive build (cardano-client-lib's 1-ADA withdrawal
     * dummy at output 0, the pool pushed to output 1) beside the fixed build (pool at output 0). This is
     * evidence, not a comment — the two layouts are logged output by output.
     */
    @Test
    void theAbsoluteOutputZeroSeamMovesThePoolToIndexZero() {
        Transaction naive = builder().buildNaive(request());
        Transaction fixed = honest();

        layout("BEFORE the seam (naive)", naive);
        layout("AFTER the seam (fixed)", fixed);

        assertFalse(isPoolOutput(naive.getBody().getOutputs().get(0)),
                "the naive build must NOT have the pool at output 0 — that is the whole hazard");
        assertTrue(isPoolOutput(fixed.getBody().getOutputs().get(0)),
                "the fixed build must have the pool continuation at output 0");
        assertEquals(POOL_DATUM_HEX, fixed.getBody().getOutputs().get(0).getInlineDatum().serializeToHex(),
                "output 0's datum must be the pool input's bytes, verbatim");
    }

    /** The witness set carries no Plutus scripts — every validator travels by reference input. */
    @Test
    void everyValidatorTravelsByReferenceInput() {
        Transaction tx = honest();
        assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null
                        || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "the six validators must be resolved from reference inputs, not the witness set");
        assertEquals(7, tx.getBody().getReferenceInputs().size(),
                "seven reference inputs: the config and the six published reference scripts");
    }

    // ======================================================================================
    // Falsifiability
    // ======================================================================================

    /** The unfixed layout: the CCL dummy at 0, the pool at 1. The pool datum read aborts on the dummy. */
    @Test
    void theUnfixedLayoutIsRefused() {
        Transaction naive = builder().buildNaive(request());
        assertFalse(isPoolOutput(naive.getBody().getOutputs().get(0)), "precondition: pool not at 0");
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(naive, universe(), REGISTRY, extraScripts());
        log.info("MUTATION [unfixed layout] refusal: {}", outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "the pool at output 1 with a dummy at 0 must be refused");
        assertTrue(outcome.detail().contains("tag: \"Withdraw\", index: 0"),
                "validate_output_to_pool reads output 0 (the dummy) and aborts inside pool_borrow_action's "
                        + "withdraw redeemer: " + outcome.detail());
    }

    /** The pool continuation datum changed to different content — equals_data refuses. */
    @Test
    void aPoolContinuationDatumWithDifferingBytesIsRefused() {
        Transaction m = copy();
        PlutusData altered = PoolTxEncoder.poolDatum(new PoolTxEncoder.PoolDatum(
                PoolFixtures.NO_PERMISSIONED_CONDITION, RequestTxEncoder.unit(),
                PoolFixtures.commonData(PARAMS), PoolFixtures.lenderAuth(), PoolFixtures.lenderBondAddress(),
                PoolFixtures.bondInlineDatumHash(PoolFixtures.templateBondDatum(PARAMS.liquidationFeePerMille())),
                List.of(PoolFixtures.COLLATERAL),
                List.of(BigInteger.valueOf(PARAMS.collateralPerPrincipalNumerator())),
                List.of(BigInteger.valueOf(PARAMS.collateralPerPrincipalDivider())),
                true)); // dynamicCollateralPrice flipped false -> true
        m.getBody().getOutputs().get(0).setInlineDatum(altered);
        assertMutated(m, "pool continuation datum re-serialised to differing bytes");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "pool continuation datum differs");
    }

    /** The pool continuation short by one lovelace — the value equality is exact. */
    @Test
    void aPoolShortByOneLovelaceIsRefused() {
        Transaction m = copy();
        TransactionOutput pool = m.getBody().getOutputs().get(0);
        pool.getValue().setCoin(pool.getValue().getCoin().subtract(BigInteger.ONE));
        assertMutated(m, "pool short by one lovelace");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "pool short by one lovelace");
    }

    /** The pool continuation long by one lovelace — the value equality is exact both ways. */
    @Test
    void aPoolLongByOneLovelaceIsRefused() {
        Transaction m = copy();
        TransactionOutput pool = m.getBody().getOutputs().get(0);
        pool.getValue().setCoin(pool.getValue().getCoin().add(BigInteger.ONE));
        assertMutated(m, "pool long by one lovelace");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "pool long by one lovelace");
    }

    /** A fourth asset on the loan output — dosProtection demands flatten == 3 for token collateral. */
    @Test
    void aFourthAssetOnTheLoanOutputIsRefused() {
        Transaction m = copy();
        TransactionOutput loan = outputAt(m, loanOutputAddress());
        loan.getValue().getMultiAssets().add(new MultiAsset("cc".repeat(28),
                new ArrayList<>(List.of(new Asset("0x" + "dd".repeat(28), BigInteger.ONE)))));
        assertMutated(m, "fourth asset on the loan output");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "loan output with a fourth asset");
    }

    /** The loan output's stake credential differs from the borrower's — correctDestination refuses. */
    @Test
    void aLoanOutputStakeCredentialDifferingFromTheBorrowerIsRefused() {
        Transaction m = copy();
        TransactionOutput loan = outputAt(m, loanOutputAddress());
        String wrong = AddressProvider.getBaseAddress(
                Credential.fromScript(REGISTRY.getLoanSpendScriptHash()),
                Credential.fromKey("99".repeat(28)), LoanFixtures.NETWORK).getAddress();
        loan.setAddress(wrong);
        assertMutated(m, "loan output stake credential differs");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "loan output at the wrong stake credential");
    }

    /** The loan datum's lendDate set to validFrom, not validTo — equals_data refuses. */
    @Test
    void aLoanDatumWithLendDateAtValidFromIsRefused() {
        Transaction m = copy();
        TransactionOutput loan = outputAt(m, loanOutputAddress());
        long validFromMillis = millisOf(VALID_FROM_SLOT);
        loan.setInlineDatum(LoanFixtures.encode(PoolFixtures.borrowLoanDatum(
                PARAMS, PoolFixtures.poolAssetName(), WANTED_PRINCIPAL, validFromMillis)));
        assertMutated(m, "loan datum lendDate = validFrom");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "loan datum lendDate is validFrom not validTo");
    }

    /** The lender bond output address one byte off — the address equality refuses. */
    @Test
    void aLenderBondOutputAddressOneByteOffIsRefused() {
        Transaction m = copy();
        TransactionOutput bond = outputAt(m, PoolFixtures.lenderBondAddress().getAddress());
        String off = AddressProvider.getEntAddress(Credential.fromScript(
                flipLastByte(REGISTRY.getLenderManagerSpendScriptHash())), LoanFixtures.NETWORK).getAddress();
        bond.setAddress(off);
        assertMutated(m, "lender bond address one byte off");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "lender bond output at the wrong address");
    }

    /**
     * The lender bond datum swapped for the <b>fee-0</b> bond datum against the fee-100 pool — its hash
     * no longer matches the pool's committed {@code lenderBondInlineDatumHash}. This is the mutation that
     * catches a factory silently reverting the bot fee: the two datums differ only in
     * {@code liquidationFeePerMille}.
     */
    @Test
    void aLenderBondDatumHashingToTheWrongValueIsRefused() {
        Transaction m = copy();
        TransactionOutput bond = outputAt(m, PoolFixtures.lenderBondAddress().getAddress());
        bond.setInlineDatum(LoanFixtures.encode(PoolFixtures.templateBondDatum(0L)));
        assertMutated(m, "lender bond datum at fee 0");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "lender bond datum hashes to the wrong value");
    }

    /** The lender bond output stripped of its datum — bonds_sent_to_both_parties aborts at :331. */
    @Test
    void aLenderBondOutputWithNoDatumIsRefused() {
        Transaction m = copy();
        TransactionOutput bond = outputAt(m, PoolFixtures.lenderBondAddress().getAddress());
        bond.setInlineDatum(null);
        assertMutated(m, "lender bond output with no datum");
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(m, universe(), REGISTRY, extraScripts());
        log.info("MUTATION [lender bond no datum] refusal: {}", outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "a lender bond output with no inline datum must be refused");
        assertTrue(outcome.detail().contains("tag: \"Withdraw\", index: 0"),
                "the expect InlineDatum(..) aborts inside pool_borrow_action's withdraw redeemer: "
                        + outcome.detail());
    }

    /** originWithdrawRedeemerIndex one too low — loan.loan's check_mint reads the wrong redeemer. */
    @Test
    void anOriginWithdrawRedeemerIndexTooLowIsRefused() {
        assertRefused(withOriginWithdrawIndexDelta(-1), "Mint", MINT_LOAN,
                "originWithdrawRedeemerIndex one too low");
    }

    /** originWithdrawRedeemerIndex one too high — the referenced redeemer is not the pool withdraw. */
    @Test
    void anOriginWithdrawRedeemerIndexTooHighIsRefused() {
        assertRefused(withOriginWithdrawIndexDelta(+1), "Mint", MINT_LOAN,
                "originWithdrawRedeemerIndex one too high");
    }

    /** The lender and borrower token indexes swapped — the lender token is sought at the wrong output. */
    @Test
    void swappedLenderAndBorrowerTokenIndexesAreRefused() {
        Transaction m = copy();
        Redeemer r = redeemer(m, RedeemerTag.Reward, PoolBorrowTransactionBuilderProbe::isBorrowAction);
        ConstrPlutusData rd = (ConstrPlutusData) r.getData();
        ListPlutusData actions = (ListPlutusData) rd.getData().getPlutusDataList().get(1);
        List<PlutusData> bf = ((ConstrPlutusData) actions.getPlutusDataList().get(0)).getData()
                .getPlutusDataList();
        PlutusData tmp = bf.get(1);
        bf.set(1, bf.get(2));
        bf.set(2, tmp);
        assertMutated(m, "lender/borrower token indexes swapped");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "lender/borrower token indexes swapped");
    }

    /** All three mints renamed to distinct names — the loan NFT no longer matches hash_output_ref. */
    @Test
    void threeMintsUnderThreeDifferentNamesAreRefused() {
        Transaction m = copy();
        String[] names = {"a1".repeat(28), "b2".repeat(28), "c3".repeat(28)};
        List<MultiAsset> mint = m.getBody().getMint();
        for (int i = 0; i < mint.size(); i++) {
            mint.get(i).getAssets().set(0, new Asset("0x" + names[i], BigInteger.ONE));
        }
        assertMutated(m, "three mints under three different names");
        assertRefused(m, "Mint", MINT_LOAN, "three mints under three different names");
    }

    /** A bond redeemer naming a wallet input's reference instead of the pool's — the name check refuses. */
    @Test
    void aBondRedeemerNamingAWalletInputIsRefused() {
        Transaction m = copy();
        Redeemer r = redeemer(m, RedeemerTag.Mint, red -> red.getIndex().equals(MINT_LENDER_BOND));
        r.setData(PoolTxEncoder.bondRedeemer(List.of(new TransactionInput(FUNDER_TX, 0))));
        assertMutated(m, "bond redeemer names a wallet input ref");
        assertRefused(m, "Mint", MINT_LENDER_BOND, "bond redeemer names a wallet input reference");
    }

    /** An unbounded validity range — the finite-bound expect aborts at pool_borrow_action.ak:53. */
    @Test
    void anUnboundedValidityRangeIsRefused() {
        Transaction m = copy();
        m.getBody().setValidityStartInterval(0);
        m.getBody().setTtl(0);
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(m, universe(), REGISTRY, extraScripts());
        log.info("MUTATION [unbounded validity] refusal: {}", outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "an unbounded validity range must be refused");
        assertTrue(outcome.detail().contains("tag: \"Withdraw\", index: 0"),
                "expect Finite(validFrom) aborts inside pool_borrow_action's withdraw redeemer: "
                        + outcome.detail());
    }

    /** A validity range spanning more than an hour — validity_range_within_an_hour returns False. */
    @Test
    void aValidityRangeOverAnHourIsRefused() {
        long wideToSlot = VALID_FROM_SLOT + 3601; // 3,601,000 ms > 3,600,000 (the smallest whole-slot miss)
        long wideToMillis = millisOf(wideToSlot);
        PoolBorrowTransactionBuilder.Request wide = requestWith(VALID_FROM_SLOT, wideToSlot,
                LoanFixtures.encode(PoolFixtures.borrowLoanDatum(
                        PARAMS, PoolFixtures.poolAssetName(), WANTED_PRINCIPAL, wideToMillis)));
        Transaction m = builder().build(wide);
        assertRefused(m, "Withdraw", WITHDRAW_POOL_BORROW_ACTION, "validity range spans more than an hour");
    }

    // ======================================================================================
    // The bot can liquidate what the factory emits
    // ======================================================================================

    /**
     * The built loan and lender-bond outputs, decoded through the production converters and fed to
     * {@link LiquidationCandidateScanner} at a time just after {@code validTo}, are classified
     * <b>buildable</b> with a fee of exactly {@code collateral × 100 / 1000}. This is the fee-100
     * invariant's teeth: a factory that reverted the bot fee to the pool's 90‰ penalty (the transposition
     * S1's audit removed) would decode to {@code collateral × 90 / 1000} and fail the exact-fee assertion.
     */
    @Test
    void theBotCanLiquidateTheEmittedLoanAtFee100() throws Exception {
        Transaction tx = honest();
        TransactionOutput loanOutput = outputAt(tx, loanOutputAddress());
        TransactionOutput bondOutput = outputAt(tx, PoolFixtures.lenderBondAddress().getAddress());

        LoanDatum loanDatum = new LoanDatumConverter().deserialize(loanOutput.getInlineDatum().serializeToHex());
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(100), bondDatum.liquidationFeePerMille(),
                "the emitted lender bond must decode to fee 100, not the pool's 90‰ penalty");

        Loan loan = new Loan(POOL_CREATE_TX, 3, loanOutputAddress(), BOND_ASSET_NAME,
                BigInteger.valueOf(NEEDED_COLLATERAL), BigInteger.valueOf(LOAN_OUTPUT_LOVELACE), loanDatum);
        LenderBond bond = new LenderBond(POOL_CREATE_TX, 1, PoolFixtures.lenderBondAddress().getAddress(),
                BOND_ASSET_NAME, bondOutput.getInlineDatum().serializeToHex(), bondDatum);

        long atMillis = VALID_TO_MILLIS + 1;
        LiquidationCandidateScanner scanner = new LiquidationCandidateScanner(
                new FakeLenderBondService(List.of(bond)), new FakeLoanService(List.of(loan)),
                provider(oracleClient(atMillis)));

        LiquidationAssessment assessment = scanner.scan(atMillis).assessments().stream()
                .filter(a -> BOND_ASSET_NAME.equals(a.bond().loanId()))
                .findFirst().orElseThrow();

        assertTrue(assessment.buildable(),
                "the factory loan must be buildable: " + assessment.exclusion() + " " + assessment.detail());
        assertEquals(BigInteger.valueOf(NEEDED_COLLATERAL * 100 / 1000), assessment.liquidationFee(),
                "the liquidation fee must be collateral × 100 / 1000 (fee 100, not the 90‰ penalty)");
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    private static Transaction HONEST;

    private static Transaction honest() {
        if (HONEST == null) {
            HONEST = builder().build(request());
        }
        return HONEST;
    }

    private static Transaction copy() {
        try {
            return Transaction.deserialize(honest().serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot copy the honest transaction", e);
        }
    }

    private Transaction withOriginWithdrawIndexDelta(int delta) {
        Transaction m = copy();
        Redeemer r = redeemer(m, RedeemerTag.Mint,
                red -> ((ConstrPlutusData) red.getData()).getData().getPlutusDataList().size() == 3);
        List<PlutusData> fields = ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
        BigInteger current = ((BigIntPlutusData) fields.get(2)).getValue();
        fields.set(2, BigIntPlutusData.of(current.add(BigInteger.valueOf(delta))));
        assertMutated(m, "originWithdrawRedeemerIndex delta " + delta);
        return m;
    }

    private static PoolBorrowTransactionBuilder builder() {
        return new PoolBorrowTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe()), LoanFixtures.protocolParams());
    }

    private static PoolBorrowTransactionBuilder.Request request() {
        return requestWith(VALID_FROM_SLOT, VALID_TO_SLOT, LoanFixtures.encode(
                PoolFixtures.borrowLoanDatum(PARAMS, PoolFixtures.poolAssetName(), WANTED_PRINCIPAL,
                        VALID_TO_MILLIS)));
    }

    private static PoolBorrowTransactionBuilder.Request requestWith(long fromSlot, long toSlot,
                                                                    PlutusData loanDatum) {
        return new PoolBorrowTransactionBuilder.Request(
                poolUtxo(), funderUtxo(), PoolFixtures.configUtxo(), referenceScriptUtxos(),
                FUNDER, BORROWER, WANTED_PRINCIPAL, 0L, PoolFixtures.TFLDT, NEEDED_COLLATERAL,
                LOAN_OUTPUT_LOVELACE, PoolFixtures.poolAssetName(), BOND_ASSET_NAME, loanDatum,
                PoolFixtures.lenderBondAddress().getAddress(),
                LoanFixtures.encode(PoolFixtures.templateBondDatum(PARAMS.liquidationFeePerMille())),
                BOND_LOVELACE, BOND_LOVELACE, fromSlot, toSlot);
    }

    private static Utxo poolUtxo() {
        return LoanFixtures.utxo(POOL_CREATE_TX, 0, PoolFixtures.poolAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(POOL_LOVELACE)),
                        Amount.asset(REGISTRY.getPoolPolicyId() + PoolFixtures.poolAssetName(), BigInteger.ONE)),
                POOL_DATUM_HEX);
    }

    private static Utxo funderUtxo() {
        return LoanFixtures.utxo(FUNDER_TX, 0, FUNDER,
                List.of(Amount.lovelace(BigInteger.valueOf(100_000_000L)),
                        Amount.asset(LoanFixtures.unit(PoolFixtures.TFLDT), BigInteger.valueOf(20_000_000L))),
                null);
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>();
        universe.add(poolUtxo());
        universe.add(funderUtxo());
        universe.add(LoanFixtures.adaUtxo(COLLATERAL_TX, 1, FUNDER, 60_000_000L));
        universe.add(PoolFixtures.configUtxo());
        universe.addAll(referenceScriptUtxos());
        return universe;
    }

    private static List<Utxo> referenceScriptUtxos() {
        List<Utxo> utxos = new ArrayList<>();
        for (String hash : List.of(REGISTRY.getPoolPolicyId(), REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolBorrowActionScriptHash(), REGISTRY.getLoanPolicyId(),
                REGISTRY.getLenderBondPolicyId(), REGISTRY.getBorrowerBondPolicyId())) {
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

    private static List<PlutusScript> extraScripts() {
        return List.of(REGISTRY.getPoolScript(), REGISTRY.getPoolSpendScript(),
                REGISTRY.getPoolBorrowActionScript(), REGISTRY.getLoanScript(),
                REGISTRY.getLenderBondScript(), REGISTRY.getBorrowerBondScript());
    }

    private static Transaction poolCreate() {
        PoolCreateTransactionBuilder poolBuilder = new PoolCreateTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(PoolFixtures.universe(FUNDER)), LoanFixtures.protocolParams());
        PoolCreateTransactionBuilder.Request request = new PoolCreateTransactionBuilder.Request(
                PoolFixtures.seedUtxo(FUNDER), PoolFixtures.configUtxo(),
                PoolFixtures.poolPolicyRefScriptUtxo(), FUNDER, PoolFixtures.poolAddress(),
                PoolFixtures.poolAssetName(),
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults())),
                PoolFixtures.defaults().poolLiquidityLovelace(),
                // T-024: every pool this factory creates now mints a PoolManager NFT beside its pool
                // NFT. Nothing on the borrow path reads it — pool_borrow_action never mentions the
                // PoolManager — so this only keeps the fixture's pool the same pool the create builder
                // actually emits, rather than a shape we no longer produce.
                PoolFixtures.poolManagerAddress(),
                PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()),
                PoolFixtures.POOL_MANAGER_LOVELACE);
        return poolBuilder.build(request);
    }

    private static String loanOutputAddress() {
        return AddressProvider.getBaseAddress(
                Credential.fromScript(REGISTRY.getLoanSpendScriptHash()),
                BORROWER.getDelegationCredential().orElseThrow(), LoanFixtures.NETWORK).getAddress();
    }

    // ---- assertions and surgery helpers ----------------------------------------------------------

    private static void assertRefused(Transaction mutated, String tag, BigInteger index, String what) {
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe(), REGISTRY, extraScripts());
        log.info("MUTATION [{}] refusal: {}", what, outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), what + " must be refused");
        String expected = "tag: \"" + tag + "\", index: " + index;
        assertTrue(outcome.detail().contains(expected),
                "expected [" + expected + "] refusing " + what + ", got: " + outcome.detail());
    }

    private static void assertMutated(Transaction mutated, String what) {
        try {
            assertNotEquals(HexUtil.encodeHexString(honest().serialize()),
                    HexUtil.encodeHexString(mutated.serialize()),
                    what + " did not change the transaction bytes");
        } catch (Exception e) {
            throw new AssertionError("cannot serialise for the mutation check", e);
        }
    }

    private static Redeemer redeemer(Transaction tx, RedeemerTag tag,
                                     java.util.function.Predicate<Redeemer> predicate) {
        return tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == tag && predicate.test(r))
                .findFirst().orElseThrow(() -> new AssertionError("no matching redeemer"));
    }

    private static TransactionOutput outputAt(Transaction tx, String address) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .findFirst().orElseThrow(() -> new AssertionError("no output at " + address));
    }

    private static boolean isPoolOutput(TransactionOutput output) {
        return PoolFixtures.poolAddress().equals(output.getAddress())
                && output.getInlineDatum() != null
                && POOL_DATUM_HEX.equalsIgnoreCase(output.getInlineDatum().serializeToHex());
    }

    private static void layout(String label, Transaction tx) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        log.info("=== layout {} ({} outputs) ===", label, outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput o = outputs.get(i);
            String paymentCred = new Address(o.getAddress()).getPaymentCredentialHash()
                    .map(HexUtil::encodeHexString).map(h -> h.substring(0, 8)).orElse("--------");
            int flatten = 1 + o.getValue().getMultiAssets().stream()
                    .mapToInt(ma -> ma.getAssets().size()).sum();
            String datum = o.getInlineDatum() == null ? "none"
                    : o.getInlineDatum().serializeToHex().substring(0, 16);
            log.info("  output[{}] paymentCred={} flatten={} datum={}", i, paymentCred, flatten, datum);
        }
    }

    private static String flipLastByte(String hash) {
        String last = hash.substring(hash.length() - 2);
        String flipped = String.format("%02x", (Integer.parseInt(last, 16) ^ 0x01));
        return hash.substring(0, hash.length() - 2) + flipped;
    }

    private static long millisOf(long slot) {
        return LoanFixtures.converters().slot().slotToTime(slot).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---- scanner-acceptance wiring ----------------------------------------------------------------

    /** The tFLDT oracle entry priced at 0.5 lovelace/unit — the born-liquidatable design price. */
    private static FluidOracleClient oracleClient(long atMillis) throws Exception {
        AssetType oracle = PoolFixtures.TFLDT_FLUID_ORACLE;
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "%s", "assetName": "%s" },
                  "fluidOracle": {
                    "policyId": "%s", "assetName": "%s",
                    "rewardAddress": "%s",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "multisigOracle": { "publicKeys": ["%s"], "requiredSignatures": 1 },
                  "supportedOracle": {
                    "multisig": {
                      "validFrom": %d, "validTo": %d,
                      "tokenPriceInLovelaces": 1, "tokenPriceDenominator": 2,
                      "multisigOracle": {
                        "requiredSignatures": 1,
                        "signatures": [{ "publicKey": "%s", "signature": "ff" }]
                      }
                    }
                  }
                }]
                """.formatted(PoolFixtures.TFLDT.policyId(), PoolFixtures.TFLDT.assetName(),
                oracle.policyId(), oracle.assetName(), LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()),
                "0".repeat(64), atMillis - 1_000_000L, atMillis + 1_000_000L, "0".repeat(64));
        FluidOracleClient client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        return client;
    }

    private static ObjectProvider<FluidOracleClient> provider(FluidOracleClient client) {
        return new ObjectProvider<>() {
            @Override
            public FluidOracleClient getObject() {
                return client;
            }

            @Override
            public FluidOracleClient getObject(Object... args) {
                return client;
            }

            @Override
            public FluidOracleClient getIfAvailable() {
                return client;
            }

            @Override
            public FluidOracleClient getIfUnique() {
                return client;
            }
        };
    }

    private static final class FakeLoanService extends LoanService {
        private final List<Loan> loans;

        FakeLoanService(List<Loan> loans) {
            super(null, null);
            this.loans = loans;
        }

        // census(), not findAll(): the scanner asks for the census so the T-060 blindness signal
        // travels with the assessments. Overriding findAll() alone leaves this fake BYPASSED — which
        // is exactly what happened when the seam moved, in three fakes at once.
        @Override
        public Census census() {
            return new Census(loans, loans.size(), 0, 0);
        }
    }

    private static final class FakeLenderBondService extends LenderBondService {
        private final List<LenderBond> bonds;

        FakeLenderBondService(List<LenderBond> bonds) {
            super(null, null);
            this.bonds = bonds;
        }

        @Override
        public List<LenderBond> findAll() {
            return bonds;
        }
    }

    /** Identifies the pool_borrow_action Reward redeemer by content: its second field is a list. */
    private static final class PoolBorrowTransactionBuilderProbe {
        static boolean isBorrowAction(Redeemer r) {
            PlutusData data = r.getData();
            return data instanceof ConstrPlutusData constr
                    && constr.getData().getPlutusDataList().size() == 2
                    && constr.getData().getPlutusDataList().get(1) instanceof ListPlutusData;
        }
    }
}
