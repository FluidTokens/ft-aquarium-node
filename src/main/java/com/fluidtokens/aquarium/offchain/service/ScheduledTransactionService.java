package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptSafeUtxoSelection;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import java.util.Comparator;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.DatumTank;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumTankConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.ScheduledTransactionData;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import com.fluidtokens.aquarium.offchain.util.AssetAmountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.util.UtxoUtil.toUtxo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static java.math.BigInteger.ZERO;

/**
 * ⛔ <b>OFF BY DEFAULT.</b> {@code scheduling.transaction-processor.enabled} must be set to
 * {@code true} for this service to exist at all — there is no {@code matchIfMissing}, so an operator
 * who says nothing gets a node that indexes and serves but never builds or submits a transaction.
 *
 * <p>⚠ <b>This is a deliberate change of default for an operator-facing image</b>, and it is the
 * safe direction: the previous behaviour was that pulling the image and starting it began
 * <em>spending from the configured wallet</em> on the next scheduling tick. Arming is now something
 * an operator does on purpose. ⇒ An operator upgrading across this change and expecting the
 * processor to keep running <b>must set the flag</b>; nothing else in the node will complain,
 * because a quiet processor and a disabled one look identical from outside — which is exactly why
 * this logs its own absence at startup rather than staying silent.
 *
 * <p>The gate is on the BEAN, not on the scheduled method, so a disabled processor costs nothing and
 * cannot be re-armed by a stray call. Nothing injects this class — its many mentions elsewhere are
 * javadoc cross-references — so removing it from the context is safe.
 */
