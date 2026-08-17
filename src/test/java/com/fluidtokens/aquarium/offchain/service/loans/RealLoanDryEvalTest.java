package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first evaluation ever run against a <b>real, third-party Lending v4 loan</b>: preview loan
 * {@code 124e7c5d…}, opened by FluidTokens rather than by this repo, liquidated through
 * {@link LiquidateTransactionBuilder} exactly as production would and handed to the real PlutusV3
 * machine.
 *
 * <h2>What makes this different from {@link LiquidateDryEvalTest}</h2>
 * That file's fixtures are synthetic: this repo chooses the datums, so a shape it cannot model is a
 * shape it never builds, and the suite stays green by construction. Here every field of the loan and
 * of the lender bond is a byte the chain produced, and three of them are firsts:
 * <ol>
 *   <li><b>A non-zero perpetual {@code interestRate} — 459 — reaches the deployed validators for the
 *       first time.</b> Every previous perpetual evaluation in this repo used
 *       {@code interestRate = 0}, where {@code get_remaining_debt} degenerates to
 *       {@code ceil(principal)} and proves nothing whatsoever about the arithmetic. At 459 the debt
 *       is {@code 50_001_735} rather than {@code 50_000_000}, so
 *       {@code remainingDebt == inputAction.remainingDebt} ({@code loan_claim_action.ak:229} at
 *       {@code ff005fb}) is a real comparison of two independently computed numbers — the Aiken
 *       {@code Rational} pipeline against {@link LoanFinance}'s port of it. This closes T-018.</li>
 *   <li><b>A real oracle leg.</b> {@link LiquidateDryEvalTest} has none — its loans are
 *       ada/ada, so {@code retrieve_oracle_data} short-circuits before it looks at anything. This
 *       loan is tFLDT-collateralised, so the Charli3-backed feed, the {@code Withdraw} redeemer it
 *       travels in, and the FluidTokens oracle validator itself all execute. The oracle script is
 *       applied to eight unpublished parameters and cannot be derived from the blueprint, so the
 *       <em>deployed</em> code is used instead, fetched from the reference-script UTxO the registry
 *       publishes and pinned in {@code src/test/resources/loans-v4/preview-oracle-script.hex}; its
 *       hash is asserted below, which is what makes it the real validator rather than a stand-in.</li>
 *   <li><b>A debt that drifts inside the validity window.</b> 9 lovelace over 120 seconds
 *       ({@code 50_001_735} at {@code validFrom}, {@code 50_001_744} at {@code validTo}). Until
 *       T-021 this builder refused exactly this loan with
 *       {@code REMAINING_DEBT_NOT_INVARIANT}; the evaluation below is the evidence that the single
 *       {@code validFrom} figure is what the chain wants.</li>
 * </ol>
 *
 * <h2>Fixture provenance</h2>
 * Read off preview with Blockfrost on <b>2026-08-17</b>, and pinned as literals so the test runs
 * cold and deterministically thereafter — no network, no key, no wallet, no env var.
 * <ul>
 *   <li>Loan UTxO: {@code d2a851262f679d9465090de5d8638602d613fa7dbe4e76f14da0241d52bfbc4f#1} —
 *       3 ADA, 100_000_000 tFLDT, the loan NFT, inline datum {@link #LOAN_DATUM_HEX}.</li>
 *   <li>Lender bond UTxO: the same transaction, {@code #3} — 1_805_890 lovelace, the lender-bond
 *       NFT, inline datum {@link #BOND_DATUM_HEX}.</li>
 *   <li>Both were unspent when they were read.</li>
 *   <li>Oracle: the tFLDT entry of {@code https://testapi.fluidtokens.com/get-oracle-tokens}, whose
 *       price ({@code 338163/1000000}) and window are pinned as literals, together with its three
 *       on-chain UTxOs.</li>
 * </ul>
 * The two <em>config</em> reference inputs are the ones {@link LoanFixtures} already carries — the
 * real third-deployment preview {@code ConfigDatum}s — so the field indices, hashes and arithmetic
 * the validators read are the deployed ones. Only the bot's own wallet UTxO and the six
 * loans-v4 reference-script coordinates are synthetic; see {@link #REFERENCE_SCRIPTS}.
 *
 * <h2>The oracle-resolution question this test settles</h2>
 * The loan datum's {@code collateral.oracleTokenAsset} names {@code 9a2ec5c9…/000de1406f766f3633},
 * which is the registry entry's {@code fluidOracle} asset; the entry's {@code supportedOracle.c3}
 * names a <em>different</em> asset, {@code decfbd6b…/4f7261636c6546656564}. They are not
 * alternatives and there is nothing to choose between:
 * {@code retrieve_oracle_data} ({@code lib/fluidtokens/oracle.ak}) uses the datum-named asset only
 * as {@code quantity_of(oracleInputValue, oracleTokenPolicyId, oracleTokenAssetName) > 0} — the NFT
 * that must sit in the oracle reference input — while {@code validators/oracle.ak:90-94} requires the
 * c3 asset in the <em>provider</em> reference input named by
 * {@code PriceDataCharlie.provider_ref_input_index}. Two different reference inputs, two different
 * checks, both required. This transaction carries both, and the evaluation is what proves it.
 *
 * <h2>Reading a failure is not the mirror image of reading a success</h2>
 * As in {@link LiquidateDryEvalTest}: the evaluator reports only the <b>first</b> failing redeemer
 * (see {@link EvalFixtures} — "Harness limitations"), so a clean run says every redeemer passed while
 * a failed run says only that the named one refused.
 */
@Slf4j
class RealLoanDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the real loan, transaction d2a85126…bfbc4f on preview, read 2026-08-17 -----------------

    private static final String LOAN_TX =
            "d2a851262f679d9465090de5d8638602d613fa7dbe4e76f14da0241d52bfbc4f";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;

    /** Asset name of both the loan NFT and the lender-bond NFT — the join key between the two. */
    private static final String LOAN_ID = "124e7c5db2b2a8905d889ffd";

    /**
     * Inline datum of {@code d2a85126…bfbc4f#1}, verbatim. Decoded by the production
     * {@link LoanDatumConverter} in {@link #theRealLoanDatumDecodesToWhatTheChainCarries()} rather
     * than trusted, and never re-encoded from a model — the bytes are the fixture.
     */
    private static final String LOAN_DATUM_HEX =
            "d8799f001a02faf0801b000001a00ec93b00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00746eab912831d705649ad01a"
                    + "3f349eaefa3fb5e57e7b170c07063e83d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b"
                    + "9f00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb26961"
                    + "1a9eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    /**
     * Inline datum of {@code d2a85126…bfbc4f#3}, verbatim. {@code lm_liquidate_action.ak:149}
     * compares the bond output against its input with {@code builtin.equals_data} on the whole
     * output, so these exact bytes have to be echoed; the builder's own
     * {@code BOND_DATUM_NOT_BYTE_IDENTICAL} guard refuses if cardano-client-lib would re-emit them
     * differently, which makes this fixture a round-trip test of the real chain bytes as well.
     */
    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd879"
                    + "8000581d00746eab912831d705649ad01a3f349eaefa3fb5e57e7b170c07063e83d8799f4040f"
                    + "fff";

    /**
     * The loan's own address: a <b>base</b> address, payment credential the derived
     * {@code loanSpendScriptHash}, stake part a borrower key hash. Every existing fixture in this
     * package parks its loans at an <em>enterprise</em> address instead, and that difference is inert
     * on chain: {@code get_inputs_from_smart_credential} ({@code lib/smart-tokens/utils.ak}) filters
     * on {@code payment_credential} alone in its native-token branch.
     */
    private static final String LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";

    /** The bond's address: {@code lenderManagerSpendScriptHash} + the datum's own stake credential. */
    private static final String BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";

    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_805_890L;

    // ---- the oracle, from testapi.fluidtokens.com/get-oracle-tokens, read 2026-08-17 ------------

    /** {@code fluidOracle} of the tFLDT entry — and {@code collateral.oracleTokenAsset} in the datum. */
    private static final AssetType ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");

    /** {@code supportedOracle.c3} of the same entry — a different asset, in a different ref input. */
    private static final AssetType C3_FEED_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");

    private static final String ORACLE_SCRIPT_HASH =
            "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    /** {@code fluidOracle.rewardAddress}, as published. Re-derived and compared in the test. */
    private static final String ORACLE_REWARD_ADDRESS =
            "stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2";

    /** {@code fluidOracle.referenceInput} — holds {@link #ORACLE_NFT}. */
    private static final TransactionInput ORACLE_REF_INPUT = new TransactionInput(
            "cc4721afdf4721f8f179b3afddb8e096805c0fad16afe54687d7368d12bd769c", 0);
    /** {@code fluidOracle.referenceScript} — holds the applied oracle validator. */
    private static final TransactionInput ORACLE_REF_SCRIPT = new TransactionInput(
            "ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f", 0);
    /** {@code supportedOracle.c3.referenceInput} — the Charli3 provider UTxO. */
    private static final TransactionInput C3_PROVIDER = new TransactionInput(
            "a17501465ed79dbc6cb25e2e99edbc421b1baa9d100b6780da89770702b235a5", 0);

    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    /**
     * The Charli3 provider's inline datum, verbatim:
     * {@code OracleDatum { GenericData { [Pair(0, 338163), Pair(1, 0), Pair(2, 1805990739000)] } } }.
     * {@code utils.get_oracle_info} reads it as {@code (price, timestamp, expiry)}, and
     * {@code validators/oracle.ak:89-98} then demands
     * {@code common.valid_from >= 0}, {@code common.valid_to <= 1805990739000}, and
     * {@code price_in_lovelaces * 10^decimals / price_denominator == 338163}.
     */
    private static final String C3_PROVIDER_DATUM_HEX =
            "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff";

    private static final BigInteger PRICE = BigInteger.valueOf(338163);
    private static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(1_000_000);
    private static final long FEED_VALID_FROM = 1_786_978_164_445L;
    private static final long FEED_VALID_TO = 1_786_978_764_445L;

    // ---- the instant ---------------------------------------------------------------------------

    /**
     * A <b>pinned</b> instant, never wall clock, chosen so every window constraint holds with room to
     * spare. 2026-08-17T14:50:00Z.
     * <ul>
     *   <li>after {@code lendDate} (1_786_954_464_000, 6.593 h earlier), so the debt is defined;</li>
     *   <li>inside the pinned feed window, {@code FEED_VALID_FROM <= NOW} and
     *       {@code VALID_TO <= FEED_VALID_TO} with 444_445 ms of feed left afterwards, against the
     *       300_000 ms margin the request asks for;</li>
     *   <li>exactly on a preview slot boundary, as is {@link #VALID_TO} — so the millis→slot→millis
     *       round trip the ledger does is lossless and the script context sees these two numbers
     *       unchanged;</li>
     *   <li>liquidatable on price alone: 50_001_735 lovelace of debt against 33_816_300 lovelace of
     *       collateral is an ltv of 1.4786 against the datum's 100/125 = 0.8 threshold. Lateness plays
     *       no part — the loan is perpetual with {@code installmentPeriod == 0}, which
     *       {@code is_repayment_late} makes permanently not-late.</li>
     * </ul>
     */
    private static final long NOW = 1_786_978_200_000L;
    private static final long VALID_FROM = NOW;
    private static final long VALID_TO = NOW + 120_000L;
    private static final long MARGIN = 300_000L;

    /** The debt at {@code validFrom}, and at {@code validTo} — the 9-lovelace drift, pinned. */
    private static final BigInteger DEBT_AT_VALID_FROM = BigInteger.valueOf(50_001_735L);
    private static final BigInteger DEBT_AT_VALID_TO = BigInteger.valueOf(50_001_744L);

    // ---- the synthetic remainder ---------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 50_000_000L);

    /** The real ledger limit. Not {@link EvalFixtures#protocolParams()}'s raised ceiling. */
    private static final int MAX_TX_SIZE = 16_384;

    /**
     * Six <b>synthetic</b> reference-script coordinates, and deliberately so.
     * <p>
     * FluidTokens redeployed preview on 2026-08-17 and has not published coordinates for the new
     * scripts — {@code loans.liquidation.reference-scripts.*} in the {@code preview} profile of
     * {@code application.yaml} are all empty, and the six values commented out above them belong to
     * the previous deployment. Pinning those here would pin coordinates that resolve to the wrong
     * scripts.
     * <p>
     * Nothing measured below depends on which hash they are: a reference input is 33 bytes on the
     * wire whatever it points at, and the UTxOs built for them in {@link #referenceScriptUtxos()}
     * carry the <em>freshly derived</em> script hashes, which is what
     * {@link EvalFixtures#scriptSupplier} resolves on. The size figure is therefore exact, and the
     * evaluation is against the real applied code.
     */
    private static final LiquidateTransactionBuilder.ReferenceScripts REFERENCE_SCRIPTS =
            new LiquidateTransactionBuilder.ReferenceScripts(
                    new TransactionInput("a1".repeat(32), 0),
                    new TransactionInput("a2".repeat(32), 0),
                    new TransactionInput("a3".repeat(32), 0),
                    new TransactionInput("a4".repeat(32), 0),
                    new TransactionInput("a5".repeat(32), 0),
                    new TransactionInput("a6".repeat(32), 0),
                    null);

    // ======================================================================================
    // the fixtures themselves
    // ======================================================================================

    /**
     * The real loan datum, decoded by the production converter. Every number the rest of this file
     * relies on is asserted here, so a mis-transcribed literal fails loudly instead of quietly
     * changing what is being evaluated.
     */
    @Test
    void theRealLoanDatumDecodesToWhatTheChainCarries() {
        LoanDatum datum = loanDatum();

        assertEquals(BigInteger.valueOf(50_000_000), datum.principalAmount());
        assertEquals(BigInteger.valueOf(459), datum.interestRate(), "the first non-zero rate evaluated");
        assertEquals(BigInteger.valueOf(1_786_954_464_000L), datum.lendDate());
        assertEquals(BigInteger.ZERO, datum.repaidInstallments());
        assertEquals(BigInteger.ZERO, datum.installmentPeriod(),
                "perpetual with no installment period is never late; liquidatability is ltv-only");
        assertFalse(datum.repaymentReceipts(), "no receipt NFT to model");
        assertEquals(new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                datum.repaymentMode());
        assertEquals(new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                BigInteger.valueOf(100), false), datum.liquidationMode());
        assertTrue(datum.principalAsset().isAda(), "ada principal, so the principal leg needs no oracle");
        assertTrue(datum.principalOracleAsset().isAda());
        assertEquals(COLLATERAL, datum.collateral().assetType());
        assertEquals(ORACLE_NFT, datum.collateral().oracleTokenAsset(),
                "the oracle the datum names is the registry's fluidOracle, not its c3 feed");

        // The debt at both ends of the window — the drift that used to make this loan unbuildable.
        assertEquals(DEBT_AT_VALID_FROM, LoanFinance.remainingDebt(datum, VALID_FROM));
        assertEquals(DEBT_AT_VALID_TO, LoanFinance.remainingDebt(datum, VALID_TO));
        assertEquals(9L, DEBT_AT_VALID_TO.subtract(DEBT_AT_VALID_FROM).longValueExact(),
                "9 lovelace of drift over 120s is what REMAINING_DEBT_NOT_INVARIANT used to refuse");
        assertFalse(LoanFinance.isRepaymentLate(datum, VALID_TO));
    }

    /** The real bond datum, decoded, and the three fields the plain {@code Liquidate} path needs. */
    @Test
    void theRealBondDatumDecodesToWhatTheChainCarries() {
        LenderManagerDatum datum = bondDatum();

        assertFalse(datum.shouldLiquidationConvertToPrincipal(),
                "lm_liquidate_action.ak:147 requires this to be false");
        assertEquals(BigInteger.ZERO, datum.liquidationFeePerMille(),
                "no fee, so the lender must receive the collateral in full");
        assertTrue(datum.principalAsset().isAda());
        assertEquals("00746eab912831d705649ad01a3f349eaefa3fb5e57e7b170c07063e83", datum.poolId(),
                "a pool-originated loan — inert for this action, which never reads poolId");
    }

    /**
     * The oracle registry's published reward address is the one this repo derives from the oracle's
     * script hash on preview. The builder makes the same comparison itself
     * ({@code ORACLE_REWARD_ADDRESS_MISMATCH}); asserting it here as well means the fixture is wrong
     * rather than the builder if it ever diverges.
     */
    @Test
    void theOracleRewardAddressDerivesFromTheDeployedOracleScriptHash() {
        assertEquals(ORACLE_REWARD_ADDRESS, LoanFixtures.rewardAddress(ORACLE_SCRIPT_HASH));
    }

    /**
     * The oracle code this rig evaluates is the deployed one. The hex fixture is the CBOR of the
     * reference script sitting at {@link #ORACLE_REF_SCRIPT}, and its hash reproduces the script hash
     * the oracle's own address and reward address are built from — which is the only thing that rules
     * out a stand-in.
     */
    @Test
    void theBundledOracleScriptIsTheDeployedOne() throws Exception {
        assertEquals(ORACLE_SCRIPT_HASH, HexUtil.encodeHexString(oracleScript().getScriptHash()));
        assertEquals(4141, HexUtil.decodeHexString(
                LoanFixtures.fixture("preview-oracle-script.hex")).length,
                "the serialised size Blockfrost reports for 402c984d…");
    }

    // ======================================================================================
    // the evaluation
    // ======================================================================================

    /**
     * <b>The deliverable.</b> The real loan, liquidated through the production builder, accepted by
     * every deployed validator the transaction invokes.
     *
     * <h3>Measured, not estimated</h3>
     * Every figure below is read off this transaction and logged by the test; they are pinned as
     * upper bounds rather than as equalities so a cost-model or encoder change reports honestly
     * instead of failing on an unrelated byte.
     * <pre>
     *   serialized size     1_815 bytes    against maxTxSize        16_384   (11.1%)
     *   total ex-units mem  2_912_761      against maxTxExMem   14_000_000   (20.8%)
     *   total ex-units cpu  993_496_492    against maxTxExSteps 10_000_000_000 (9.9%)
     *
     *   per redeemer (the withdrawal map turns out to be sorted by reward address, so index 0 is
     *   the oracle at 402c984d… — read off the body by {@link #redeemerLabel}, not assumed):
     *     Spend#0                       mem     28_085   steps    9_651_596
     *     Spend#1                       mem     33_809   steps   11_625_734
     *     Mint#0                        mem    270_601   steps   98_713_456
     *     Reward#0  oracle              mem    216_003   steps   65_767_740
     *     Reward#1  loan                mem    141_837   steps   43_584_459
     *     Reward#2  lenderManager       mem    126_375   steps   39_852_955
     *     Reward#3  loan_claim_action   mem  1_331_779   steps  443_562_483
     *     Reward#4  lm_liquidate_action mem    764_272   steps  280_738_069
     * </pre>
     * Eight redeemers have to return ex-units: two spends (the loan, the bond), one mint (the loan
     * NFT burn) and five withdrawals — {@code loan}, {@code loan_claim_action},
     * {@code lenderManager}, {@code lm_liquidate_action}, and the <b>oracle</b>, which no other
     * dry-eval in this repo exercises. The size figure is for the reference-script shape; the six
     * validators do not travel in the witness set (see {@link #REFERENCE_SCRIPTS}).
     *
     * <h3>The arithmetic the chain agrees with</h3>
     * <pre>
     *   remainingDebt at validFrom             50_001_735 lovelace   (interestRate 459, m 28)
     *   collateral 100_000_000 tFLDT at 338163/1000000  ->  33_816_300 lovelace
     *   ltv 1.4786 against 100/125 = 0.8       -> can_liquidate
     *   equity: 33_816_300 - 50_001_735 - 10%  -> negative, clamps to 0
     *   liquidationFeePerMille 0               -> fee 0
     *   payout to the lender                   100_000_000 tFLDT, in full
     * </pre>
     */
    @Test
    void theRealLoanLiquidationEvaluatesAgainstTheDeployedValidators() {
        Fixture fixture = fixture();

        assertEquals(DEBT_AT_VALID_FROM, fixture.assessment().remainingDebt());
        assertEquals(BigInteger.ZERO, fixture.assessment().equity(),
                "deeply underwater, so equity clamps to zero and V8 never applies");
        assertEquals(BigInteger.ZERO, fixture.assessment().liquidationFee());
        assertTrue(fixture.assessment().buildable(), fixture.assessment().detail());

        Transaction tx = build(fixture);
        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe(fixture), REGISTRY, List.of(oracleScript()));

        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend), "the loan and the bond");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one burn redeemer");
        assertEquals(5, count(results, RedeemerTag.Reward),
                "four loans-v4 withdrawals plus the oracle's");

        // ---- structural facts, off the deserialised transaction, never off the evaluator ---------
        Transaction body = deserialise(tx);

        // The oracle withdrawal really is there, at the registry's reward address.
        assertTrue(body.getBody().getWithdrawals().stream()
                        .anyMatch(w -> ORACLE_REWARD_ADDRESS.equals(w.getRewardAddress())),
                "the oracle must be invoked by withdrawal, or its validator never ran");

        // lm_liquidate_action.ak:170-179 — the asset-manager WITHDRAW script must not appear in the
        // withdrawals at all, which is what stops a liquidation from spending asset-manager UTxOs in
        // the same transaction. Asserted on the finished body because it is a property of the whole
        // transaction rather than of anything this builder decides.
        String assetManagerReward =
                LoanFixtures.rewardAddress(REGISTRY.getAssetManagerWithdrawScriptHash());
        assertTrue(body.getBody().getWithdrawals().stream()
                        .noneMatch(w -> assetManagerReward.equals(w.getRewardAddress())),
                "no asset-manager withdrawal may be present");

        // All three oracle reference inputs travel, at three distinct indexes.
        List<TransactionInput> refInputs = sortedRefInputs(body);
        assertTrue(refInputs.contains(ORACLE_REF_INPUT), "the fluid oracle utxo, holding the datum's NFT");
        assertTrue(refInputs.contains(ORACLE_REF_SCRIPT), "the oracle's own reference script");
        assertTrue(refInputs.contains(C3_PROVIDER), "the Charli3 provider utxo");

        // The claim redeemer's own numbers, and the index it points the collateral oracle at.
        List<PlutusData> claim = claimFields(body);
        assertEquals(DEBT_AT_VALID_FROM, ((BigIntPlutusData) claim.get(7)).getValue(),
                "remainingDebt in the redeemer is the validFrom figure");
        assertEquals(BigInteger.ZERO, ((BigIntPlutusData) claim.get(5)).getValue(), "equity");
        assertEquals(refInputs.indexOf(ORACLE_REF_INPUT),
                ((BigIntPlutusData) claim.get(2)).getValue().intValueExact(),
                "collateralOracleRefInputIndex must point at the datum-named oracle utxo");

        // The Charli3 provider index inside the oracle redeemer.
        assertEquals(refInputs.indexOf(C3_PROVIDER), charlieProviderIndex(body),
                "provider_ref_input_index must point at the c3 provider utxo");

        // The payout: the whole collateral, in an output of exactly two flattened entries
        // (lm_liquidate_action.ak:219-224 dosProtection for a token collateral), carrying the
        // claimed-collateral datum built from an ASCII literal rather than from the encoder's constant.
        TransactionOutput payout = onlyAssetManagerOutput(body);
        assertEquals(BigInteger.valueOf(COLLATERAL_AMOUNT), quantityOf(payout, COLLATERAL));
        assertEquals(2, flattenedCount(payout), "collateral plus the min-ada rider, and nothing else");
        assertEquals(expectedPayoutDatumHex(), payout.getInlineDatum().serializeToHex());

        // The bond echo: byte-identical to its input, address, value and datum.
        TransactionOutput echo = onlyBondOutput(body);
        assertEquals(BOND_ADDRESS, echo.getAddress());
        assertEquals(BOND_DATUM_HEX, echo.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(BOND_LOVELACE), echo.getValue().getCoin());
        assertEquals(BigInteger.ONE, quantityOf(echo,
                new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID)));

        // The loan NFT is burned, exactly once.
        assertEquals(BigInteger.ONE.negate(), mintedQuantity(body,
                new AssetType(REGISTRY.getLoanPolicyId(), LOAN_ID)));

        // assetOutputIndexes must be DISTINCT (list.unique(..) == ..). No ordering is required and
        // none is asserted — lm_liquidate_action.ak:168 imposes uniqueness only.
        List<BigInteger> assetOutputIndexes = assetOutputIndexes(body);
        assertEquals(1, assetOutputIndexes.size());
        assertEquals(assetOutputIndexes.size(), new HashSet<>(assetOutputIndexes).size());

        // ---- the two measurements -----------------------------------------------------------
        int size = serializedSize(tx);
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (EvaluationResult result : results) {
            assertTrue(result.getExUnits().getMem().signum() > 0, "a script that ran costs memory");
            assertTrue(result.getExUnits().getSteps().signum() > 0, "a script that ran costs steps");
            log.info("  {}#{} [{}]: mem {} steps {}", result.getRedeemerTag(), result.getIndex(),
                    redeemerLabel(body, result), result.getExUnits().getMem(),
                    result.getExUnits().getSteps());
            mem = mem.add(result.getExUnits().getMem());
            steps = steps.add(result.getExUnits().getSteps());
        }
        log.info("real loan {} liquidation: {} bytes (max {}), mem {} (max {}), steps {} (max {})",
                LOAN_ID, size, MAX_TX_SIZE, mem, 14_000_000L, steps, 10_000_000_000L);

        assertTrue(size < MAX_TX_SIZE, "serialized size " + size + " against " + MAX_TX_SIZE);
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);
    }

    // ======================================================================================
    // the adversarial mutations — a rig that evaluates nothing reads as green
    // ======================================================================================

    /**
     * The debt the chain recomputes is the one at {@code validFrom} and nothing else: the drifted
     * {@code validTo} figure — 9 lovelace higher, and a number the loan genuinely does produce, just
     * at the wrong instant — is <b>refused</b>.
     * <p>
     * This is the sharpest available proof of T-021's narrowing. The builder's V4 guard refuses such
     * an assessment off chain, so the mutation is applied to the built transaction's redeemer, which
     * is the only way to put it in front of the machine. A rig that never reached
     * {@code loan_claim_action}'s arithmetic would accept it, and so would a validator that read the
     * debt at the upper bound — this rules out both.
     */
    @Test
    void theValidToDebtIsRejectedByLoanClaimAction() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction clean = build(fixture);
        EvalFixtures.evaluate(clean, universe, REGISTRY, extra);

        Transaction mutated = build(fixture);
        replaceClaimRedeemer(mutated, DEBT_AT_VALID_TO);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(),
                "loan_claim_action must reject the validTo debt: the validator reads validFrom");
        assertTrue(outcome.detail().contains(redeemerError(mutated,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "expected loan_claim_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * The oracle leg is load-bearing rather than decorative: swapping the Charli3 provider index for
     * the fluid oracle's own reference-input index — a valid index into a real reference input, just
     * the wrong one — is refused by the <b>oracle</b> validator.
     * <p>
     * {@code validators/oracle.ak:59-62} expects an inline {@code OracleDatum} at that index and the
     * fluid oracle utxo carries no datum at all, so the refusal is attributable to the one field that
     * moved. Without this, "the oracle passed" would be indistinguishable from an oracle whose
     * redeemer was never really checked against its provider.
     */
    @Test
    void aWrongCharlieProviderIndexIsRejectedByTheOracle() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction mutated = build(fixture);
        List<TransactionInput> refInputs = sortedRefInputs(deserialise(mutated));
        int wrongIndex = refInputs.indexOf(ORACLE_REF_INPUT);
        assertTrue(wrongIndex >= 0 && wrongIndex != refInputs.indexOf(C3_PROVIDER),
                "the mutation must really change the index");
        replaceCharlieProviderIndex(mutated, wrongIndex);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(), "the oracle must reject a provider index that is not its feed");
        assertTrue(outcome.detail().contains(redeemerError(mutated, ORACLE_REWARD_ADDRESS)),
                "expected the oracle to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * {@code NOT_LIQUIDATABLE_OVER_WINDOW}'s conservatism, on the real loan: shifting the window past
     * the pinned feed's {@code validTo} is refused off chain by V3 before anything is built.
     * <p>
     * This is the off-chain mirror of {@code retrieve_oracle_data}'s
     * {@code commonFeedData.valid_to >= transactionValidTo}, and it is why T-021 left the
     * liquidatability leg checked over the whole interval rather than narrowing it too: unlike the
     * debt, that leg consumes a feed whose own window the chain compares against <em>both</em> bounds.
     */
    @Test
    void aWindowThatOutlivesTheFeedIsRefusedBeforeAnythingIsBuilt() {
        Fixture fixture = fixture();
        LiquidateTransactionBuilder.RefusedException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(fixture).build(request(fixture, FEED_VALID_TO - 60_000L,
                                FEED_VALID_TO + 60_000L)));
        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_FEED_NOT_USABLE_OVER_WINDOW,
                refused.getReason());
    }

    // ======================================================================================
    // fixture plumbing
    // ======================================================================================

    private record Fixture(Loan loan, Utxo loanUtxo, LenderBond bond, Utxo bondUtxo,
                           OracleEntry oracle, LiquidationAssessment assessment) {

        LiquidateTransactionBuilder.LoanLiquidation toLiquidation() {
            return new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo, bondUtxo);
        }
    }

    private static LoanDatum loanDatum() {
        return new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
    }

    private static LenderManagerDatum bondDatum() {
        return new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
    }

    /**
     * The loan and bond exactly as {@code LiquidationUtxoResolver} and
     * {@code LiquidationCandidateScanner} would have produced them from those two UTxOs, with the
     * assessment taken at {@link #VALID_FROM} through {@link LoanFixtures#assess} — the same
     * {@link LoanFinance} calls and the same fee formula the scanner uses.
     */
    private static Fixture fixture() {
        LoanDatum datum = loanDatum();
        Utxo loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), LOAN_DATUM_HEX);
        Loan loan = new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);

        Utxo bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)),
                BOND_DATUM_HEX);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID,
                BOND_DATUM_HEX, bondDatum());

        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);

        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, OraclePriceFeed.unit(),
                oracle.feed(), VALID_FROM);
        return new Fixture(loan, loanUtxo, bond, bondUtxo, oracle, assessment);
    }

    /**
     * Everything the evaluator may resolve an input or reference input from. The three oracle UTxOs
     * carry exactly what preview holds: the fluid oracle its NFT and no datum (nothing reads one),
     * the reference script only its script hash, the Charli3 provider its NFT and its inline datum.
     */
    private static List<Utxo> universe(Fixture fixture) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                fixture.loanUtxo(), fixture.bondUtxo()));

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

        universe.addAll(referenceScriptUtxos());
        return universe;
    }

    /** A UTxO per {@link #REFERENCE_SCRIPTS} coordinate, each carrying its real derived script hash. */
    private static List<Utxo> referenceScriptUtxos() {
        List<TransactionInput> coordinates = List.of(REFERENCE_SCRIPTS.loan(),
                REFERENCE_SCRIPTS.loanSpend(), REFERENCE_SCRIPTS.lenderManager(),
                REFERENCE_SCRIPTS.lenderManagerSpend(), REFERENCE_SCRIPTS.loanClaimAction(),
                REFERENCE_SCRIPTS.lmLiquidateAction());
        List<PlutusScript> scripts = List.of(REGISTRY.getLoanScript(), REGISTRY.getLoanSpendScript(),
                REGISTRY.getLenderManagerScript(), REGISTRY.getLenderManagerSpendScript(),
                REGISTRY.getLoanClaimActionScript(), REGISTRY.getLmLiquidateActionScript());
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i++) {
            String scriptHash = scriptHash(scripts.get(i));
            utxos.add(Utxo.builder()
                    .txHash(coordinates.get(i).getTransactionId())
                    .outputIndex(coordinates.get(i).getIndex())
                    .address(LoanFixtures.entAddress(scriptHash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(scriptHash)
                    .build());
        }
        return utxos;
    }

    /**
     * The deployed oracle validator, from the committed CBOR of its published reference script.
     * <p>
     * The fixture holds exactly what Blockfrost's {@code /scripts/{hash}/cbor} serves, which is the
     * byte string the ledger hashes as {@code blake2b_224(0x03 || these bytes)} — the same role
     * {@code compiledCode} plays in the blueprint. It is therefore fed through the same
     * {@code PlutusBlueprintUtil.getPlutusScriptFromCompiledCode} the production
     * {@link LoansContractRegistry} uses, rather than into {@link PlutusV3Script}'s {@code cborHex}
     * directly: cardano-client-lib strips one CBOR layer off {@code cborHex} before hashing, so
     * passing these bytes there hashes one layer too deep and yields {@code 33b91a2f…}. Verified
     * against a script this repo derives independently — Blockfrost's {@code /cbor} for
     * {@code loan_claim_action} (9ae63b26…) hashes correctly under exactly this rule.
     */
    private static PlutusScript oracleScript() {
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    LoanFixtures.fixture("preview-oracle-script.hex"), PlutusVersion.v3);
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script", e);
        }
    }

    private static LiquidateTransactionBuilder builder(Fixture fixture) {
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe(fixture)), EvalFixtures.protocolParams());
    }

    private static LiquidateTransactionBuilder.Request request(Fixture fixture) {
        return request(fixture, VALID_FROM, VALID_TO);
    }

    private static LiquidateTransactionBuilder.Request request(Fixture fixture, long validFrom,
                                                               long validTo) {
        return new LiquidateTransactionBuilder.Request(
                List.of(fixture.toLiquidation()), CONFIG_UTXO, LM_CONFIG_UTXO,
                Map.of(ORACLE_NFT.toUnit(), fixture.oracle()), WALLET_UTXO, LoanFixtures.botAddress(),
                validFrom, validTo, MARGIN, REFERENCE_SCRIPTS);
    }

    private static Transaction build(Fixture fixture) {
        Transaction tx = builder(fixture).build(request(fixture));
        assertNotNull(tx);
        return tx;
    }

    // ---- reading the built transaction back ------------------------------------------------

    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("the built transaction does not round-trip through CBOR", e);
        }
    }

    private static int serializedSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialize the built transaction", e);
        }
    }

    private static String scriptHash(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash an applied loans-v4 script", e);
        }
    }

    private static List<TransactionInput> sortedRefInputs(Transaction tx) {
        return tx.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
    }

    private static void assertRedeemerCoverage(Transaction tx, List<EvaluationResult> results) {
        List<String> expected = tx.getWitnessSet().getRedeemers().stream()
                .map(redeemer -> redeemer.getTag() + "#" + redeemer.getIndex())
                .sorted()
                .toList();
        List<String> actual = results.stream()
                .map(result -> result.getRedeemerTag() + "#" + result.getIndex())
                .sorted()
                .toList();
        assertEquals(expected, actual, "every redeemer must have been evaluated, and nothing else");
    }

    private static long count(List<EvaluationResult> results, RedeemerTag tag) {
        return results.stream().filter(result -> result.getRedeemerTag() == tag).count();
    }

    /**
     * Which validator an {@link EvaluationResult} belongs to, for the log. Read off the body rather
     * than assumed: whether the withdrawal map keeps insertion order or canonical reward-address order
     * decides the index-to-validator mapping, and guessing it would misattribute every ex-unit figure
     * in the javadoc above.
     */
    private static String redeemerLabel(Transaction body, EvaluationResult result) {
        if (result.getRedeemerTag() != RedeemerTag.Reward) {
            return result.getRedeemerTag().toString();
        }
        String rewardAddress = body.getBody().getWithdrawals().get(result.getIndex())
                .getRewardAddress();
        return Map.of(
                LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()), "loan",
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()), "loan_claim_action",
                LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()), "lenderManager",
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()), "lm_liquidate_action",
                ORACLE_REWARD_ADDRESS, "oracle")
                .getOrDefault(rewardAddress, rewardAddress);
    }

    /** The single output at the asset-manager spend credential — the lender's payout. */
    private static TransactionOutput onlyAssetManagerOutput(Transaction tx) {
        return onlyOutputAt(tx, REGISTRY.getAssetManagerSpendScriptHash(), "asset-manager");
    }

    private static TransactionOutput onlyBondOutput(Transaction tx) {
        return onlyOutputAt(tx, REGISTRY.getLenderManagerSpendScriptHash(), "lender-manager");
    }

    private static TransactionOutput onlyOutputAt(Transaction tx, String paymentScriptHash, String what) {
        List<TransactionOutput> matches = tx.getBody().getOutputs().stream()
                .filter(output -> paymentCredentialOf(output.getAddress()).equals(paymentScriptHash))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one " + what + " output, got " + matches.size());
        return matches.getFirst();
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash()
                .map(HexUtil::encodeHexString)
                .orElse("");
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** Flattened entries in an output's value: one per (policy, name) pair, plus lovelace. */
    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size())
                .sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }

    private static BigInteger mintedQuantity(Transaction tx, AssetType asset) {
        return tx.getBody().getMint().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * The datum {@code lm_liquidate_action}'s {@code validate_repayment_output} and
     * {@code loan_claim_action} will both compare against, built from the ASCII action literal rather
     * than from {@link LiquidationTxEncoder}'s constant — an expectation derived from the constant
     * under test moves with it and pins nothing.
     */
    private static String expectedPayoutDatumHex() {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                        LOAN_TX, LOAN_OUTPUT_INDEX,
                        HexUtil.encodeHexString("claimed_collateral".getBytes(StandardCharsets.US_ASCII)),
                        new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID)))
                .serializeToHex();
    }

    // ---- the redeemers ---------------------------------------------------------------------

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

    /** The prefix the Aiken evaluator prints for the withdrawal made at {@code rewardAddress}. */
    private static String redeemerError(Transaction tx, String rewardAddress) {
        return "RedeemerError { tag: \"Withdraw\", index: " + withdrawalIndexOf(tx, rewardAddress);
    }

    /** The eight fields of the single {@code ClaimData} in the loan-claim withdrawal's redeemer. */
    private static List<PlutusData> claimFields(Transaction tx) {
        ConstrPlutusData claimAction = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash())).getData();
        ListPlutusData actions = (ListPlutusData) claimAction.getData().getPlutusDataList().get(1);
        assertEquals(1, actions.getPlutusDataList().size(), "one loan, one action");
        ConstrPlutusData claim = (ConstrPlutusData) actions.getPlutusDataList().getFirst();
        List<PlutusData> fields = claim.getData().getPlutusDataList();
        assertEquals(8, fields.size(), "ClaimData has eight fields");
        return fields;
    }

    /** {@code assetOutputIndexes} — field 3 of the {@code lm_liquidate_action} redeemer. */
    private static List<BigInteger> assetOutputIndexes(Transaction tx) {
        ConstrPlutusData constr = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash())).getData();
        ListPlutusData indexes = (ListPlutusData) constr.getData().getPlutusDataList().get(3);
        return indexes.getPlutusDataList().stream()
                .map(item -> ((BigIntPlutusData) item).getValue())
                .toList();
    }

    /**
     * {@code PriceDataCharlie.provider_ref_input_index} — field 0 of the feed, which is field 0 of the
     * {@code OracleRedeemer}.
     */
    private static int charlieProviderIndex(Transaction tx) {
        ConstrPlutusData oracleRedeemer =
                (ConstrPlutusData) rewardRedeemer(tx, ORACLE_REWARD_ADDRESS).getData();
        ConstrPlutusData feed =
                (ConstrPlutusData) oracleRedeemer.getData().getPlutusDataList().getFirst();
        return ((BigIntPlutusData) feed.getData().getPlutusDataList().getFirst())
                .getValue().intValueExact();
    }

    // ---- the mutations ---------------------------------------------------------------------

    /**
     * Replaces {@code remainingDebt} — and only that — in the loan-claim redeemer. The other seven
     * {@code ClaimData} fields are lifted verbatim off the built transaction, so a resulting refusal is
     * attributable to the one field that moved.
     */
    private static void replaceClaimRedeemer(Transaction tx, BigInteger remainingDebt) {
        List<PlutusData> fields = new ArrayList<>(claimFields(tx));
        fields.set(7, BigIntPlutusData.of(remainingDebt));

        ConstrPlutusData original = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash())).getData();
        PlutusData configRefIndex = original.getData().getPlutusDataList().getFirst();
        rewardRedeemer(tx, LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))
                .setData(constr(0, configRefIndex,
                        ListPlutusData.of(constr(0, fields.toArray(PlutusData[]::new)))));
    }

    /** Replaces {@code provider_ref_input_index} — and only that — in the oracle redeemer. */
    private static void replaceCharlieProviderIndex(Transaction tx, int providerIndex) {
        Redeemer redeemer = rewardRedeemer(tx, ORACLE_REWARD_ADDRESS);
        ConstrPlutusData oracleRedeemer = (ConstrPlutusData) redeemer.getData();
        List<PlutusData> outer = new ArrayList<>(oracleRedeemer.getData().getPlutusDataList());
        ConstrPlutusData feed = (ConstrPlutusData) outer.getFirst();
        List<PlutusData> feedFields = new ArrayList<>(feed.getData().getPlutusDataList());
        feedFields.set(0, BigIntPlutusData.of(providerIndex));
        outer.set(0, ConstrPlutusData.builder()
                .alternative(feed.getAlternative())
                .data(ListPlutusData.of(feedFields.toArray(PlutusData[]::new)))
                .build());
        redeemer.setData(ConstrPlutusData.builder()
                .alternative(oracleRedeemer.getAlternative())
                .data(ListPlutusData.of(outer.toArray(PlutusData[]::new)))
                .build());
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }
}
