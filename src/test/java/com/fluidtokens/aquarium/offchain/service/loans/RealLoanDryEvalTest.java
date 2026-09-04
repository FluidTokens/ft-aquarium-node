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
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
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
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Validator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * Asset name of both the loan NFT and the lender-bond NFT — the join key between the two.
     * <p>
     * All 28 bytes. This constant used to be the 12-byte prefix {@code 124e7c5db2b2a8905d889ffd},
     * and every offline test still passed, because the offline fixture <em>builds</em> the loan and
     * bond UTxOs from this same constant — so both sides agreed with each other and nothing agreed
     * with the chain. The live test against production's wiring is what surfaced it: the real UTxO
     * carries the full name and the builder refused with
     * {@code UTXO_DOES_NOT_MATCH_ASSESSMENT: loan NFT … is 0, the assessment says 1}. A fixture that
     * manufactures both halves of a comparison can be wrong about the world and green forever.
     */
    private static final String LOAN_ID = "124e7c5db2b2a8905d889ffd90ae84e3a227dd271f999799d56b02ae";

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
    // ⛔ DERIVED FROM THE REGISTRY, not pinned as bech32. Literal addresses here survived the
    // 2026-09-04 re-point looking correct and then failed as RequiredRedeemersMismatch /
    // STRUCTURAL_ASSERTION_FAILED — the inputs stayed at the old script while every redeemer named
    // the new one. Those errors name script hashes and never mention addresses, so a stale fixture
    // reads as a builder bug. The stake halves are the real ones from the recorded UTxOs.
    private static final String LOAN_STAKE_KEY = "9e39d6f9824de5f24ac1d73243ebd54bbcaf764e56de11d0c23db9a8";
    private static final String LENDER_STAKE_KEY = "1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3";

    private static final String LOAN_ADDRESS =
            LoanFixtures.baseScriptAddress(REGISTRY.getLoanSpendScriptHash(), LOAN_STAKE_KEY);

    /** The bond's address: {@code lenderManagerSpendScriptHash} + the datum's own stake credential. */
    private static final String BOND_ADDRESS =
            LoanFixtures.baseScriptAddress(REGISTRY.getLenderManagerSpendScriptHash(), LENDER_STAKE_KEY);

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
    /**
     * One synthetic coordinate per validator, DERIVED from the enum rather than listed against it.
     * <p>
     * It used to be a literal map of six. When a seventh validator was added on 2026-08-25 the map
     * silently had no entry for it, {@code referenceScriptUtxos} dereferenced the null, and six tests
     * in this class failed with an NPE that named neither the validator nor the map. Generating the
     * map means the fixture cannot fall behind the enum again.
     */
    private static final Map<Validator, TransactionInput> COORDINATES;

    static {
        Map<Validator, TransactionInput> coordinates = new EnumMap<>(Validator.class);
        for (Validator validator : Validator.values()) {
            // Distinct, deterministic, and obviously synthetic. Nothing measured depends on the
            // value: a reference input is 33 bytes on the wire whatever it points at.
            String marker = String.format("%02x", 0xa1 + validator.ordinal());
            coordinates.put(validator, new TransactionInput(marker.repeat(32), 0));
        }
        COORDINATES = Map.copyOf(coordinates);
    }

    /**
     * The validators the PLAIN {@code Liquidate} path can reference — exactly the ones
     * {@link #referenceScripts(Set)} maps into the record.
     * <p>
     * Deliberately NOT {@code EnumSet.allOf}. {@code LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION} belongs
     * to the CONVERT path and this class evaluates the plain one, so "all validators" silently meant
     * "one more than this path can ever carry" and the body-vs-published count went 6 against an
     * expected 7. The set is named rather than counted because the distinction is real: these six are
     * what a plain Liquidate can shed, and the seventh is not one of them.
     */
    private static final Set<Validator> PLAIN_PATH_REFERENCEABLE = EnumSet.of(
            Validator.LOAN, Validator.LOAN_SPEND, Validator.LENDER_MANAGER,
            Validator.LENDER_MANAGER_SPEND, Validator.LOAN_CLAIM_ACTION, Validator.LM_LIQUIDATE_ACTION);

    private static final LiquidateTransactionBuilder.ReferenceScripts REFERENCE_SCRIPTS =
            referenceScripts(PLAIN_PATH_REFERENCEABLE);

    /**
     * The {@code ReferenceScripts} record for a chosen subset: a coordinate where the validator is
     * published, {@code null} where it is not. {@code assetManager} is always {@code null} — a plain
     * {@code Liquidate} never invokes it.
     */
    private static LiquidateTransactionBuilder.ReferenceScripts referenceScripts(
            Set<Validator> published) {
        return new LiquidateTransactionBuilder.ReferenceScripts(
                coordinate(published, Validator.LOAN),
                coordinate(published, Validator.LOAN_SPEND),
                coordinate(published, Validator.LENDER_MANAGER),
                coordinate(published, Validator.LENDER_MANAGER_SPEND),
                coordinate(published, Validator.LOAN_CLAIM_ACTION),
                coordinate(published, Validator.LM_LIQUIDATE_ACTION),
                null);
    }

    private static TransactionInput coordinate(Set<Validator> published, Validator validator) {
        return published.contains(validator) ? COORDINATES.get(validator) : null;
    }

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

    /**
     * <b>Production's priced path, on the real loan.</b> Everything else in this file builds through
     * the five-argument constructor — no evaluator, placeholder ex-units — and evaluates the finished
     * body separately. Production uses the six-argument one, so cardano-client-lib prices the
     * transaction <em>during</em> {@code build()} and a failed evaluation refuses the candidate. This
     * test is the only place that exercises that path, and it pins the three facts that decide whether
     * the separately-evaluated proof carries over to what production would submit:
     * <ol>
     *   <li><b>The priced build succeeds</b> and costs every redeemer with real ex-units.</li>
     *   <li><b>The body cardano-client-lib hands the evaluator has the same output layout as the body
     *       it returns.</b> Evaluation is scheduled before {@code ScriptBalanceTxProviders.balanceTx}
     *       (QuickTxBuilder:455 against :472), so the two bodies differ in fee and in the change
     *       output's coin — but the change output already exists by then, created with the inputs in
     *       {@code InputBuilders.createFromSender}, so no output is added, removed or reordered. That
     *       is what makes the observe-then-rebuild indexes valid for the body actually evaluated.</li>
     *   <li><b>Pricing the layout probe instead would fail</b>, at {@code loan_claim_action}: the probe
     *       carries the placeholder {@code lenderBondOutputIndex}, which aims at the bot's change
     *       output rather than at the bond echo. {@link LiquidateTransactionBuilder#complete} passes
     *       {@code priceScripts=false} for the probe precisely for this reason, and this is the
     *       measurement behind that decision — on this fixture, with five withdrawals, the refusal
     *       lands at {@code Withdraw#3}.</li>
     * </ol>
     */
    @Test
    void thePricedPathEvaluatesTheSameLayoutItShips() throws Exception {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction unpriced = build(fixture);
        EvalFixtures.evaluate(unpriced, universe, REGISTRY, extra);

        List<byte[]> seen = new ArrayList<>();
        com.bloxbean.cardano.aiken.AikenTransactionEvaluator aiken =
                new com.bloxbean.cardano.aiken.AikenTransactionEvaluator(
                        LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                        EvalFixtures.scriptSupplier(REGISTRY, extra),
                        com.bloxbean.cardano.client.common.model.SlotConfigs.preview());
        com.bloxbean.cardano.client.api.TransactionEvaluator recording = (cbor, inputs) -> {
            seen.add(cbor);
            return aiken.evaluateTx(cbor, inputs);
        };

        LiquidateTransactionBuilder pricedBuilder = new LiquidateTransactionBuilder(
                REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), recording);

        Transaction priced = null;
        String failure = null;
        try {
            priced = pricedBuilder.build(request(fixture));
        } catch (Exception e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
                if (t.getCause() == t) {
                    break;
                }
            }
            failure = chain.toString();
        }

        // (1) The priced build succeeds, and evaluates exactly once — one remote round trip in
        //     production, because the probe is deliberately not priced.
        assertNull(failure, "the priced path must build; it refused with:\n" + failure);
        assertEquals(1, seen.size(), "exactly one evaluation per build");

        Transaction evaluated = Transaction.deserialize(seen.getFirst());
        log.info("T024 EVALUATED body\n{}", layout(evaluated));
        log.info("T024 FINAL UNPRICED body\n{}", layout(unpriced));
        log.info("T024 FINAL PRICED body\n{}", layout(priced));

        // (2) Same outputs, in the same order, except for the change output's coin — which is the one
        //     thing balancing is allowed to move. Compared on address + datum + assets, so a reorder,
        //     an insertion or a removal all fail here.
        assertEquals(layoutKeys(evaluated), layoutKeys(priced),
                "the evaluated body's output layout must be the one that ships");
        assertEquals(layoutKeys(unpriced), layoutKeys(priced),
                "and the separately-evaluated proof must cover the same layout too");
        assertEquals(BigInteger.ZERO, evaluated.getBody().getFee(),
                "evaluation happens before balanceTx, so the fee is still unset");
        assertTrue(priced.getBody().getFee().signum() > 0, "the shipped body is balanced");

        // Every redeemer really carries measured ex-units rather than cardano-client-lib's placeholder.
        for (Redeemer redeemer : priced.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(BigInteger.valueOf(10_000)) > 0,
                    redeemer.getTag() + "#" + redeemer.getIndex() + " kept a placeholder ex-unit");
        }

        // (3) The probe's own shape — the placeholder lenderBondOutputIndex (0, the loan's ordinal)
        //     instead of the observed one (1) — is what a build that priced the probe would send.
        Transaction probeShaped = build(fixture);
        List<PlutusData> fields = new ArrayList<>(claimFields(probeShaped));
        fields.set(1, BigIntPlutusData.of(BigInteger.ZERO));
        ConstrPlutusData original = (ConstrPlutusData) rewardRedeemer(probeShaped,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash())).getData();
        rewardRedeemer(probeShaped, LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))
                .setData(constr(0, original.getData().getPlutusDataList().getFirst(),
                        ListPlutusData.of(constr(0, fields.toArray(PlutusData[]::new)))));

        EvalFixtures.Outcome probeOutcome =
                EvalFixtures.evaluateRaw(probeShaped, universe, REGISTRY, extra);
        log.info("T024 PROBE-SHAPED (placeholder lenderBondOutputIndex=0) outcome:\n{}",
                probeOutcome.detail());
        assertFalse(probeOutcome.successful(), "the probe's redeemers name the wrong output");
        assertTrue(probeOutcome.detail().contains(redeemerError(probeShaped,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "loan_claim_action must be the refuser, got: " + probeOutcome.detail());
    }

    /** One key per output — address, datum and assets, but not the coin, which balancing moves. */
    private static List<String> layoutKeys(Transaction tx) {
        return tx.getBody().getOutputs().stream()
                .map(o -> o.getAddress() + "|" + flattenedCount(o) + "|"
                        + (o.getInlineDatum() == null ? "-" : o.getInlineDatum().serializeToHex()))
                .toList();
    }

    /** Output layout + the index-bearing redeemer fields, for the T-024 comparison. */
    private static String layout(Transaction tx) {
        StringBuilder out = new StringBuilder();
        out.append("fee=").append(tx.getBody().getFee())
                .append(" inputs=").append(tx.getBody().getInputs().size())
                .append(" outputs=").append(tx.getBody().getOutputs().size()).append('\n');
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput o = outputs.get(i);
            String cred = paymentCredentialOf(o.getAddress());
            String what = cred.equals(REGISTRY.getLenderManagerSpendScriptHash()) ? "BOND-ECHO"
                    : cred.equals(REGISTRY.getAssetManagerSpendScriptHash()) ? "ASSET-MGR"
                    : "other/change";
            out.append("  [").append(i).append("] ").append(what)
                    .append(" cred=").append(cred.isEmpty() ? "(pubkey)" : cred.substring(0, 8))
                    .append(" coin=").append(o.getValue().getCoin())
                    .append(" assets=").append(flattenedCount(o))
                    .append(" datum=").append(o.getInlineDatum() == null ? "-"
                            : o.getInlineDatum().serializeToHex().substring(0, 16))
                    .append('\n');
        }
        try {
            out.append("  lenderBondOutputIndex=")
                    .append(((BigIntPlutusData) claimFields(tx).get(1)).getValue())
                    .append(" assetOutputIndexes=").append(assetOutputIndexes(tx)).append('\n');
        } catch (RuntimeException e) {
            out.append("  (redeemer indexes unreadable: ").append(e).append(")\n");
        }
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        for (int i = 0; i < withdrawals.size(); i++) {
            out.append("  withdraw#").append(i).append(' ')
                    .append(Map.of(
                            LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()), "loan",
                            LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()),
                            "loan_claim_action",
                            LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()),
                            "lenderManager",
                            LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()),
                            "lm_liquidate_action",
                            ORACLE_REWARD_ADDRESS, "oracle")
                            .getOrDefault(withdrawals.get(i).getRewardAddress(),
                                    withdrawals.get(i).getRewardAddress()))
                    .append('\n');
        }
        return out.toString();
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
    // T-023 — how few reference scripts are enough
    // ======================================================================================

    /**
     * One measured configuration: which validators travel by reference, what that costs to publish,
     * and what the resulting transaction costs to serialise and to submit.
     */
    private record Config(String label, Set<Validator> published, int unsignedBytes, int signedBytes,
                          long feeLovelace, long lockedLovelace) {

        boolean fits() {
            return signedBytes < MAX_TX_SIZE;
        }

        int headroom() {
            return MAX_TX_SIZE - signedBytes;
        }
    }

    /**
     * The minimum ada a batch of one witness would need for a dummy Ed25519 signature: a
     * {@code vkeywitness} is a 32-byte key and a 64-byte signature. Zeroed — nothing here verifies a
     * signature, only counts its bytes.
     */
    private static final VkeyWitness DUMMY_WITNESS = VkeyWitness.builder()
            .vkey(new byte[32])
            .signature(new byte[64])
            .build();

    /**
     * Only ever asked {@link ReferenceScriptPublisher#minAdaFor}, which needs the registry and the
     * protocol params and nothing else — hence the empty UTxO supplier. Real preview params, so the
     * locked-ada figures are the ones {@code ReferenceScriptPublisherTest} pins.
     */
    private static final ReferenceScriptPublisher PUBLISHER = new ReferenceScriptPublisher(
            REGISTRY, LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams());

    /**
     * <b>The T-023 deliverable.</b> Publishing reference scripts is not all-or-nothing: enough of them
     * have to travel by reference to get the transaction under {@code maxTxSize}, and every one of the
     * rest can stay in the witness set. This measures each candidate subset on the real loan.
     *
     * <h3>What is measured, and how</h3>
     * For every configuration the same real-loan liquidation is built through the production builder.
     * Sizes are {@code Transaction.serialize().length} of the deserialised body, twice: as built
     * (unsigned) and with one dummy vkey witness appended, which is what the finished transaction will
     * carry — the bot signs with one key. Nothing here is arithmetic standing in for a measurement.
     * <p>
     * Each configuration's universe holds a reference-script UTxO for <em>only</em> the validators it
     * publishes, and {@link #measure} asserts both halves of the shape off the finished body: exactly
     * {@code |published|} of the coordinates appear among the reference inputs, and exactly
     * {@code 6 - |published|} PlutusV3 scripts remain in the witness set. So a partial configuration is
     * genuinely partial — some referenced, the rest inline — rather than a set that silently dropped
     * the unpublished ones.
     *
     * <h3>Measured (2026-08-17, real loan 124e7c5d…, batch of one, maxTxSize 16_384)</h3>
     * <pre>
     *   configuration                        unsigned   signed   fits   headroom    locked ada
     *   all six referenced                      1_815    1_921    yes     14_463     86.837880
     *   none referenced (all six inline)       20_342   20_448     NO     -4_064      0.000000
     *   loanClaimAction only                   11_713   11_819    yes      4_565     38.359000
     *   lmLiquidateAction only                 16_148   16_254    yes        130     19.244150
     *   loanClaimAction + lmLiquidateAction     7_519    7_625    yes      8_759     57.603150
     *   the three largest (+ loan)              5_005    5_111    yes     11_273     69.606500
     *   everything but lenderManager            2_755    2_861    yes     13_523     81.640020
     * </pre>
     * The figures are re-measured on every run and logged; the assertions below pin the
     * <em>conclusions</em> (which configurations fit, which clear the margin) rather than the byte
     * counts, so an encoder change reports honestly instead of failing on an unrelated byte.
     *
     * <h3>The answer</h3>
     * <b>One published script is enough, and {@code loanClaimAction} is the one.</b> It is 8_662 of the
     * 18_720 validator bytes, and shedding it alone takes the transaction from 20_448 signed bytes to
     * <b>11_819, with 4_565 bytes of headroom</b>, locking <b>38.359 ada instead of 86.838</b>. This is
     * the configuration this repo ships.
     * <p>
     * {@code lmLiquidateAction} alone also fits — but at 16_254 bytes, <b>130 bytes under the limit</b>.
     * That is not a margin, and it is the reason the question had to be measured: reasoning about it
     * from the script table put the answer within a hundred bytes of the wrong side.
     * <p>
     * "Fits" is not "sensible". The margin rule {@link #sensible} adopts is <b>headroom ≥ 4_096 bytes</b>
     * (a quarter of {@code maxTxSize}) <b>and ≥ the largest still-inline script</b>. The second clause is
     * the load-bearing one: this transaction grows in units of whole scripts, so headroom smaller than a
     * script it already carries is one change away from not fitting. The shipped one-script configuration
     * <b>clears both clauses — but the second only by 338 bytes</b>: 4_565 of headroom against
     * {@code lmLiquidateAction}'s 4_227 inline bytes. It passes, and it passes narrowly. Publishing
     * {@code lmLiquidateAction} as well (57.60 ada) would give 8_759 bytes against a 2_547-byte largest
     * inline script and clear both clauses threefold — that is the upgrade if the margin is ever wanted,
     * not a correction of the shipped choice.
     * <p>
     * <b>The margin also has to absorb batching, and that is not measured here.</b> Every figure is for a
     * batch of <em>one</em> loan; each additional loan adds two inputs, two outputs, a {@code ClaimData}
     * and an {@code assetOutputIndex}. The real fixture is a single loan, so the per-loan marginal cost is
     * the first thing to measure before relying on 4_565 bytes.
     *
     * <h3>What is deliberately <em>not</em> claimed here</h3>
     * {@link Config#feeLovelace()} is captured and logged but <b>no conclusion is drawn from it, and no
     * per-submission cost comparison is made</b>. Two things would have to be settled first, and neither
     * is in this slice's scope: the builder used here has no script-cost evaluator, so every redeemer
     * carries cardano-client-lib's placeholder ex-units and the execution term of the fee is a fiction;
     * and the reference-script tier fee cardano-client-lib computes for the all-referenced case works out
     * at ~59 lovelace per script byte against a {@code minFeeRefScriptCostPerByte} of 15, which this test
     * has not explained and will not assert around. A fee table built on either would be a number that
     * looks measured and is not.
     */
    @Test
    void theCheapestReferenceScriptSubsetThatFitsIsMeasured() {
        Fixture fixture = fixture();

        Map<String, Set<Validator>> subsets = new LinkedHashMap<>();
        subsets.put("all six referenced", PLAIN_PATH_REFERENCEABLE);
        subsets.put("none referenced (all six inline)", EnumSet.noneOf(Validator.class));
        subsets.put("loanClaimAction only", EnumSet.of(Validator.LOAN_CLAIM_ACTION));
        subsets.put("lmLiquidateAction only", EnumSet.of(Validator.LM_LIQUIDATE_ACTION));
        subsets.put("loanClaimAction + lmLiquidateAction",
                EnumSet.of(Validator.LOAN_CLAIM_ACTION, Validator.LM_LIQUIDATE_ACTION));
        subsets.put("the three largest (+ loan)",
                EnumSet.of(Validator.LOAN_CLAIM_ACTION, Validator.LM_LIQUIDATE_ACTION, Validator.LOAN));
        // Complement WITHIN the plain path, not within the enum: EnumSet.complementOf would include
        // the convert path's action validator, which this path cannot reference at all.
        Set<Validator> allButLenderManager = EnumSet.copyOf(PLAIN_PATH_REFERENCEABLE);
        allButLenderManager.remove(Validator.LENDER_MANAGER);
        subsets.put("everything but lenderManager", allButLenderManager);

        Map<String, Config> measured = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Validator>> subset : subsets.entrySet()) {
            measured.put(subset.getKey(), measure(fixture, subset.getKey(), subset.getValue()));
        }

        log.info("reference-script subsets, real loan {}, batch of one, maxTxSize {}:",
                LOAN_ID, MAX_TX_SIZE);
        for (Config config : measured.values()) {
            log.info("  {}", "%-38s unsigned %6d  signed %6d  fits %-5s  headroom %7d  locked %11d  fee %9d"
                    .formatted(config.label(), config.unsignedBytes(), config.signedBytes(),
                            config.fits(), config.headroom(), config.lockedLovelace(),
                            config.feeLovelace()));
        }

        Config all = measured.get("all six referenced");
        Config none = measured.get("none referenced (all six inline)");
        Config claimOnly = measured.get("loanClaimAction only");
        Config lmOnly = measured.get("lmLiquidateAction only");
        Config two = measured.get("loanClaimAction + lmLiquidateAction");

        // The two ends of the question.
        assertTrue(all.fits(), "the all-referenced baseline must fit: " + all.signedBytes());
        assertFalse(none.fits(), "all six inline must not fit: " + none.signedBytes());

        // Giovanni's hypothesis, tested rather than adopted: one published script is enough — but not
        // just any one. loanClaimAction sheds 8_662 bytes and clears the limit; lmLiquidateAction sheds
        // 4_227 and does not.
        // THE SHIPPING CONFIGURATION. Five validators inline, loanClaimAction by reference.
        assertTrue(claimOnly.fits(),
                "loanClaimAction alone must be enough to fit: " + claimOnly.signedBytes());
        assertTrue(claimOnly.lockedLovelace() < all.lockedLovelace(),
                "the point of publishing fewer is locking less");

        // lmLiquidateAction alone also fits — but by 130 bytes, which is not a margin. Asserted in the
        // direction the measurement found rather than the direction it was predicted in: the brief
        // reasoned it would clear the limit by ~76 bytes and it clears by 130, so "it fits" was the
        // right call and the exact figure was not. Both are why this was measured.
        assertTrue(lmOnly.fits(), "lmLiquidateAction alone fits, barely: " + lmOnly.signedBytes());
        assertFalse(sensible(lmOnly),
                "lmLiquidateAction alone must not clear the margin rule: headroom " + lmOnly.headroom());

        // The margin rule this file adopts, applied. The shipped one-script configuration clears it —
        // by 338 bytes on the binding clause, which is thin but is what the rule says, and the rule was
        // written down before the numbers were in. Asserted rather than narrated so that a future
        // encoder change which eats those 338 bytes turns this red instead of going unnoticed.
        assertTrue(sensible(claimOnly),
                "loanClaimAction alone must clear the margin rule: headroom " + claimOnly.headroom()
                        + " against a largest inline script of "
                        + largestInlineScriptBytes(claimOnly.published()));
        assertTrue(claimOnly.headroom() - largestInlineScriptBytes(claimOnly.published()) < 1_000,
                "and it clears the binding clause only narrowly — if this ever becomes comfortable, "
                        + "the recommendation in the javadoc above is stale");
        assertTrue(sensible(two),
                "loanClaimAction + lmLiquidateAction must clear the margin rule: headroom "
                        + two.headroom() + " against a largest inline script of "
                        + largestInlineScriptBytes(two.published()));
    }

    /**
     * Builds the real-loan liquidation with exactly {@code published} travelling by reference, and
     * measures it. The unpublished validators travel in the witness set — the builder attaches all six
     * unconditionally and {@code removeDuplicateScriptWitnesses} strips only the ones it was told are
     * published, so a partial set is expressible without any change to the builder.
     */
    private static Config measure(Fixture fixture, String label, Set<Validator> published) {
        Transaction built = builder(fixture, published)
                .build(request(fixture, VALID_FROM, VALID_TO, referenceScripts(published)));
        Transaction tx = deserialise(built);

        assertEquals(published.size(), referencedScriptCount(tx),
                label + ": the body must carry exactly the published scripts by reference");
        List<PlutusScript> inline = tx.getWitnessSet().getPlutusV3Scripts() == null
                ? List.of()
                : List.copyOf(tx.getWitnessSet().getPlutusV3Scripts());
        assertEquals(6 - published.size(), inline.size(),
                label + ": every unpublished validator must travel in the witness set");

        int unsigned = serializedSize(tx);
        if (tx.getWitnessSet().getVkeyWitnesses() == null) {
            tx.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        }
        tx.getWitnessSet().getVkeyWitnesses().add(DUMMY_WITNESS);
        int signed = serializedSize(deserialise(tx));

        long locked = 0L;
        for (Validator validator : published) {
            locked += PUBLISHER.minAdaFor(validator, LoanFixtures.botAddress());
        }
        return new Config(label, published, unsigned, signed,
                built.getBody().getFee().longValueExact(), locked);
    }

    /** How many of the body's reference inputs resolve to a published loans-v4 script. */
    private static int referencedScriptCount(Transaction tx) {
        List<TransactionInput> refInputs = tx.getBody().getReferenceInputs();
        return (int) COORDINATES.values().stream().filter(refInputs::contains).count();
    }

    /**
     * The margin rule. "It fits" is not the bar: the transaction has to keep fitting when it carries a
     * batch rather than one loan, and it grows in units of whole scripts, so the headroom must be at
     * least a quarter of {@code maxTxSize} <em>and</em> at least as large as the biggest script still
     * travelling inline.
     */
    private static boolean sensible(Config config) {
        return config.fits()
                && config.headroom() >= MAX_TX_SIZE / 4
                && config.headroom() >= largestInlineScriptBytes(config.published());
    }

    /** Applied bytes of the largest validator this configuration still carries in the witness set. */
    private static int largestInlineScriptBytes(Set<Validator> published) {
        return PLAIN_PATH_REFERENCEABLE.stream()
                .filter(validator -> !published.contains(validator))
                .mapToInt(validator -> appliedBytes(scriptOf(validator)))
                .max()
                .orElse(0);
    }

    /** Applied script body bytes — the same measure {@code ReferenceScriptPublisherTest} pins. */
    private static int appliedBytes(PlutusScript script) {
        try {
            return script.serializeScriptBody().length;
        } catch (Exception e) {
            throw new AssertionError("cannot size an applied loans-v4 script", e);
        }
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
        return universe(fixture, PLAIN_PATH_REFERENCEABLE);
    }

    /**
     * As above, but publishing only {@code published}. Every other validator has no reference-script
     * UTxO in the universe at all, so a build that referenced one anyway could not resolve it — which
     * is what makes the partial configurations in
     * {@link #theCheapestReferenceScriptSubsetThatFitsIsMeasured()} genuinely partial rather than
     * six-published-but-only-some-declared.
     */
    private static List<Utxo> universe(Fixture fixture, Set<Validator> published) {
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

        universe.addAll(referenceScriptUtxos(published));
        return universe;
    }

    /** A UTxO per published coordinate, each carrying its real derived script hash. */
    private static List<Utxo> referenceScriptUtxos(Set<Validator> published) {
        List<Utxo> utxos = new ArrayList<>();
        for (Validator validator : published) {
            TransactionInput coordinate = COORDINATES.get(validator);
            String scriptHash = scriptHash(scriptOf(validator));
            utxos.add(Utxo.builder()
                    .txHash(coordinate.getTransactionId())
                    .outputIndex(coordinate.getIndex())
                    .address(LoanFixtures.entAddress(scriptHash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(scriptHash)
                    .build());
        }
        return utxos;
    }

    /** The applied script the registry derives for one publishable validator. */
    private static PlutusScript scriptOf(Validator validator) {
        return switch (validator) {
            case LOAN -> REGISTRY.getLoanScript();
            case LOAN_SPEND -> REGISTRY.getLoanSpendScript();
            case LENDER_MANAGER -> REGISTRY.getLenderManagerScript();
            case LENDER_MANAGER_SPEND -> REGISTRY.getLenderManagerSpendScript();
            case LOAN_CLAIM_ACTION -> REGISTRY.getLoanClaimActionScript();
            case LM_LIQUIDATE_ACTION -> REGISTRY.getLmLiquidateActionScript();
            case LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION ->
                    REGISTRY.getLmLiquidateAndPayInAdvanceActionScript();
            // The compound path's four; this rig publishes only liquidation validators, but the
            // switch must stay exhaustive or the enum cannot grow.
            case LM_COMPOUND_ACTION -> REGISTRY.getLmCompoundActionScript();
            case POOL_COMPOUND_ACTION -> REGISTRY.getPoolCompoundActionScript();
            case ASSET_MANAGER -> REGISTRY.getAssetManagerScript();
            case POOL_MANAGER -> REGISTRY.getPoolManagerScript();
        };
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
        return request(fixture, validFrom, validTo, REFERENCE_SCRIPTS);
    }

    private static LiquidateTransactionBuilder.Request request(
            Fixture fixture, long validFrom, long validTo,
            LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
        return new LiquidateTransactionBuilder.Request(
                List.of(fixture.toLiquidation()), CONFIG_UTXO, LM_CONFIG_UTXO,
                Map.of(ORACLE_NFT.toUnit(), fixture.oracle()), WALLET_UTXO, LoanFixtures.botAddress(),
                validFrom, validTo, MARGIN, referenceScripts);
    }

    /**
     * The same builder, resolving against a universe that publishes only {@code published} — so a
     * configuration cannot accidentally reach a reference script it did not publish.
     */
    private static LiquidateTransactionBuilder builder(Fixture fixture, Set<Validator> published) {
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe(fixture, published)), EvalFixtures.protocolParams());
    }

    private static Transaction build(Fixture fixture) {
        Transaction tx = builder(fixture).build(request(fixture));
        assertNotNull(tx);
        return tx;
    }

    // ---- LIVE: production's exact wiring, against preview ---------------------------------------

    /**
     * <b>The one test that builds through PRODUCTION'S wiring against the REAL chain.</b> Every other
     * test in this class hands the builder a synthetic universe and an offline evaluator. This one
     * hands it exactly what {@code YaciConfig} hands it: Blockfrost's utxo supplier, protocol params,
     * <em>script supplier</em> and {@code /utils/txs/evaluate} evaluator — and it references the
     * reference script we actually published, {@code 48c102c0…#0}, whose bytes therefore have to be
     * fetched from chain by hash rather than handed in.
     * <p>
     * That is the gap Giovanni named: the offline rig proved the transaction against the validators,
     * but the offline rig resolves reference scripts through its own supplier, not through the one
     * production runs. A builder that cannot fetch a reference script cannot price a transaction that
     * depends on one, and until this test nothing exercised that path end to end. The assertion that
     * matters is the last one: the redeemers on the <em>built</em> transaction carry Blockfrost's
     * ex-units, not cardano-client-lib's placeholders.
     * <p>
     * Gated on {@code BLOCKFROST_KEY}; it makes network calls and is not part of the cold suite. It
     * submits nothing: the builder has no {@code TransactionProcessor}, and there is no signer here.
     */
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+")
    @Test
    void productionWiringPricesTheRealLoanAgainstTheRealChainThroughThePublishedReferenceScript() throws Exception {
        var bf = new com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService(
                "https://cardano-preview.blockfrost.io/api/v0/", System.getenv("BLOCKFROST_KEY"));
        var utxoSupplier = new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(bf.getUtxoService());
        com.bloxbean.cardano.client.api.TransactionEvaluator evaluator =
                (cbor, inputs) -> bf.getTransactionService().evaluateTx(cbor);

        // Production's constructor: the whole BackendService, exactly as YaciConfig hands it over.
        LiquidateTransactionBuilder production = new LiquidateTransactionBuilder(
                REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(), bf, evaluator);

        // Real chain state, fetched now rather than pinned: the loan, the bond, the two configs, a
        // fee/collateral utxo, and the live oracle feed. If any of these has moved this test says so.
        Utxo loanUtxo = utxoSupplier.getTxOutput(LOAN_TX, LOAN_OUTPUT_INDEX).orElseThrow(
                () -> new AssertionError("loan utxo spent — the loan may have been liquidated"));
        Utxo bondUtxo = utxoSupplier.getTxOutput(LOAN_TX, BOND_OUTPUT_INDEX).orElseThrow();
        Utxo configUtxo = utxoSupplier.getTxOutput(
                "7374a98596cf03c323a0dd1643178861301f1060646789ae4d385ec3e54be781", 0).orElseThrow();
        Utxo lmConfigUtxo = utxoSupplier.getTxOutput(
                "7374a98596cf03c323a0dd1643178861301f1060646789ae4d385ec3e54be781", 1).orElseThrow();
        Utxo walletUtxo = utxoSupplier.getTxOutput(
                "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd", 1).orElseThrow();
        String changeAddress = walletUtxo.getAddress();

        var registry = new FluidOracleClient("https://testapi.fluidtokens.com/get-oracle-tokens");
        registry.refresh(); // outside Spring the scheduled refresh never runs; populate it once
        OracleEntry oracle = registry.entries().stream()
                .filter(e -> e.oracleToken() != null && e.oracleToken().toUnit().equals(ORACLE_NFT.toUnit()))
                .findFirst().orElseThrow(() -> new AssertionError("tFLDT oracle entry not in registry"));

        long now = System.currentTimeMillis();
        long validFrom = now - 30_000L;
        long validTo = now + 120_000L;

        Loan loan = new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), loanDatum());
        LenderBond bond = new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID,
                BOND_DATUM_HEX, bondDatum());
        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, OraclePriceFeed.unit(),
                oracle.feed(), validFrom);
        assertTrue(assessment.buildable(), "not buildable right now: " + assessment.detail());

        var published = new LiquidateTransactionBuilder.ReferenceScripts(null, null, null, null,
                new TransactionInput("48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd", 0),
                null, null);
        var request = new LiquidateTransactionBuilder.Request(
                List.of(new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo, bondUtxo)),
                configUtxo, lmConfigUtxo, Map.of(ORACLE_NFT.toUnit(), oracle), walletUtxo, changeAddress,
                validFrom, validTo, 30_000L, published);

        Transaction tx = production.build(request);

        // The claims that matter, all read off the built artefact.
        Transaction round = deserialise(tx);
        assertEquals(5, round.getWitnessSet().getPlutusV3Scripts().size(),
                "five validators inline; loan_claim_action must have been dropped from the witness set");
        assertTrue(round.getBody().getReferenceInputs().stream().anyMatch(i ->
                        i.getTransactionId().equals("48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd")
                                && i.getIndex() == 0),
                "the published reference script must be a reference input");
        for (Redeemer r : round.getWitnessSet().getRedeemers()) {
            assertTrue(r.getExUnits().getMem().longValue() > 10_000L,
                    "placeholder mem on " + r.getTag() + "#" + r.getIndex()
                            + " — Blockfrost did not price this redeemer");
        }
        long size = tx.serialize().length;
        assertTrue(size < 16_384, "size " + size);
        log.info("LIVE priced build OK: {} bytes, fee {}, redeemers {}", size,
                round.getBody().getFee(), round.getWitnessSet().getRedeemers().size());
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
