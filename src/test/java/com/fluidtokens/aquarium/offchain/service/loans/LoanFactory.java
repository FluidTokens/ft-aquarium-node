package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;

import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The T-016 F origination <b>tool</b>: composes the three landed pool builders into a
 * <em>create → borrow</em> pipeline that originates a born-liquidatable, fee-100 loan on preview,
 * plus a <em>recovery cancel</em> for the pool. Every transaction it hands back has been
 * dry-evaluated against the real deployed validators through {@link EvalFixtures}; the tool
 * <b>refuses</b> — by throwing {@link GateFailure} — rather than return a transaction that is not
 * fee-100, not born-liquidatable, whose recovery does not return value to the lender, or that fails
 * dry-evaluation.
 *
 * <h2>It composes; it does not re-implement</h2>
 * This class holds no redeemer construction and no transaction assembly of its own: it builds a
 * {@link PoolCreateTransactionBuilder.Request}, a {@link PoolBorrowTransactionBuilder.Request} and a
 * {@link PoolCancelTransactionBuilder.Request} from a {@link Recipe}, and delegates every mint,
 * withdrawal, output and validity decision to the three landed builders. The pool datum, loan datum
 * and lender-bond datum are the same {@link PoolFixtures} / {@link LoanFixtures} machinery the
 * builders' own dry-eval tests use — parameterised by the {@link Recipe}, never hardcoded to
 * {@link PoolFixtures#defaults()}.
 *
 * <h2>Structurally incapable of submitting</h2>
 * Each of the three builders constructs its {@code QuickTxBuilder} with a <b>null</b>
 * {@code TransactionProcessor} — the only thing in cardano-client-lib that can put a transaction on a
 * network. This tool holds none of its own, opens no backend, signs nothing, and returns unsigned
 * transactions. There is deliberately no signer, no flag-gated submit path and no backend wiring here,
 * not even an off-by-default one. The optional {@code TransactionEvaluator} below does not change that:
 * an evaluator only measures script cost, it cannot submit.
 *
 * <h2>The optional evaluator</h2>
 * Constructed without one (the four-argument constructor) this is the build-only tool it has always
 * been, byte-for-byte: no evaluator reaches the builders, their redeemers keep {@code ScriptTx}'s
 * placeholder {@code mem 10000 / steps 10000}, and the ex-units gate stays silent. Constructed
 * <em>with</em> one, all three builders wire it, the returned transactions carry real execution budgets
 * and a fee computed from them, and the ex-units gate is armed.
 *
 * <h2>The gates, each read off the finished body</h2>
 * <ul>
 *   <li><b>Dry-eval</b> — every returned transaction passed {@link EvalFixtures#evaluateRaw}; a
 *       non-green build is refused, not returned.</li>
 *   <li><b>Ex-units</b> — <em>only when a {@code TransactionEvaluator} is configured</em>: every
 *       redeemer of the returned body must declare at least the budget an independent measurement of
 *       that same body reports ({@link #measureBudgets}), and must not still carry {@code ScriptTx}'s
 *       placeholder. Without an evaluator the transactions are explicitly build-only and keep their
 *       placeholders, so this gate stays silent — see {@link #assertExUnitsAreReal}.</li>
 *   <li><b>Fee-100</b> — {@link #buildBorrow} decodes the emitted lender-bond output's inline datum
 *       with the production {@link LenderManagerDatumConverter} and refuses unless
 *       {@code liquidationFeePerMille == 100}. This is a hard refusal inside the tool, not a test
 *       assertion, so a fee-0 pool can never yield a returned borrow transaction.</li>
 *   <li><b>Born-liquidatable</b> — {@link #buildBorrow} calls
 *       {@link PoolFixtures#assertBornLiquidatable} at the recipe's collateral price and refuses if
 *       it throws.</li>
 *   <li><b>Recovery destination</b> — {@link #buildRecoveryCancel} refuses unless the finished body
 *       has no output at the pool address and every output goes to the lender (findings §14: the
 *       validators constrain no output, so this is the only backstop).</li>
 *   <li><b>Ledger preflight</b> — <em>only when a {@code TransactionEvaluator} is configured</em>: no
 *       script may travel both in the witness set and as a reference script on a resolved input, which
 *       Conway refuses as {@code ExtraneousScriptWitnessesUTXOW}. See
 *       {@link #assertNoDuplicateScriptWitnesses}.</li>
 * </ul>
 *
 * <h2>What these gates do not check, and who does</h2>
 * {@link EvalFixtures} runs the scripts and nothing else — its own javadoc says so — and every gate
 * above reads the finished body. <b>Fee adequacy is not gated here and must not be:</b> the fee is
 * cardano-client-lib's to compute, off the protocol parameters the build was balanced with, and the
 * three builders' job is to hand it the facts it needs (see
 * {@link PoolCreateTransactionBuilder}'s reference-script wiring). A gate that recomputed the fee would
 * be a second, divergent implementation of a rule the ledger already owns.
 */
public final class LoanFactory {

    /** The named refusal every gate throws — so a caller (or test) asserts on the gate, not on an NPE. */
    public static final class GateFailure extends RuntimeException {
        public GateFailure(String message) {
            super(message);
        }
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    /**
     * The evaluator handed to all three builders, or null. Null is the build-only tool this class was
     * born as: placeholder ex-units, understated fee, {@link #EX_UNITS_GATE} silent. Non-null makes the
     * returned transactions carry real budgets and arms that gate.
     */
    private final TransactionEvaluator txEvaluator;

    /**
     * The pool-create builder, constructed once with the given supplier: pool creation collects its
     * seed by name and coin-selects the funder, and needs no post-create pool UTxO. The borrow and
     * cancel builders are constructed per call, over a supplier that also resolves the pool
     * continuation this pipeline produces — see {@link #resolving(Utxo)}.
     */
    private final PoolCreateTransactionBuilder poolCreateBuilder;

    /** No evaluator: the build-only tool, unchanged. */
    public LoanFactory(LoansContractRegistry registry,
                       Network network,
                       UtxoSupplier utxoSupplier,
                       ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null);
    }

    public LoanFactory(LoansContractRegistry registry,
                       Network network,
                       UtxoSupplier utxoSupplier,
                       ProtocolParamsSupplier protocolParamsSupplier,
                       TransactionEvaluator txEvaluator) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.txEvaluator = txEvaluator;
        this.poolCreateBuilder = new PoolCreateTransactionBuilder(registry, utxoSupplier,
                protocolParamsSupplier, txEvaluator);
    }

    /**
     * Everything the create → borrow → recovery-cancel pipeline needs, all of it a parameter.
     *
     * @param params                      the pool knobs — principal floor, no-oracle collateral
     *                                    floor, rates, LTV, penalty and, load-bearing here, the
     *                                    liquidation fee the fee-100 gate reads back off the bond
     * @param lender                      the lender wallet: funder, change, recovery required signer,
     *                                    and the address every recovered lovelace must return to
     * @param borrower                    the borrower wallet; one wallet plays both roles, so this is
     *                                    normally the same address as {@code lender}
     * @param collateralPriceNumerator    lovelace numerator of the collateral price the born-liquidatable
     *                                    gate is checked at
     * @param collateralPriceDenominator  denominator of that price
     * @param seedUtxo                    the pool-create seed: the mint's {@code inputRef} and the
     *                                    liquidity/fee source
     * @param funderUtxo                  the borrow/cancel funder input: fees, the collateral the loan
     *                                    output posts, and (for cancel) the input collected beside the
     *                                    pool
     * @param configUtxo                  the loans config reference input
     * @param poolPolicyRefScriptUtxo     the published pool-policy reference-script UTxO the create
     *                                    transaction reads
     * @param borrowReferenceScriptUtxos  the six published reference-script UTxOs the borrow reads
     * @param cancelReferenceScriptUtxos  the three published reference-script UTxOs the cancel reads
     * @param collateralAsset             the collateral token the loan output posts (tFLDT)
     * @param wantedPrincipalAmount       the principal drawn from the pool at borrow
     * @param chosenCollateralIndex       index into the pool datum's collateral options (0 for our pool)
     * @param loanOutputLovelace          the loan output's lovelace leg
     * @param lenderBondLovelace          the lender-bond output's lovelace leg
     * @param borrowerBondLovelace        the borrower-bond output's lovelace leg
     * @param validFromSlot               the borrow validity lower bound, in slots
     * @param validToSlot                 the borrow validity upper bound, in slots; its POSIX time is
     *                                    the loan datum's {@code lendDate}
     */
    public record Recipe(PoolFixtures.PoolParameters params,
                         Address lender,
                         Address borrower,
                         long collateralPriceNumerator,
                         long collateralPriceDenominator,
                         Utxo seedUtxo,
                         Utxo funderUtxo,
                         Utxo configUtxo,
                         Utxo poolPolicyRefScriptUtxo,
                         List<Utxo> borrowReferenceScriptUtxos,
                         List<Utxo> cancelReferenceScriptUtxos,
                         AssetType collateralAsset,
                         long wantedPrincipalAmount,
                         long chosenCollateralIndex,
                         long loanOutputLovelace,
                         long lenderBondLovelace,
                         long borrowerBondLovelace,
                         long validFromSlot,
                         long validToSlot) {
    }

    // ---- create -----------------------------------------------------------------------------------

    /**
     * Builds the pool-creation transaction by delegating to {@link PoolCreateTransactionBuilder}, then
     * dry-evaluates it against the deployed {@code pool.pool} policy. Refuses (throws
     * {@link GateFailure}) on any non-green evaluation.
     */
    public Transaction buildCreate(Recipe recipe) {
        return buildCreate(recipe, PoolCreateTransactionBuilder.Mutation.none(), true);
    }

    /**
     * As {@link #buildCreate}, but from a create builder whose reference-script wiring is omitted — the
     * pre-fix shape, in which the {@code pool.pool} policy travels both in the witness set and on a
     * reference input, and no reference-script fee is paid for it. <b>The gates still run</b>, which is
     * the point: this is the seam {@code LoanFactoryDryEvalTest} uses to prove that
     * {@code LEDGER_PREFLIGHT} refuses the very transaction the preview ledger refused with
     * {@code ExtraneousScriptWitnessesUTXOW}. Package-private, no production caller.
     */
    Transaction buildCreateWithoutReferenceScriptWiring(Recipe recipe) {
        return buildCreate(recipe, new PoolCreateTransactionBuilder.Mutation(true), true);
    }

    /**
     * The same pre-fix shape, handed back with <b>no gate run over it</b> — so a test can measure what it
     * costs and compare. {@link #buildCreateWithoutReferenceScriptWiring} above is what proves the gate
     * refuses this body; this exists only to be measured, never to be returned to a caller who might
     * submit it. Package-private, no production caller.
     */
    Transaction assembleCreateWithoutReferenceScriptWiring(Recipe recipe) {
        return buildCreate(recipe, new PoolCreateTransactionBuilder.Mutation(true), false);
    }

    private Transaction buildCreate(Recipe recipe, PoolCreateTransactionBuilder.Mutation mutation,
                                    boolean gate) {
        PoolCreateTransactionBuilder.Request request = new PoolCreateTransactionBuilder.Request(
                recipe.seedUtxo(),
                recipe.configUtxo(),
                recipe.poolPolicyRefScriptUtxo(),
                funderAddress(recipe),
                poolAddress(),
                poolAssetName(recipe),
                PoolTxEncoder.poolDatum(poolDatum(recipe)),
                recipe.params().poolLiquidityLovelace());

        Transaction tx = poolCreateBuilder.build(request, mutation);
        if (gate) {
            dryEval(tx, universeOf(tx, utxoSupplier, List.of(recipe.poolPolicyRefScriptUtxo())),
                    createExtraScripts());
        }
        return tx;
    }

    // ---- borrow -----------------------------------------------------------------------------------

    /**
     * Builds the pool-borrow transaction against the pool this pipeline just created — its input is
     * <b>output 0</b> of {@code poolCreateTx}, the create builder's proven pool-output-0 placement.
     * Delegates to {@link PoolBorrowTransactionBuilder}, dry-evaluates, then enforces the fee-100 gate
     * (the emitted lender bond, decoded with {@link LenderManagerDatumConverter}, must carry
     * {@code liquidationFeePerMille == 100}) and the born-liquidatable gate. Any failure throws
     * {@link GateFailure}.
     */
    public Transaction buildBorrow(Recipe recipe, Transaction poolCreateTx) {
        Utxo poolUtxo = poolOutputZero(recipe, poolCreateTx);
        TransactionInput poolRef = new TransactionInput(TransactionUtil.getTxHash(poolCreateTx), 0);
        String bondAssetName = PoolTxEncoder.bondAssetName(poolRef);
        long lendDateMillis = LoanFixtures.converters().slot()
                .slotToTime(recipe.validToSlot()).toInstant(ZoneOffset.UTC).toEpochMilli();

        LoanDatum loanDatum = PoolFixtures.borrowLoanDatum(
                recipe.params(), poolAssetName(recipe), recipe.wantedPrincipalAmount(), lendDateMillis);
        long neededCollateral =
                PoolFixtures.neededCollateral(recipe.params(), recipe.wantedPrincipalAmount()).longValueExact();

        PoolBorrowTransactionBuilder.Request request = new PoolBorrowTransactionBuilder.Request(
                poolUtxo,
                recipe.funderUtxo(),
                recipe.configUtxo(),
                recipe.borrowReferenceScriptUtxos(),
                funderAddress(recipe),
                recipe.borrower(),
                recipe.wantedPrincipalAmount(),
                recipe.chosenCollateralIndex(),
                recipe.collateralAsset(),
                neededCollateral,
                recipe.loanOutputLovelace(),
                poolAssetName(recipe),
                bondAssetName,
                LoanFixtures.encode(loanDatum),
                lenderBondAddress().getAddress(),
                LoanFixtures.encode(bondDatum(recipe)),
                recipe.lenderBondLovelace(),
                recipe.borrowerBondLovelace(),
                recipe.validFromSlot(),
                recipe.validToSlot());

        Transaction tx = borrowBuilder(poolUtxo).build(request);
        dryEval(tx, universeOf(tx, resolving(poolUtxo), recipe.borrowReferenceScriptUtxos()),
                borrowExtraScripts());
        assertEmittedBondFeeIs100(tx, bondAssetName);
        assertBornLiquidatable(recipe);
        return tx;
    }

    // ---- recovery cancel --------------------------------------------------------------------------

    /**
     * Builds the recovery cancel for the pool carried by {@code poolTx} — its pool input is output 0
     * of that transaction. The change, fee payer and recovery required signer are the lender,
     * explicitly. Delegates to {@link PoolCancelTransactionBuilder}, dry-evaluates, then enforces the
     * recovery-destination gate: no output at the pool address, every output at the lender.
     */
    public Transaction buildRecoveryCancel(Recipe recipe, Transaction poolTx) {
        return buildRecoveryCancel(recipe, poolTx, recipe.lender());
    }

    /**
     * As {@link #buildRecoveryCancel(Recipe, Transaction)}, but with the change/fee/collateral address
     * overridden while the recovery required signer stays the lender (so the transaction still
     * dry-evaluates green) and the destination gate still checks the finished body against the
     * <em>lender</em>. This is the seam {@code LoanFactoryDryEvalTest}'s F-c case uses to prove the
     * tool's own destination gate refuses a cancel that sends the recovered value anywhere but the
     * lender — the gate the deployed validators do not provide.
     */
    Transaction buildRecoveryCancel(Recipe recipe, Transaction poolTx, Address changeSigner) {
        Utxo poolUtxo = poolOutputZero(recipe, poolTx);

        PoolCancelTransactionBuilder.Request request = new PoolCancelTransactionBuilder.Request(
                poolUtxo,
                recipe.funderUtxo(),
                recipe.configUtxo(),
                recipe.cancelReferenceScriptUtxos(),
                changeSigner.getAddress(),
                lenderKeyHash(recipe),
                poolAssetName(recipe));

        Transaction tx = cancelBuilder(poolUtxo).build(request);
        dryEval(tx, universeOf(tx, resolving(poolUtxo), recipe.cancelReferenceScriptUtxos()),
                cancelExtraScripts());
        assertRecoveryReturnsToLender(recipe, tx);
        return tx;
    }

    // ---- gates ------------------------------------------------------------------------------------

    /**
     * The dry-eval gate, immediately followed by the ex-units and ledger-preflight gates over the same
     * finished body and the same resolved universe.
     */
    private void dryEval(Transaction tx, List<Utxo> universe, List<PlutusScript> extra) {
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(tx, universe, registry, extra);
        if (!outcome.successful()) {
            throw new GateFailure("DRY_EVAL_GATE: the transaction was refused by the real validators: "
                    + outcome.detail());
        }
        assertExUnitsAreReal(tx, universe, extra);
        assertNoDuplicateScriptWitnesses(tx, universe);
    }

    /** The placeholder budget {@code ScriptTx} stamps on every redeemer it creates. */
    private static final long PLACEHOLDER_EX_UNIT = 10_000L;

    /** The gate's name, as it appears in every refusal it throws. */
    private static final String EX_UNITS_GATE = "EX_UNITS_GATE";

    /**
     * The ex-units gate: when an evaluator is configured, every redeemer of the returned body must
     * declare a budget at least as large as an <em>independent</em> measurement of the same body, and
     * must not still carry the placeholder.
     * <p>
     * <b>Silent without an evaluator.</b> A no-evaluator {@link LoanFactory} is explicitly build-only —
     * cardano-client-lib swallows the "Transaction evaluator is not set" throw and leaves
     * {@code ScriptTx}'s {@code mem 10000 / steps 10000} in place — and those transactions are never
     * meant to be submitted, so firing here would redden the whole offline rig instead of catching
     * anything. The gate exists for the submittable path, where a redeemer declaring less than it costs
     * fails phase-2 validation on chain: the collateral is forfeit and the failure arrives as an opaque
     * budget message long after every other gate has gone green.
     */
    private void assertExUnitsAreReal(Transaction tx, List<Utxo> universe, List<PlutusScript> extra) {
        if (txEvaluator == null) {
            return;
        }
        List<EvaluationResult> measured = measureBudgets(tx, universe, extra);
        List<Redeemer> redeemers = tx.getWitnessSet() == null ? null : tx.getWitnessSet().getRedeemers();
        if (redeemers == null || redeemers.isEmpty()) {
            return;   // a script-free body has no budget to understate
        }
        for (Redeemer redeemer : redeemers) {
            String what = redeemer.getTag() + "#" + redeemer.getIndex();
            BigInteger declaredMem = redeemer.getExUnits().getMem();
            BigInteger declaredSteps = redeemer.getExUnits().getSteps();

            if (declaredMem.longValueExact() <= PLACEHOLDER_EX_UNIT
                    && declaredSteps.longValueExact() <= PLACEHOLDER_EX_UNIT) {
                throw new GateFailure(EX_UNITS_GATE + ": redeemer " + what + " still carries the "
                        + "placeholder budget mem " + declaredMem + " / steps " + declaredSteps
                        + " — the evaluator did not write a real one");
            }

            EvaluationResult result = measured.stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag()
                            && r.getIndex() == redeemer.getIndex().intValueExact())
                    .findFirst()
                    .orElseThrow(() -> new GateFailure(EX_UNITS_GATE + ": the UPLC machine reported no "
                            + "budget for redeemer " + what + ", so its declared one cannot be checked"));
            BigInteger neededMem = result.getExUnits().getMem();
            BigInteger neededSteps = result.getExUnits().getSteps();
            if (declaredMem.compareTo(neededMem) < 0 || declaredSteps.compareTo(neededSteps) < 0) {
                throw new GateFailure(EX_UNITS_GATE + ": redeemer " + what + " declares mem "
                        + declaredMem + " / steps " + declaredSteps + " but really costs mem "
                        + neededMem + " / steps " + neededSteps + " — it would fail phase-2 validation");
            }
        }
    }

    /**
     * The ex-units gate's oracle: a second, independent UPLC evaluation of the finished body.
     *
     * <h2>Why it is not {@link EvalFixtures#evaluateRaw}, which the dry-eval gate just ran</h2>
     * {@link EvalFixtures} evaluates under its own {@code ProtocolParams}, whose PlutusV3 cost model is
     * the one <em>bundled with cardano-client-lib</em>. A submittable build's budgets are written by an
     * evaluator running under the <em>chain's live</em> cost model, and the two are not identical:
     * measured on preview 2026-08-18, the same create body costs {@code steps 70,583,881} under the
     * bundled model and {@code steps 70,579,642} under preview's — a 0.006% skew, enough to make a
     * correct transaction look 4,239 steps short. The ledger charges under the chain's model, so that is
     * the only model this gate may judge against; it therefore measures under {@code
     * protocolParamsSupplier}, the very parameters the build was balanced with. Cold, that supplier
     * carries the same bundled model as {@link EvalFixtures}, so the offline numbers are unchanged.
     *
     * <h2>It is still independent of the wired evaluator</h2>
     * This is a freshly constructed evaluator, not {@link #txEvaluator}. That is what gives the gate its
     * teeth: an evaluator that reports an understated budget — the exact defect this gate exists for —
     * writes that understatement into the redeemers, and this oracle contradicts it.
     */
    private List<EvaluationResult> measureBudgets(Transaction tx, List<Utxo> universe,
                                                  List<PlutusScript> extra) {
        AikenTransactionEvaluator oracle = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), protocolParamsSupplier,
                EvalFixtures.scriptSupplier(registry, extra), SlotConfigs.preview());
        try {
            Result<List<EvaluationResult>> result =
                    oracle.evaluateTx(tx.serialize(), new LinkedHashSet<>(universe));
            if (!result.isSuccessful()) {
                throw new GateFailure(EX_UNITS_GATE + ": the budget oracle could not measure the "
                        + "finished body: " + result.getResponse());
            }
            return result.getValue();
        } catch (GateFailure e) {
            throw e;
        } catch (Exception e) {
            throw new GateFailure(EX_UNITS_GATE + ": the budget oracle could not measure the finished "
                    + "body: " + e);
        }
    }

    /** The gate's name, as it appears in every refusal it throws. */
    private static final String LEDGER_PREFLIGHT_GATE = "LEDGER_PREFLIGHT";

    /**
     * The ledger-preflight gate: no script may reach the ledger twice. A script that sits in the witness
     * set <em>and</em> is published as a reference script on an input or reference input of the same
     * transaction is refused by Conway as {@code ExtraneousScriptWitnessesUTXOW} — phase 1, so the
     * transaction never runs and every other gate here has already gone green by then. Measured on
     * preview 2026-08-18: the create transaction carried {@code pool.pool} both ways and the node
     * answered {@code ExtraneousScriptWitnessesUTXOW (NonEmptySet (fromList [ScriptHash
     * "65a0bc5e6e5152fbe2bf3e1053f4020f6c7ee0a563beb0fe070a7b93"]))}.
     * <p>
     * <b>It asserts the artefact; it computes nothing.</b> The referenced hashes are read off the very
     * UTxOs the dry-evaluator resolved this body against, and the witnessed hashes off the finished
     * witness set.
     * <p>
     * <b>And it refuses when it cannot see them.</b> Those referenced hashes come from
     * {@code Utxo.getReferenceScriptHash()}, which a fixture — or a resolver handed the wrong
     * coordinates — may simply leave null. The set is then empty, no witnessed script can ever be found
     * in it, and the gate returns green on anything, including a body carrying its own policy both ways.
     * So a body that reads reference inputs while its universe publishes no reference-script hash at all
     * is refused outright: the difference between "no duplicate" and "no visibility" is the whole value
     * of this gate, and only one of them is an answer.
     * <p>
     * Fee adequacy — the other half of that same rejection — is deliberately not checked
     * here: cardano-client-lib owns the fee, and the fix for the fee was to give it the reference-script
     * bytes it needs ({@link PoolCreateTransactionBuilder}), not to recompute its answer.
     * <p>
     * <b>Silent without an evaluator</b>, on the {@link #EX_UNITS_GATE} convention: a no-evaluator
     * {@link LoanFactory} is explicitly build-only and none of its output is ever submitted.
     * <p>
     * <b>Do not restate that silence as "the witness copy is still there without an evaluator".</b> It
     * was true once and is now false: the reference-script wiring in {@link PoolCreateTransactionBuilder}
     * is <em>unconditional</em>, so the no-evaluator create carries an <em>empty</em> witness set too and
     * the pin is taken over that corrected shape. The silence rests on build-only and never-submitted,
     * nothing else.
     * <p>
     * <b>Known limit — partial blindness.</b> The precondition below fires only when <em>no</em>
     * reference-script hash is resolvable at all. If the universe publishes some hashes but the one UTxO
     * carrying the duplicated script has a null or wrong {@code referenceScriptHash}, a genuine duplicate
     * passes unseen — the gate cannot tell "legitimately witnessed, never published" from "published but
     * the metadata did not say so". Inherent to asserting the artefact rather than recomputing it; on
     * real chain data the hash is populated, which is what keeps the severity low.
     */
    private void assertNoDuplicateScriptWitnesses(Transaction tx, List<Utxo> universe) {
        if (txEvaluator == null) {
            return;
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Utxo utxo : universe) {
            if (utxo.getReferenceScriptHash() != null) {
                referenced.add(utxo.getReferenceScriptHash().toLowerCase());
            }
        }

        // The gate's precondition: it can only see duplicates if it can see the reference scripts. A
        // body that reads reference inputs but whose resolved universe publishes no reference-script
        // hash at all has starved this gate of its input — every witnessed script would then look
        // un-referenced and the gate would wave through the very shape it exists to refuse. A gate that
        // cannot see its input must say so rather than return green.
        List<TransactionInput> referenceInputs = tx.getBody().getReferenceInputs();
        if (referenceInputs != null && !referenceInputs.isEmpty() && referenced.isEmpty()) {
            throw new GateFailure(LEDGER_PREFLIGHT_GATE + ": this transaction reads "
                    + referenceInputs.size() + " reference input(s) but not one UTxO in the resolved "
                    + "universe publishes a reference-script hash — the gate cannot see its input, so it "
                    + "cannot tell a duplicated script from a clean one and refuses rather than pass");
        }

        for (String witnessed : witnessScriptHashes(tx)) {
            if (referenced.contains(witnessed)) {
                throw new GateFailure(LEDGER_PREFLIGHT_GATE + ": script " + witnessed + " travels both in "
                        + "the witness set and as a reference script this transaction resolves — Conway "
                        + "refuses that as ExtraneousScriptWitnessesUTXOW; exactly one route is permitted");
            }
        }
    }

    /** Every script hash the finished witness set carries, in any language. */
    private static Set<String> witnessScriptHashes(Transaction tx) {
        Set<String> hashes = new LinkedHashSet<>();
        if (tx.getWitnessSet() == null) {
            return hashes;
        }
        List<List<? extends Script>> scriptLists = new ArrayList<>();
        scriptLists.add(tx.getWitnessSet().getPlutusV1Scripts());
        scriptLists.add(tx.getWitnessSet().getPlutusV2Scripts());
        scriptLists.add(tx.getWitnessSet().getPlutusV3Scripts());
        scriptLists.add(tx.getWitnessSet().getNativeScripts());
        for (List<? extends Script> scripts : scriptLists) {
            if (scripts == null) {
                continue;
            }
            for (Script script : scripts) {
                try {
                    hashes.add(HexUtil.encodeHexString(script.getScriptHash()).toLowerCase());
                } catch (Exception e) {
                    throw new GateFailure(LEDGER_PREFLIGHT_GATE + ": a witness-set script cannot be "
                            + "hashed, so it cannot be checked against the reference scripts: " + e);
                }
            }
        }
        return hashes;
    }

    /**
     * The fee-100 gate, read off the finished body: the emitted lender-bond output's inline datum,
     * decoded with the production {@link LenderManagerDatumConverter}, must carry
     * {@code liquidationFeePerMille == 100}. A pool built at any other fee (fee 0 in F-a) mints a bond
     * carrying that fee, so this refuses it.
     */
    private void assertEmittedBondFeeIs100(Transaction tx, String bondAssetName) {
        TransactionOutput bondOutput = findOutput(tx, lenderBondAddress().getAddress(),
                registry.getLenderBondPolicyId(), bondAssetName, "the emitted lender bond");
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
        if (bondDatum.liquidationFeePerMille().intValueExact() != 100) {
            throw new GateFailure("FEE_GATE: the emitted lender bond decodes to fee "
                    + bondDatum.liquidationFeePerMille() + " per mille, not 100");
        }
    }

    private void assertBornLiquidatable(Recipe recipe) {
        try {
            PoolFixtures.assertBornLiquidatable(recipe.params(),
                    recipe.collateralPriceNumerator(), recipe.collateralPriceDenominator());
        } catch (AssertionError e) {
            throw new GateFailure("BORN_LIQUIDATABLE_GATE: " + e.getMessage());
        }
    }

    /**
     * The recovery-destination gate: {@code pool_cancel_action} constrains no output (findings §14), so
     * this is the only backstop that the recovered value comes back to the lender. Refuses if any output
     * sits at the pool address (there must be no continuation) or at any address but the lender's.
     */
    private void assertRecoveryReturnsToLender(Recipe recipe, Transaction tx) {
        String lenderAddress = recipe.lender().getAddress();
        String poolAddress = poolAddress();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (poolAddress.equals(output.getAddress())) {
                throw new GateFailure("RECOVERY_DESTINATION_GATE: an output returns to the pool address "
                        + poolAddress + " — the pool is cancelled, not re-created");
            }
            if (!lenderAddress.equals(output.getAddress())) {
                throw new GateFailure("RECOVERY_DESTINATION_GATE: an output goes to " + output.getAddress()
                        + ", not the lender " + lenderAddress);
            }
        }
    }

    // ---- per-call builders over a supplier that resolves the pool continuation ---------------------

    private PoolBorrowTransactionBuilder borrowBuilder(Utxo poolUtxo) {
        return new PoolBorrowTransactionBuilder(registry, network, resolving(poolUtxo),
                protocolParamsSupplier, txEvaluator);
    }

    private PoolCancelTransactionBuilder cancelBuilder(Utxo poolUtxo) {
        return new PoolCancelTransactionBuilder(registry, network, resolving(poolUtxo),
                protocolParamsSupplier, txEvaluator);
    }

    /**
     * The construction supplier, wrapped so it also resolves {@code poolUtxo} — the pool continuation
     * this pipeline produced, which cannot be in the construction supplier because it does not exist
     * until create is built. {@code PoolCancelTransactionBuilder} resolves every input's address through
     * {@code getTxOutput} in its structural checks, so the cancel needs the pool UTxO resolvable there;
     * coin selection ({@code getPage}) is delegated unchanged, as the pool output sits at the pool
     * credential and is never coin-selected.
     */
    private UtxoSupplier resolving(Utxo poolUtxo) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return utxoSupplier.getPage(address, nrOfItems, page, order);
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                if (poolUtxo.getTxHash().equals(txHash) && poolUtxo.getOutputIndex() == outputIndex) {
                    return Optional.of(poolUtxo);
                }
                return utxoSupplier.getTxOutput(txHash, outputIndex);
            }
        };
    }

    // ---- derivations from the recipe --------------------------------------------------------------

    private String funderAddress(Recipe recipe) {
        return recipe.lender().getAddress();
    }

    private byte[] lenderKeyHash(Recipe recipe) {
        return recipe.lender().getPaymentCredentialHash()
                .orElseThrow(() -> new GateFailure("the lender address has no payment key hash"));
    }

    private String poolAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(registry.getPoolSpendScriptHash()), network).getAddress();
    }

    private Address lenderBondAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(registry.getLenderManagerSpendScriptHash()), network);
    }

    private String poolAssetName(Recipe recipe) {
        return PoolTxEncoder.poolAssetName(0,
                new TransactionInput(recipe.seedUtxo().getTxHash(), recipe.seedUtxo().getOutputIndex()));
    }

    /** {@code lenderAuth = CardanoSignature(lenderKeyHash)} — the pool datum's authorisation. */
    private AuthorizationMethod lenderAuth(Recipe recipe) {
        return new AuthorizationMethod.CardanoSignature(HexUtil.encodeHexString(lenderKeyHash(recipe)));
    }

    /** The lender-bond template datum, at the recipe's fee and with this pool's NFT name as its poolId. */
    private LenderManagerDatum bondDatum(Recipe recipe) {
        return LoanFixtures.bondDatum(
                BigInteger.valueOf(recipe.params().liquidationFeePerMille()),
                LoanFixtures.noStakeCredential(), AssetType.ada(), poolAssetName(recipe));
    }

    private PoolTxEncoder.PoolDatum poolDatum(Recipe recipe) {
        return PoolFixtures.poolDatum(recipe.params(), lenderAuth(recipe), lenderBondAddress(),
                PoolFixtures.bondInlineDatumHash(bondDatum(recipe)));
    }

    /**
     * Resolves the pool UTxO as output 0 of a pool-bearing transaction — the create builder's proven
     * pool-output-0 placement — reading its address, value and inline datum off the finished body.
     * Refuses if output 0 is not the pool output (wrong address or missing pool NFT).
     */
    private Utxo poolOutputZero(Recipe recipe, Transaction poolTx) {
        TransactionOutput output = poolTx.getBody().getOutputs().get(0);
        String poolAddress = poolAddress();
        if (!poolAddress.equals(output.getAddress())) {
            throw new GateFailure("output 0 is at " + output.getAddress()
                    + ", not the pool address " + poolAddress);
        }
        if (!quantityOf(output, registry.getPoolPolicyId(), poolAssetName(recipe)).equals(BigInteger.ONE)) {
            throw new GateFailure("output 0 does not hold exactly one pool NFT "
                    + registry.getPoolPolicyId() + poolAssetName(recipe));
        }
        Utxo utxo = new Utxo();
        utxo.setTxHash(TransactionUtil.getTxHash(poolTx));
        utxo.setOutputIndex(0);
        utxo.setAddress(output.getAddress());
        utxo.setAmount(amountsOf(output));
        utxo.setInlineDatum(output.getInlineDatum() == null ? null : output.getInlineDatum().serializeToHex());
        return utxo;
    }

    // ---- eval universe ----------------------------------------------------------------------------

    /**
     * The UTxO set the dry-evaluator resolves the finished transaction against.
     * <p>
     * Spend and collateral inputs are resolved through {@code supplier} (the construction supplier for
     * create, that supplier wrapped to also resolve the pool continuation for borrow and cancel), read
     * off the finished body so whatever coin selection actually consumed is present. Reference inputs
     * that name a published script are taken from {@code referenceScriptUtxos} — the phase's own
     * hash-bearing coordinates — because a reference-script UTxO and this rig's plain pool-policy
     * reference input share the pool-policy coordinate, and only the hash-bearing one lets the evaluator
     * resolve the reference script; any other reference input (the config) falls back to the supplier.
     * Refuses if anything cannot be resolved.
     */
    private List<Utxo> universeOf(Transaction tx, UtxoSupplier supplier, List<Utxo> referenceScriptUtxos) {
        Map<String, Utxo> refByCoordinate = new LinkedHashMap<>();
        for (Utxo refScript : referenceScriptUtxos) {
            refByCoordinate.put(refScript.getTxHash() + "#" + refScript.getOutputIndex(), refScript);
        }

        List<TransactionInput> spendInputs = new ArrayList<>(tx.getBody().getInputs());
        if (tx.getBody().getCollateral() != null) {
            spendInputs.addAll(tx.getBody().getCollateral());
        }

        List<Utxo> universe = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TransactionInput input : spendInputs) {
            String key = input.getTransactionId() + "#" + input.getIndex();
            if (!seen.add(key)) {
                continue;
            }
            universe.add(supplier.getTxOutput(input.getTransactionId(), input.getIndex())
                    .orElseThrow(() -> new GateFailure("cannot resolve input " + key + " for dry-eval")));
        }
        if (tx.getBody().getReferenceInputs() != null) {
            for (TransactionInput input : tx.getBody().getReferenceInputs()) {
                String key = input.getTransactionId() + "#" + input.getIndex();
                if (!seen.add(key)) {
                    continue;
                }
                Utxo resolved = refByCoordinate.get(key);
                if (resolved == null) {
                    resolved = supplier.getTxOutput(input.getTransactionId(), input.getIndex())
                            .orElseThrow(() -> new GateFailure(
                                    "cannot resolve reference input " + key + " for dry-eval"));
                }
                universe.add(resolved);
            }
        }
        return universe;
    }

    /**
     * The one validator the create transaction needs the dry-evaluator to resolve. The {@code pool.pool}
     * witness copy is stripped and the policy travels by reference input only, and a Blockfrost
     * {@link Utxo} publishes a reference script's hash but never its bytes — so without this the
     * evaluator would answer {@code RequiredRedeemersMismatch}.
     * <p>
     * <b>Load-bearing on every path, including the no-evaluator one — do not scope it to the evaluator.</b>
     * An earlier version of this comment called it "inert without an evaluator, where the witness copy is
     * still there". That stopped being true when the reference-script wiring in
     * {@link PoolCreateTransactionBuilder} became unconditional: the witness copy is now gone everywhere.
     * Returning an empty list when {@code txEvaluator == null} fails six tests with
     * {@code RequiredRedeemersMismatch {missing: [65a0bc5e…]}} — verified by mutation, so this is a
     * measured claim rather than a caution.
     * <p>
     * Reference-script resolution needs <b>two independent facts</b> and this supplies only one: the UTxO
     * must publish {@code referenceScriptHash} (<em>which</em> script is reachable) and the
     * {@code ScriptSupplier} must hold its <em>bytes</em>. Neither alone is sufficient.
     */
    private List<PlutusScript> createExtraScripts() {
        return List.of(registry.getPoolScript());
    }

    private List<PlutusScript> borrowExtraScripts() {
        return List.of(registry.getPoolScript(), registry.getPoolSpendScript(),
                registry.getPoolBorrowActionScript(), registry.getLoanScript(),
                registry.getLenderBondScript(), registry.getBorrowerBondScript());
    }

    private List<PlutusScript> cancelExtraScripts() {
        return List.of(registry.getPoolSpendScript(), registry.getPoolScript(),
                registry.getPoolCancelActionScript());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static TransactionOutput findOutput(Transaction tx, String address, String policyId,
                                                String assetNameHex, String what) {
        List<TransactionOutput> matches = tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress())
                        && quantityOf(o, policyId, assetNameHex).equals(BigInteger.ONE))
                .toList();
        if (matches.size() != 1) {
            throw new GateFailure(what + " matches " + matches.size() + " outputs, expected exactly one");
        }
        return matches.get(0);
    }

    private static List<Amount> amountsOf(TransactionOutput output) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(output.getValue().getCoin()));
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            for (Asset asset : multiAsset.getAssets()) {
                amounts.add(Amount.asset(multiAsset.getPolicyId() + strip(asset.getNameAsHex()),
                        asset.getValue()));
            }
        }
        return amounts;
    }

    private static BigInteger quantityOf(TransactionOutput output, String policyId, String assetNameHex) {
        return output.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> strip(a.getNameAsHex()).equalsIgnoreCase(assetNameHex))
                .map(Asset::getValue)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