@Service
@ConditionalOnProperty(prefix = "scheduling.transaction-processor", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionService {

    record RefInputIndexes(BigInteger paramsIndex, BigInteger stakingIndex) {

    }

    private record DatumTankUtxo(DatumTank datumTank, Utxo utxo) {

    }

    private final AppConfig.Network network;

    private final AppConfig.AquariumConfiguration aquariumConfiguration;

    private final Account account;

    /**
     * cardano-client-lib 0.7.2's own collateral figure: {@code DEFAULT_COLLATERAL_AMT =
     * Amount.ada(5.0)}, hardcoded at {@code QuickTxBuilder:65}. It does not consult protocol
     * parameters, and {@code buildCollateralOutput} constructs its own selection strategy rather
     * than reading the one on the builder context — so this number, not the ledger's, is what a
     * wallet utxo must actually clear.
     */
    private static final java.math.BigInteger CCL_DEFAULT_COLLATERAL_LOVELACE =
            java.math.BigInteger.valueOf(5_000_000L);

    private final QuickTxBuilder quickTxBuilder;

    /**
     * ⛔ <b>A DIAGNOSTIC, NOT A DESIGN CHANGE — and it ships OFF.</b>
     *
     * <p>Left unset (the default) nothing changes: evaluation goes through the shared
     * {@code QuickTxBuilder(bfBackendService)} bean, i.e. Blockfrost's
     * {@code /utils/txs/evaluate}, exactly as before.
     *
     * <h2>What it is for</h2>
     * On mainnet 2026-09-22 every tank transaction failed with Blockfrost answering
     * {@code DeserialiseFailure 0 "expected tag"} — the provider could not DECODE the transaction,
     * so nothing reached phase 1, let alone a validator. Two hypotheses were eliminated by
     * measurement: cardano-client-lib 0.7.2 emits Conway set tags correctly (probed, body and
     * witness set), and the collateral ceiling — a real bug, fixed — did not change the symptom.
     *
     * <p>⚑ The commented-out reference this builder was derived from
     * ({@code MainnetTankTest.executePayment}) differs in exactly one line: it evaluated through
     * <b>Ogmios</b>. Pointing this at an Ogmios endpoint answers the open question — is the
     * TRANSACTION malformed, or is Blockfrost's evaluator simply the wrong tool for it — and that
     * answer decides which fix is correct.
     *
     * <h2>⚠ Why this must NOT become the shipped answer</h2>
     * This node's architecture is "connects straight to a relay — <b>no Kupo/Ogmios</b>", and the
     * README sells that as the reason an operator's costs stay low. Requiring every operator to run
     * Ogmios to process scheduled transactions would undo it. If Ogmios proves the transaction is
     * sound, the fix belongs elsewhere — most likely the offline Aiken evaluator this repo already
     * uses in its dry-eval rigs, which needs no external service at all.
     *
     * <p>⛔ {@code TransactionEvaluator} is a ONE-METHOD interface that cannot submit (CCL trap 8),
     * so nominating one here grants costing without granting submission. The evaluator changes; the
     * submit path does not.
     */
    @org.springframework.beans.factory.annotation.Value("${scheduling.transaction-processor.ogmios-url:}")
    private String ogmiosUrl;

    /**
     * ⛔ <b>DIAGNOSTIC. Builds the transaction, logs its CBOR, and SUBMITS NOTHING.</b>
     *
     * <p>The provider rejects these transactions while DECODING them, and its message — {@code
     * DeserialiseFailure 0 "expected tag"} — is not reliable about <em>what</em> it choked on. The
     * same class of failure has previously reported {@code "expected array or int, got TypeNInt"}
     * for a negative value. So the message is a symptom, and the bytes are the evidence.
     *
     * <p>⚠ <b>This flag also turns {@code ignoreScriptCostEvaluationError} ON</b>, which is the only
     * way to get past a failing evaluation and reach a serialisable transaction. That makes the
     * result carry PLACEHOLDER ex-units, and submitting one is CCL trap 8 — accepted by the mempool,
     * failed in phase 2, collateral forfeit. Hence {@code build()} and an immediate {@code continue}:
     * in this mode the code path that submits is not reachable.
     *
     * <p>⛔⛔ <b>THE DEFAULT IS {@code true} ON THIS BRANCH ONLY, AND IT MUST NOT REACH {@code main}.</b>
     * Giovanni runs this on Kubernetes, where adding an environment variable means editing the Helm
     * chart — so a flag that must be switched on to be useful would not have been switched on. His
     * instruction was to hack it here and roll it back once the cause is found.
     *
     * <p>⚠ <b>While this default stands, the processor SUBMITS NOTHING.</b> That is the intended
     * trade, not a side effect: every tank transaction is currently rejected at decode anyway, so
     * the cost is zero and the return is the bytes. <b>Restore {@code :false} in the commit that
     * fixes the decode failure</b> — {@code ScheduledTransactionDumpDefaultTest} fails until it is.
     */
    @org.springframework.beans.factory.annotation.Value("${scheduling.transaction-processor.dump-cbor:true}")
    private boolean dumpCbor;


    /**
     * Only used to build the reference-script-safe coin selection; see the guard at compose().
     * <p>
     * <b>Deliberately the backend and not the {@code UtxoSupplier} bean.</b> That bean is
     * {@code @ConditionalOnProperty(loans.enabled=true)}, and this service is the Aquarium tank
     * subsystem, which runs on mainnet where lending is disabled by default — injecting it here
     * would have failed startup on exactly the production deployment this repo ships. The backend
     * is unconditional, and this is the same construction {@code YaciConfig} performs.
     */
    private final BFBackendService bfBackendService;

    private final UtxoRepository utxoRepository;

    private final StakerService service;

    private final CardanoConverters cardanoConverters;

    private final ParametersService parametersService;

    private final TankContractService tankContractService;

    private final AppUtxoService appUtxoService;

    private final BlockEventListener blockEventListener;

    private final Vector<TransactionInput> unprocessableScheduledTransactions = new Vector<>();

    private final DatumTankConverter datumConverter = new DatumTankConverter();

    private RefInputIndexes resolveRefIndexes(TransactionInput parametersRefInput, TransactionInput stakingRefInput) {
        var sortedRefInputs = Stream.of(parametersRefInput, stakingRefInput, aquariumConfiguration.getTankRefInput())
                .sorted(new TransactionInputComparator())
                .toList();
        var parametersRefInputIndex = sortedRefInputs.indexOf(parametersRefInput);
        var stakingRefInputIndex = sortedRefInputs.indexOf(stakingRefInput);
        return new RefInputIndexes(BigInteger.valueOf(parametersRefInputIndex), BigInteger.valueOf(stakingRefInputIndex));
    }


    /** The supplier the selection guard reads through; see the field javadoc for why it is built here. */
    private UtxoSupplier referenceScriptSafeSupplier() {
        return new DefaultUtxoSupplier(bfBackendService.getUtxoService());
    }

    @Scheduled(timeUnit = TimeUnit.MINUTES, fixedDelayString = "${scheduling.transaction-processor.delay-minutes}")
    public void processPayments() {

        log.info("Starting Process Payments RUN");

        if (blockEventListener.getIsSyncing().get()) {
            log.info("node is syncing, skipping...");
            return;
        }

        var stakerRefInputOpt = service.findStakerRefInput();
        if (stakerRefInputOpt.isEmpty()) {
            log.info("Cannot find stake for {}", account.baseAddress());
            return;
        }

        final var stakerRefInput = stakerRefInputOpt.getFirst();

        final var parametersRefInput = parametersService.loadParametersRefInput();
        final var parameters = parametersService.loadParameters();

        var tankUtxos = utxoRepository
                .findUnspentByOwnerPaymentCredential(tankContractService.getScriptHashHex(), Pageable.unpaged())
                .stream()
                .flatMap(List::stream)
                .toList();


        final var tankContractRefInput = aquariumConfiguration.getTankRefInput();

        var refInputIndexes = resolveRefIndexes(parametersRefInput, stakerRefInput);

        var scheduledTank = tankUtxos
                .stream()
                .filter(filterUnprocessableScheduledTransactions(unprocessableScheduledTransactions))
                .flatMap(getAddressUtxoEntityStreamFunction())
                .filter(isScheduledTankTransaction())
                .toList();

        var processableScheduledTransactions = scheduledTank.stream()
                .filter(isScheduledTxTimeValid())
                .toList();

        log.info("Found {} Tank Utxos of which {} Scheduled Transactions and {} Processable Scheduled Transactions",
                tankUtxos.size(),
                scheduledTank.size(),
                processableScheduledTransactions.size());

        // ⛔ ONE WALLET READ AND ONE PROTOCOL-PARAMS READ PER CYCLE, NOT PER TANK.
        //
        // Both of these are PROVIDER CALLS and both were inside the loop below. With 535 processable
        // tanks that is 1,070 Blockfrost calls per cycle, every five minutes — and AppUtxoService's
        // own javadoc promises "one provider call per cycle", which had quietly stopped being true.
        //
        // ⚠ AND THE PER-TANK READ WAS LOAD-BEARING BEFORE THIS CHANGE, which is why hoisting it
        // alone would have BROKEN the bot rather than sped it up. Selection took the SMALLEST
        // qualifying utxo, so every tank in a cycle chose the SAME one; the loop only worked because
        // completeAndWait() waited for confirmation and the next iteration re-read a wallet that now
        // held the change. The read WAS the chaining.
        //
        // ⇒ So the read is hoisted AND the coupling is removed together: each tank is assigned its
        // OWN wallet utxo from a pool built once. The transactions then share no input and cannot
        // conflict, which is why no chaining is needed — not because the waiting was unnecessary,
        // but because the dependency it existed to satisfy is gone.
        List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
        if (walletUtxos.isEmpty()) {
            log.warn("No wallet UTXOs found for account: {}", account.baseAddress());
            return;
        }

        var protocolParams = new DefaultProtocolParamsSupplier(bfBackendService.getEpochService())
                .getProtocolParams();

        // ⛔ THE COLLATERAL CEILING, NOT THE FEE CEILING — and the difference is a transaction NO NODE
        // CAN PARSE, not a transaction that fails.
        //
        // This asked for maxPossibleFee. The ledger requires collateral of fee × collateral_percent
        // (150%), so a utxo between the two passed this filter, was nominated as both the input and
        // the collateral, and cardano-client-lib emitted a NEGATIVE collateral return. A negative
        // MaryValue is unrepresentable, so the provider rejects the CBOR at offset 0 before any
        // validation runs — "DeserialiseFailure ... expected tag" — and nothing reaches the chain.
        //
        // ⚠ MEASURED ON MAINNET 2026-09-22: floor 2,549,327 against a collateral requirement of
        // ~3,823,991. Every wallet utxo in that 1.27 ada window failed, every cycle.
        //
        // ⚑ THIS IS THE 2026-08-25 INCIDENT ON A SECOND PATH. LiquidateTransactionBuilder carries
        // INSUFFICIENT_COLLATERAL and sizes from maxPossibleCollateral; LiquidationExecutor does the
        // same. This service shares their wallet, their library and their failure mode, and had
        // neither -- the "every guarantee lives on the preview paths, this one had none" shape, twice
        // noted in this file and now the cause of a third outage.
        var required = requiredWalletLovelace(protocolParams);

        // ⚠ SMALLEST-FIRST, still: largest-first would spend the biggest utxo to pay a fee and
        // fragment the wallet against the case where a large one is genuinely needed. Ordering the
        // POOL this way means the cheapest suitable utxos are consumed first, tank by tank.
        var walletPool = walletPool(walletUtxos, required);

        if (walletPool.isEmpty()) {
            var largest = walletUtxos.stream()
                    .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                    .map(LedgerCeilings::lovelaceOf)
                    .max(java.math.BigInteger::compareTo);
            log.warn("no ada-only wallet utxo covers the {} lovelace of COLLATERAL this ledger could demand; "
                            + "largest available is {}. Fund the wallet with a single ada-only "
                            + "utxo of at least that amount.",
                    required, largest.map(Object::toString).orElse("none at all"));
            return;
        }

        // ⚠ How many tanks this cycle can do is now bounded by the wallet's SHAPE, not its balance:
        // one ada-only utxo per tank. Said once, here, because an operator watching a backlog drain
        // slowly needs to know the lever is "split the wallet into more utxos", not "add more ada".
        if (walletPool.size() < processableScheduledTransactions.size()) {
            log.info("{} processable tanks but only {} usable wallet utxos — processing {} this "
                            + "cycle and the rest next. Each tank needs its own ada-only utxo of at "
                            + "least {} lovelace; split the wallet to raise this ceiling.",
                    processableScheduledTransactions.size(), walletPool.size(), walletPool.size(),
                    required);
        }

        for (var datumTankUtxo : processableScheduledTransactions) {

            if (walletPool.isEmpty()) {
                break;
            }
            var tankPaymentUtxo = datumTankUtxo.utxo();
            var tankDatum = datumTankUtxo.datumTank();

            try {

                // ⛔ ONE UTXO PER TANK, TAKEN FROM THE POOL — the reason these transactions need no
                // chaining. Each carries a different wallet input, so none conflicts with another
                // and none has to wait for the previous one's change to exist and be indexed.
                //
                // The selection RULE is unchanged and its reasoning still holds (T-053): ada-only,
                // no reference script, and PROVABLY covering what the ledger could charge, smallest
                // first. What changed is only that the pool is built once and drained here, instead
                // of every tank re-reading the wallet and picking the same utxo.
                var walletUtxo = walletPool.poll();

                var batcher = AddressUtil.toOnchainAddress(account.getBaseAddress());

                var scheduledTransactionData = new ScheduledTransactionData();
                scheduledTransactionData.setInputtankindex(ZERO);
                scheduledTransactionData.setBatcher(batcher);
                scheduledTransactionData.setReferenceParamsIndex(refInputIndexes.paramsIndex());
                scheduledTransactionData.setReferenceStakingIndex(refInputIndexes.stakingIndex());
                scheduledTransactionData.setWhitelistIndex(ZERO);

                var redeemer = scheduledTransactionData.toPlutusData();

                var amountToSend = AssetAmountUtil.toValue(List.of(tankDatum.getScheduledamount()));

                var payeeAddress = AddressUtil.toAddress(tankDatum.getDestionationaaddress(), network.getCardanoNetwork());

                var now = LocalDateTime.now(ZoneOffset.UTC);
                var slot = cardanoConverters.time().toSlot(now);

                var reward = AssetAmountUtil.toValue(List.of(tankDatum.getReward()));

                var rewardsAddress = AddressUtil.toAddress(parameters.getAddressRewards(), network.getCardanoNetwork());

                var tx = new ScriptTx()
                        .collectFrom(walletUtxo)
                        .collectFrom(tankPaymentUtxo, redeemer)
                        .payToAddress(payeeAddress.getAddress(), ValueUtil.toAmountList(amountToSend))
                        .payToAddress(rewardsAddress.getAddress(), ValueUtil.toAmountList(reward))
                        .withChangeAddress(account.baseAddress())
                        .readFrom(parametersRefInput)
                        .readFrom(stakerRefInput)
                        .readFrom(tankContractRefInput);

                var composed = quickTxBuilder.compose(tx);
                if (ogmiosUrl != null && !ogmiosUrl.isBlank()) {
                    // ⚠ Named in the log the first time it is used, because a node evaluating
                    // somewhere other than its configured backend must not do so silently.
                    log.info("evaluating script cost through OGMIOS at {} instead of the backend "
                            + "(scheduling.transaction-processor.ogmios-url is set)", ogmiosUrl);
                    // ⚠ It lives in cardano-client-supplier-ogmios-supplier, NOT in
                    // cardano-client-backend-ogmios — that artefact has only a BackendService, whose
                    // TransactionService is not a TransactionEvaluator in 0.7.2. The supplier module
                    // arrives transitively through the backend one, so no new dependency is declared.
                    composed = composed.withTxEvaluator(
                            new com.bloxbean.cardano.client.supplier.ogmios.OgmiosTransactionEvaluator(ogmiosUrl));
                }

                // ⛔ DUMP THE BYTES THE EVALUATOR IS ACTUALLY HANDED, NOT THE ONES build() RETURNS.
                //
                // The failure is Blockfrost's /utils/txs/evaluate refusing to DECODE the request.
                // Dumping build()'s output would give a transaction that has since been through fee
                // calculation and balancing -- close to, but NOT the same as, the bytes that were
                // rejected. For a decode failure at offset 0, "close" is useless: the whole question
                // is which byte is wrong.
                //
                // ⚠ This wraps the SAME backend evaluator the bean already uses, so it changes
                // nothing about where evaluation happens -- it only copies the payload to the log on
                // the way past, and returns the backend's own answer untouched.
                if (dumpCbor) {
                    var dumpTank = tankPaymentUtxo;
                    composed = composed.withTxEvaluator((cbor, inputUtxos) -> {
                        log.warn("EVALUATE CBOR for tank {}:{} — {} bytes, {} input utxos. These are "
                                        + "the exact bytes posted to the evaluator.\n{}",
                                dumpTank.getTxHash(), dumpTank.getOutputIndex(), cbor.length,
                                inputUtxos == null ? 0 : inputUtxos.size(),
                                com.bloxbean.cardano.client.util.HexUtil.encodeHexString(cbor));
                        return bfBackendService.getTransactionService().evaluateTx(cbor);
                    });
                }

                var context = composed
                        // ⛔ NEVER SPEND A UTxO CARRYING A REFERENCE SCRIPT.
                        //
                        // This service reaches coin selection through the SHARED QuickTxBuilder bean
                        // (YaciConfig), so there is no tx.from(...) here to grep for — the hazard
                        // arrives by injection and is invisible to a search for the dangerous call.
                        // That is how it was missed: the two liquidation builders construct their own
                        // builders and were guarded, and this third site was not.
                        //
                        // It spends from the same wallet as the liquidation bot. Nothing with a
                        // scriptRef is in that wallet today, but a guard whose absence depends on a
                        // wallet staying empty of a particular UTxO shape is not a guard -- and this
                        // repo published a reference script to its own address on 2026-08-17 and had
                        // it consumed by an unguarded builder on 2026-08-25.
                        .withUtxoSelectionStrategy(
                                ReferenceScriptSafeUtxoSelection.strategy(referenceScriptSafeSupplier()))
                        .preBalanceTx((ctx, txn) -> ctx.setUtxoSelector(
                                ReferenceScriptSafeUtxoSelection.selector(referenceScriptSafeSupplier())))
                        .withSigner(SignerProviders.signerFrom(account))
                        .withSigner(SignerProviders.stakeKeySignerFrom(account))
                        .withRequiredSigners(account.getBaseAddress().getDelegationCredentialHash().get())
                        .validFrom(slot - 30)
                        .validTo(slot + 180)
                        .feePayer(account.baseAddress())
                        // ⛔ COLLATERAL IS NOT PINNED, AND THE REASON IS NARROWER THAN IT WAS.
                        //
                        // An earlier commit pinned withCollateralInputs to the utxo this transaction
                        // SPENDS. That is wrong for a reason ReferenceScriptSafeUtxoSelection had
                        // already written down: a pinned collateral input is excluded from ordinary
                        // coin selection, so it cannot also front the principal.
                        //
                        // ⚠ But read in CCL 0.7.2's own source (QuickTxBuilder:499-514), the
                        // unpinned path has a real defect of its own:
                        //
                        //     utxoSelectionStrategy.select(payingAddress, DEFAULT_COLLATERAL_AMT, null)
                        //                                                                        ^^^^
                        //                                                            utxosToExclude
                        //
                        // Nothing is excluded -- so CCL's collateral selector scans the SAME address
                        // and may nominate as collateral a utxo the transaction is already spending
                        // as an input. Pinning a DIFFERENT wallet utxo is the only way to stop that.
                        // Not done here yet: it costs a second utxo per tank, and it should not be
                        // bought on a theory before the CBOR says this is what is happening.
                        .collateralPayer(account.baseAddress())
                        .mergeOutputs(false)
                        .ignoreScriptCostEvaluationError(dumpCbor)
                        // T-059 — THE ONLY MAINNET PATH NOW ASSERTS ITS OWN STRUCTURE.
                        //
                        // Every guarantee the lending-v4 review added lives on the PREVIEW paths;
                        // this one had none. And `withVerifier` is genuinely reached here because the
                        // tank submits through completeAndWait() — the one place in that whole arc
                        // where the obviously-named API is the right one, after three that were not.
                        //
                        // ⚠ It COMPOSES (QuickTxBuilder:863-868 uses andThen), unlike preBalanceTx
                        // above, which is a SETTER whose second call silently discards the first.
                        // Two hooks on one builder with opposite semantics.
                        .withVerifier(TankStructureVerifier.of(
                                tankPaymentUtxo,
                                parametersRefInput, stakerRefInput,
                                refInputIndexes.paramsIndex(), refInputIndexes.stakingIndex(),
                                ZERO,
                                payeeAddress.getAddress(), amountToSend.getCoin(),
                                rewardsAddress.getAddress(), reward.getCoin()))
                        ;

                // ⛔ DUMP MODE BUILDS AND STOPS. IT CANNOT SUBMIT, and that is the whole safety
                // property: ignoreScriptCostEvaluationError is TRUE here, so the build survives a
                // failed evaluation by shipping PLACEHOLDER ex-units (10000 mem / 1000 steps).
                // Submitting that is CCL trap 8 -- accepted by the mempool, failed in PHASE 2,
                // collateral forfeit. So this branch calls build(), never completeAndWait(), and
                // returns before anything can be signed or sent.
                if (dumpCbor) {
                    var built = context.build();
                    log.warn("CBOR DUMP for tank {}:{} — {} bytes. ⚠ NOT SUBMITTED: this build "
                                    + "carries PLACEHOLDER ex-units and must never be sent. Decode it "
                                    + "to find what the evaluator could not read.\n{}",
                            tankPaymentUtxo.getTxHash(), tankPaymentUtxo.getOutputIndex(),
                            built.serialize().length,
                            com.bloxbean.cardano.client.util.HexUtil.encodeHexString(built.serialize()));
                    continue;
                }

                context.completeAndWait();

            } catch (Exception e) {
                unprocessableScheduledTransactions.add(TransactionInput.builder()
                        .transactionId(tankPaymentUtxo.getTxHash())
                        .index(tankPaymentUtxo.getOutputIndex())
                        .build());
                log.warn("Could not process Tank utxo: {}:{}", tankPaymentUtxo.getTxHash(), tankPaymentUtxo.getOutputIndex());
                log.warn("Error", e);
            }

        }
    }

    /**
     * ⛔ <b>The cycle's wallet inputs — one per tank, which is what removes the need to chain.</b>
     *
     * <p>Each tank transaction spends its own ada-only utxo, so no two share an input and none has
     * to wait for the previous one's change to exist and be indexed. Before this existed, selection
     * took the SMALLEST qualifying utxo on every iteration — the same one each time — and the loop
     * only worked because {@code completeAndWait()} waited and the next iteration re-read a wallet
     * that now held the change. The per-tank read WAS the chain.
     *
     * <p>The selection RULE is unchanged and its reasoning stands (T-053):
     * <ul>
     *   <li><b>ada-only</b> — a utxo carrying tokens drags them into a transaction that did not ask
     *       for them;</li>
     *   <li><b>no reference script</b> — spending one destroys it permanently, and this service
     *       spends from the same wallet the liquidation bot publishes into;</li>
     *   <li><b>provably covers</b> what the ledger could charge, derived from protocol parameters
     *       rather than assumed;</li>
     *   <li><b>smallest first</b> — largest-first would spend the biggest utxo to pay a fee and
     *       fragment the wallet against the case where a large one is genuinely needed.</li>
     * </ul>
     *
     * <p>⚠ The pool's SIZE is the cycle's ceiling, and it is a property of the wallet's SHAPE rather
     * than its balance: ten tanks need ten utxos, not ten times the ada. Package-private so that
     * distinctness can be tested without a Spring context, a provider or a signer — the whole reason
     * the previous coupling went unnoticed is that nothing could exercise it.
     */
    /**
     * ⛔ <b>HOW BIG A WALLET UTXO HAS TO BE — TWO CEILINGS, AND THE BINDING ONE IS THE LIBRARY'S.</b>
     *
     * <p>{@link LedgerCeilings#maxPossibleCollateral} is the <i>ledger's</i> figure — fee ×
     * {@code collateral_percent}, ~3.82 ada on mainnet. cardano-client-lib does not use it:
     * {@code QuickTxBuilder:65} hardcodes {@code DEFAULT_COLLATERAL_AMT = Amount.ada(5.0)} and
     * {@code buildCollateralOutput} builds its <b>own</b> {@code DefaultUtxoSelectionStrategyImpl}
     * to find that much — a strategy {@code withUtxoSelectionStrategy} cannot reach. Asking for the
     * max of the two means the utxo we put in is never smaller than what the library may ask of it.
     *
     * <p>⚠ <b>THIS IS A CHEAP INVARIANT, NOT A DIAGNOSIS — and an earlier version of this javadoc
     * claimed otherwise.</b> It asserted that a sub-5-ada utxo makes CCL emit a negative collateral
     * return, which is what produced the undecodable mainnet CBOR. <b>Reading CCL 0.7.2's sources
     * refutes that:</b>
     * <ul>
     *   <li>{@code DefaultUtxoSelectionStrategyImpl.select} <b>accumulates across several utxos</b>
     *       to reach its target and <b>throws</b> {@code InsufficientBalanceException} if it cannot.
     *       It never returns a short set — so it cannot under-fund the return this way. A per-utxo
     *       floor is not what the library requires; it requires 5 ada <i>at the address</i>, and the
     *       wallet holds ~140.</li>
     *   <li>{@code CollateralBuilders.balanceCollateralOutputs} computes
     *       {@code remainingCoin = coin - totalCollateral} with <b>no non-negativity check</b> — a
     *       genuine landmine, but one the throw above keeps out of reach.</li>
     * </ul>
     *
     * <p>⇒ So the cause of {@code DeserialiseFailure 0 "expected tag"} is <b>still open</b>, and the
     * CBOR dump on this branch is how it gets settled. Keeping this floor is defensible on its own
     * terms — it removes one variable for about one wallet utxo of throughput — but it must not be
     * described as the fix until the bytes say so.
     *
     * <p>⚠ Deliberately <b>not</b> fixed by {@code withCollateralInputs}: as
     * {@code ReferenceScriptSafeUtxoSelection} records, a pinned collateral input is excluded from
     * ordinary coin selection, so it cannot also be the utxo fronting the principal. Size is the
     * only lever that works here.
     */
    static java.math.BigInteger requiredWalletLovelace(
            com.bloxbean.cardano.client.api.model.ProtocolParams protocolParams) {
        return LedgerCeilings.maxPossibleCollateral(protocolParams).max(CCL_DEFAULT_COLLATERAL_LOVELACE);
    }

    static java.util.Deque<Utxo> walletPool(List<Utxo> walletUtxos, java.math.BigInteger required) {
        return new java.util.ArrayDeque<>(walletUtxos.stream()
                .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                .filter(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(required) >= 0)
                .sorted(Comparator.comparing(LedgerCeilings::lovelaceOf))
                .toList());
    }

    /**
     * transform a AddressUtxoEntity into an "optional" DatumTankUtxo stream.
     * @return an utxo object and the Tank Datum if it can be deserialized, otherwise an empty stream.
     */
    private Function<AddressUtxoEntity, Stream<DatumTankUtxo>> getAddressUtxoEntityStreamFunction() {
        return addressUtxoEntity -> {
            try {
                var inlineDatum = addressUtxoEntity.getInlineDatum();
                var tankDatum = datumConverter.deserialize(inlineDatum);
                return Stream.of(new DatumTankUtxo(tankDatum, toUtxo(addressUtxoEntity)));
            } catch (Exception e) {
                log.warn("could not deserialise datum for: {}", addressUtxoEntity);
                return Stream.empty();
            }
        };
    }

    /**
     * Checks whether the current Scheduled Tx schedule time has been reached.
     *
     * @return
     */
    private static Predicate<DatumTankUtxo> isScheduledTxTimeValid() {
        return datumTankUtxo -> {
            var tankDatum = datumTankUtxo.datumTank();
            var timestamp = tankDatum.getExecutiontime();
            var startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp.longValue()), ZoneOffset.UTC);
            return LocalDateTime.now(ZoneOffset.UTC).isAfter(startTime);
        };
    }

    /**
     * Checks whether the current Scheduled Tx schedule time has been reached.
     *
     * @return
     */
    private static Predicate<AddressUtxoEntity> filterUnprocessableScheduledTransactions(Vector<TransactionInput> unprocessableScheduledTransactions) {
        return addressUtxoEntity -> !unprocessableScheduledTransactions.contains(TransactionInput.builder()
                        .transactionId(addressUtxoEntity.getTxHash())
                        .index(addressUtxoEntity.getOutputIndex())
                .build());
    }

    /**
     * Not all tank utxos are Scheduled Tx. In order to be one, allowed tokens list must be empty and there must be
     * some operator rewards.
     *
     * @return
     */
    private static Predicate<DatumTankUtxo> isScheduledTankTransaction() {
        return datumTankUtxo -> {
            var datumTank = datumTankUtxo.datumTank();
            var reward = AssetAmountUtil.toValue(List.of(datumTank.getReward()));
            return datumTank.getAllowedtokens().isEmpty() && !reward.isZero() && reward.isPositive();
        };
    }



}
