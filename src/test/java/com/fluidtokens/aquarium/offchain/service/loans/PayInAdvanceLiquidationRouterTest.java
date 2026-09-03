package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>Reviewer's orientation — what this class proves, and what it does not</h2>
 * <b>Proves:</b> the ROUTING decision only — that a bond whose datum says
 * {@code shouldLiquidationConvertToPrincipal == True} reaches the pay-in-advance builder rather than
 * the plain one, and that the two shapes the seam cannot model are refused cleanly instead of
 * throwing. <b>Does NOT prove:</b> that the routed transaction is valid, buildable against live data,
 * or acceptable to the chain — the builder's own tests and the live check answer that.
 * <b>Evaluator:</b> none; this class does not evaluate scripts at all.
 *
 * The routing seam in isolation: given an already-resolved convert candidate,
 * {@link PayInAdvanceLiquidationRouter} either builds the {@code LiquidateAndPayInAdvance} transaction
 * through the promoted, submit-incapable {@link LiquidatePayInAdvanceTransactionBuilder} or refuses it
 * cleanly. Nothing here signs, submits, or evaluates against a chain — the builder has a null
 * transaction processor and this test only inspects the unsigned body.
 *
 * <h2>The fixture is the frozen {@code f855d1b4…} preview loan</h2>
 * The loan/bond/oracle bytes are the same real preview shape {@link LiquidatePayInAdvanceDryEvalTest}
 * pins — 100 000 000 tFLDT of collateral against a 28 000 000-lovelace (ada) principal, with a lender
 * bond whose {@code shouldLiquidationConvertToPrincipal == True}. They are <b>duplicated</b> here on
 * purpose so this test owns its inputs and does not couple to the dry-eval rig or {@code LoanFixtures}.
 */
class PayInAdvanceLiquidationRouterTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the real loan, transaction f855d1b4… on preview (duplicated from the dry-eval fixture) --

    private static final String LOAN_TX =
            "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;

    private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";

    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    /**
     * Inline datum of {@code f855d1b4…#3}, verbatim. {@code shouldLiquidationConvertToPrincipal == True}
     * ({@code d87a80}) and {@code liquidationFeePerMille == 50} ({@code 1832}). Its {@code poolId} is
     * non-empty — which is exactly why routing is keyed on the convert flag and never on {@code poolId}.
     */
    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87a80"
                    + "1832581d00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11d8799f4040ff"
                    + "ff";

    private static final String LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";
    private static final String BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";

    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_810_200L;

    // ---- the oracle, from the dry-eval fixture (the tFLDT entry) ---------------------------------

    private static final AssetType ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
    private static final AssetType C3_FEED_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");

    private static final String ORACLE_SCRIPT_HASH =
            "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";

    private static final TransactionInput ORACLE_REF_INPUT = new TransactionInput(
            "cc4721afdf4721f8f179b3afddb8e096805c0fad16afe54687d7368d12bd769c", 0);
    private static final TransactionInput ORACLE_REF_SCRIPT = new TransactionInput(
            "ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f", 0);
    private static final TransactionInput C3_PROVIDER = new TransactionInput(
            "a17501465ed79dbc6cb25e2e99edbc421b1baa9d100b6780da89770702b235a5", 0);

    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";
    private static final String C3_PROVIDER_DATUM_HEX =
            "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff";

    private static final BigInteger PRICE = BigInteger.valueOf(338163);
    private static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(1_000_000);

    // ---- the instant. This loan's lendDate is 1_787_216_064_000; the window sits ~1h after it. ---

    private static final long LEND_DATE = 1_787_216_064_000L;
    private static final long NOW = LEND_DATE + 3_600_000L;
    private static final long VALID_TO_MILLIS = NOW + 120_000L;
    private static final long FEED_VALID_FROM = NOW - 35_555L;
    private static final long FEED_VALID_TO = FEED_VALID_FROM + 600_000L;

    // ---- the redeemer arithmetic, pinned in the dry-eval test --------------------------------

    private static final long REMAINING_DEBT = 28_000_147L;
    private static final long EQUITY = 8_919_184L;
    private static final long LIQUIDATION_FEE = 5_000_000L;

    // ---- the synthetic remainder ----------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 60_000_000L);

    /** A selector that supplies the fixture wallet whatever the payout — for tests not about T-052. */
    private static Function<BigInteger, Optional<Utxo>> anyWallet() {
        return payout -> Optional.of(WALLET_UTXO);
    }

    // ======================================================================================
    // (1) the deliverable — a convert loan routed to the pay-in-advance builder
    // ======================================================================================

    /**
     * The frozen convert loan, routed through {@link PayInAdvanceLiquidationRouter}, produces the
     * pay-in-advance shape: a parent {@code lender_manager.lenderManager} withdrawal whose redeemer
     * carries {@code LenderManagerAction.LiquidateAndPayInAdvance} (constructor index 3), and exactly
     * two asset-manager outputs (the borrower compensation and the lender's paid-in-advance ada).
     */
    @Test
    void aConvertLoanIsRoutedToThePayInAdvanceBuilder() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), anyWallet(), NOW, VALID_TO_MILLIS);

        // The parent LenderManager redeemer carries LiquidateAndPayInAdvance (constructor index 3).
        String parentReward = LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash());
        ConstrPlutusData parent = (ConstrPlutusData) rewardRedeemer(tx, parentReward).getData();
        ConstrPlutusData action = (ConstrPlutusData) parent.getData().getPlutusDataList().get(1);
        assertEquals(3, action.getAlternative(),
                "the parent LenderManager must carry LiquidateAndPayInAdvance (constructor index 3)");

        // Exactly two asset-manager outputs: the borrower compensation and the lender converted ada.
        assertEquals(2, assetManagerOutputs(tx).size(),
                "the pay-in-advance layout emits a borrower compensation and a lender converted output");
    }

    // ======================================================================================
    // (2) a non-convert loan is not routed to pay-in-advance
    // ======================================================================================

    /**
     * The routing key is {@code shouldLiquidationConvertToPrincipal}, never {@code poolId}. The frozen
     * convert bond selects the pay-in-advance seam even though its {@code poolId} is non-empty; a
     * plain bond does not, even given the same non-empty {@code poolId}. This pins the exact predicate
     * {@code LiquidationExecutor} branches on.
     */
    @Test
    void aNonConvertLoanIsNotRoutedToPayInAdvance() {
        LenderManagerDatum convertBond = bondDatum();
        assertTrue(convertBond.shouldLiquidationConvertToPrincipal(),
                "the frozen f855 bond is the convert shape — routed to pay-in-advance");
        assertFalse(convertBond.poolId().isEmpty(),
                "and its poolId is non-empty, so poolId cannot be what routes it");

        LenderManagerDatum plainBond = LoanFixtures.bondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada(), convertBond.poolId());
        assertFalse(plainBond.shouldLiquidationConvertToPrincipal(),
                "a plain bond with the very same non-empty poolId is NOT routed to pay-in-advance");
    }

    // ======================================================================================
    // (3) the clean refusals — never a crash, a quarantine or a transaction
    // ======================================================================================

    /** A convert loan whose equity is not strictly positive is refused cleanly, before the builder runs. */
    @Test
    void nonPositiveEquityIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.ZERO);
        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), anyWallet(), NOW, VALID_TO_MILLIS));
        assertEquals("pay-in-advance not yet modelled for non-positive equity", refusal.getMessage());
    }

    /** A convert loan whose principal is not ada is refused cleanly, before the builder runs. */
    @Test
    void nonAdaPrincipalIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY),
                nonAdaPrincipalLoanDatum());
        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), anyWallet(), NOW, VALID_TO_MILLIS));
        assertEquals("pay-in-advance not yet modelled for non-ada principal", refusal.getMessage());
    }

    // ======================================================================================
    // fixture plumbing
    // ======================================================================================

    private static PayInAdvanceLiquidationRouter router() {
        return new PayInAdvanceLiquidationRouter(REGISTRY, converters(), configuration(),
                new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams()));
    }

    private static AppConfig.LiquidationConfiguration configuration() {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, false, 60L, 120L, 30L,
                BigInteger.valueOf(1_500_000L), 200, 30L);
        // The market gate defaults to DISABLED for every market (Giovanni's defensive-default
        // ruling). A pay-in-advance test must therefore name the market it operates in, exactly
        // as an operator must — an ample ada cap, so the gate is satisfied and never the thing
        // under test here.
        configuration.setMarkets(java.util.List.of(
                anticipateMarket("lovelace", 1_000_000_000_000L)));
        return configuration;
    }

    private static CardanoConverters converters() {
        return LoanFixtures.converters();
    }

    private static LoanDatum loanDatum() {
        return new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
    }

    /** The frozen loan datum with only its principal (and principal-oracle) asset swapped to a token. */
    private static LoanDatum nonAdaPrincipalLoanDatum() {
        LoanDatum ada = loanDatum();
        AssetType token = new AssetType("aa".repeat(28), "abcd");
        return new LoanDatum(ada.doneRecasts(), ada.principalAmount(), ada.lendDate(),
                ada.repaidInstallments(), ada.interestRate(), ada.totalInstallments(), token, token,
                ada.installmentPeriod(), ada.initialGracePeriod(), ada.liquidationMode(),
                ada.repaymentMode(), ada.repaymentTimeWindow(), ada.penaltyFeeForLateRepayment(),
                ada.repaymentReceipts(), ada.originId(), ada.collateral());
    }

    private static LenderManagerDatum bondDatum() {
        return new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
    }

    private static Loan loan(LoanDatum datum) {
        return new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);
    }

    private static Utxo loanUtxo() {
        return LoanFixtures.utxo(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), LOAN_DATUM_HEX);
    }

    private static LenderBond bond() {
        return new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID, BOND_DATUM_HEX,
                bondDatum());
    }

    private static Utxo bondUtxo() {
        return LoanFixtures.utxo(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), BOND_DATUM_HEX);
    }

    private static OracleEntry oracle() {
        return LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);
    }

    private static Map<String, OracleEntry> oraclesByUnit() {
        OracleEntry oracle = oracle();
        return Map.of(oracle.oracleToken().toUnit(), oracle);
    }

    private static LiquidationAssessment convertAssessment(BigInteger equity) {
        return convertAssessment(equity, loanDatum());
    }

    private static LiquidationAssessment convertAssessment(BigInteger equity, LoanDatum datum) {
        // A sanity check on the fixture: the frozen bond really is the convert shape this seam routes.
        assertTrue(bondDatum().shouldLiquidationConvertToPrincipal());
        assertTrue(datum.liquidationMode() instanceof LiquidationMode.Liquidation);
        return LiquidationAssessment.buildable(bond(), loan(datum), "f855 convert fixture",
                BigInteger.valueOf(REMAINING_DEBT), equity, false, BigInteger.valueOf(LIQUIDATION_FEE));
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                loanUtxo(), bondUtxo()));
        universe.add(LoanFixtures.utxo(ORACLE_REF_INPUT.getTransactionId(), ORACLE_REF_INPUT.getIndex(),
                ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(LoanFixtures.unit(ORACLE_NFT), BigInteger.ONE)), null));
        universe.add(Utxo.builder()
                .txHash(ORACLE_REF_SCRIPT.getTransactionId())
                .outputIndex(ORACLE_REF_SCRIPT.getIndex())
                .address(ORACLE_ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                .referenceScriptHash(ORACLE_SCRIPT_HASH)
                .build());
        universe.add(LoanFixtures.utxo(C3_PROVIDER.getTransactionId(), C3_PROVIDER.getIndex(),
                C3_PROVIDER_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(LoanFixtures.unit(C3_FEED_NFT), BigInteger.ONE)),
                C3_PROVIDER_DATUM_HEX));
        return universe;
    }

    // ---- reading the built transaction back ------------------------------------------------------

    private static List<TransactionOutput> assetManagerOutputs(Transaction tx) {
        String assetManagerSpend = REGISTRY.getAssetManagerSpendScriptHash();
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    private static Redeemer rewardRedeemer(Transaction tx, String rewardAddress) {
        int index = withdrawalIndexOf(tx, rewardAddress);
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward && redeemer.getIndex().intValue() == index) {
                return redeemer;
            }
        }
        throw new AssertionError("no reward redeemer for the withdrawal at " + rewardAddress);
    }

    private static int withdrawalIndexOf(Transaction tx, String rewardAddress) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        for (int i = 0; i < withdrawals.size(); i++) {
            if (withdrawals.get(i).getRewardAddress().equals(rewardAddress)) {
                return i;
            }
        }
        throw new AssertionError("no withdrawal at " + rewardAddress);
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    // ======================================================================================
    // T-052 — the wallet input is selected to cover THIS liquidation's lender payout
    // ======================================================================================

    /**
     * <b>The router asks for the exact ada it is about to pay the lender.</b>
     * <p>
     * This is the half of T-052's acceptance that says a principal-repaying liquidation is never
     * built against an input too small to fund it. The figure handed to the selector must be
     * {@code convertedLoanCollateralToPrincipalAmount} — the ada that leaves the bot's wallet — and
     * not a proxy.
     */
    @Test
    void theSelectorIsAskedForTheExactLenderPayout() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));
        List<BigInteger> asked = new ArrayList<>();

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(),
                payout -> {
                    asked.add(payout);
                    return Optional.of(WALLET_UTXO);
                },
                NOW, VALID_TO_MILLIS);

        assertEquals(1, asked.size(), "the selector must be consulted exactly once");
        // Re-derived from the BUILT body rather than from the router's own arithmetic: the lender's
        // paid-in-advance output is the ada the wallet has to fund, so the amount demanded of the
        // wallet and the amount paid out must be the same number. Comparing the router against itself
        // would be an assertion structurally incapable of failing.
        BigInteger paidToLender = tx.getBody().getOutputs().stream()
                .filter(o -> o.getValue().getMultiAssets() == null
                        || o.getValue().getMultiAssets().isEmpty())
                .map(o -> o.getValue().getCoin())
                .max(BigInteger::compareTo)
                .orElseThrow();
        assertEquals(paidToLender, asked.getFirst(),
                "the wallet was asked to cover an amount that is not what the lender is paid");
    }

    /**
     * ⚠ <b>A wallet that cannot fund the payout is a REFUSAL, not a crash and not a built
     * transaction.</b> Before T-052 the executor nominated one utxo per cycle and this candidate
     * would have been built against it regardless, failing at evaluation with an empty
     * {@code ScriptFailures} map — the unreadable shape measured on preview 2026-08-24.
     */
    @Test
    void aWalletThatCannotFundTheLenderPayoutIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        PayInAdvanceLiquidationRouter.WalletInputTooSmallException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.WalletInputTooSmallException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(),
                        payout -> Optional.empty(), NOW, VALID_TO_MILLIS));

        assertTrue(refusal.getMessage().contains("repays the lender"),
                "the refusal must say what the wallet was short of, not merely that it was short: "
                        + refusal.getMessage());
    }

    /**
     * Positive control: the selection is real, not decorative. Handing back a different wallet UTxO
     * must reach the built transaction's inputs — otherwise the test above proves only that a lambda
     * was invoked.
     */
    @Test
    void theSelectedUtxoIsTheOneActuallySpent() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), anyWallet(), NOW, VALID_TO_MILLIS);

        assertTrue(tx.getBody().getInputs().stream()
                        .anyMatch(i -> i.getTransactionId().equals(WALLET_UTXO.getTxHash())
                                && i.getIndex() == WALLET_UTXO.getOutputIndex()),
                "the utxo the selector returned is not among the transaction's inputs");
    }

    /**
     * A market that permits pay-in-advance up to {@code cap}. Since 2026-09-03 a test that exercises
     * that path must SAY which market it operates in and that the operator chose ANTICIPATE there —
     * exactly what an operator must now do, and no longer inferable from a cap alone.
     */
    private static AppConfig.LiquidationConfiguration.Market anticipateMarket(String unit, long cap) {
        var m = new AppConfig.LiquidationConfiguration.Market();
        m.setUnit(unit);
        m.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        m.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        m.setCap(java.math.BigInteger.valueOf(cap));
        return m;
    }
}
