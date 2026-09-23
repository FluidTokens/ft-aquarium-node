package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
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
     * <p>⚑ This is what found the 2026-09-22 outage: the dumped bytes showed a 29-byte address
     * under a 57-byte header in output[0]. Off by default again now that the cause is fixed.
     */
    @org.springframework.beans.factory.annotation.Value("${scheduling.transaction-processor.dump-cbor:false}")
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

    private static final String LOVELACE = "lovelace";

    /** @see #processPayments() — held for the duration of a cycle so ticks cannot overlap. */
    private final java.util.concurrent.atomic.AtomicBoolean cycleInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /**
     * ⛔ <b>ONE CYCLE AT A TIME. A tick that arrives while the previous one is still working is
     * SKIPPED, not queued and not run alongside.</b>
     *
     * <p>A cycle now processes <b>every</b> eligible tank rather than a fixed few, so its duration is
     * set by the size of the backlog and can easily exceed the tick interval. Two cycles running
     * together would read the same tank set and build two transactions spending the same tank — one
     * wins, the other is rejected for an input that no longer exists, and the loser's tank gets
     * counted as a failure it never had.
     *
     * <p>⛔ <b>THIS GUARD IS NOW LOAD-BEARING, NOT BELT-AND-BRACES.</b> It was written while this
     * method ran on {@code fixedDelay}, which measures the gap between a run FINISHING and the next
     * STARTING and therefore cannot overlap. <b>A cron trigger has no such property</b>: it fires on
     * the clock, so a cycle still working when the next minute arrives is exactly the case cron
     * introduces — and the overlap would be invisible, showing up as tanks failing for inputs that
     * another thread had just spent.
     *
     * <p>⚑ It was added on the argument that "an invariant worth relying on is worth stating in the
     * code that relies on it", against the objection that {@code fixedDelay} made it redundant. One
     * commit later the schedule changed and the redundancy was gone. <b>That is the usual lifetime
     * of a guarantee nobody wrote down.</b>
     */
    @Scheduled(cron = "${scheduling.transaction-processor.cron}", zone = "UTC")
    public void processPayments() {
        if (!cycleInProgress.compareAndSet(false, true)) {
            log.info("previous Process Payments run is still going — SKIPPING this tick rather than "
                    + "running two cycles over the same tanks");
            return;
        }
        try {
            runPaymentCycle();
        } finally {
            // ⚠ finally, not at the end of the happy path: a cycle that throws must still release
            // the guard, or the processor is dead until restart and says nothing about why.
            cycleInProgress.set(false);
        }
    }

    private void runPaymentCycle() {

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

        var dueScheduledTransactions = scheduledTank.stream()
                .filter(isScheduledTxTimeValid())
                .toList();

        // ⛔ DECIDE WHAT IS HOPELESS BEFORE THE LOOP, NOT INSIDE IT.
        //
        // Everything needed to know a tank can never work is in its datum, which is already in hand:
        // an address that cannot be built, or payouts larger than the tank holds. Both are fixed
        // properties of bytes written on chain.
        //
        // ⚑ Discovering them INSIDE the loop is what throttled the bot to 1-2 tanks a minute. Each
        // one consumed a wallet utxo from the cycle's pool -- for an exception thrown while building
        // an address, before any transaction existed and with nothing spent -- so a cluster of broken
        // tanks could exhaust the cycle's whole budget at full speed and the bot looked stopped.
        // Measured on mainnet 2026-09-22: 21 tanks with an unbuildable address (14 with an empty
        // payment credential, 9 with an empty stake credential) and 42 that cannot cover their own
        // payouts, against 551 sound ones.
        // ⚠ Fetched BEFORE classification, because the min-UTxO floor is derived from
        // coinsPerUtxoByte and must never be a hardcoded guess -- it has changed before.
        var protocolParams = new DefaultProtocolParamsSupplier(bfBackendService.getEpochService())
                .getProtocolParams();

        var processableScheduledTransactions = new java.util.ArrayList<DatumTankUtxo>();
        int refusedBeforeStarting = 0;
        for (var candidate : dueScheduledTransactions) {
            var refusal = permanentRefusal(candidate, network.getCardanoNetwork(), protocolParams);
            if (refusal == null) {
                processableScheduledTransactions.add(candidate);
                continue;
            }
            refusedBeforeStarting++;
            unprocessableScheduledTransactions.add(TransactionInput.builder()
                    .transactionId(candidate.utxo().getTxHash())
                    .index(candidate.utxo().getOutputIndex())
                    .build());
            // ⚠ ONE LINE, NO STACK TRACE. The reason is known, complete and permanent; a 20-frame
            // trace through Optional.map adds nothing actionable and, at this volume, buries the
            // failures that DO need reading.
            log.warn("Could not process Tank utxo {}:{} — {} Blacklisted until restart.",
                    candidate.utxo().getTxHash(), candidate.utxo().getOutputIndex(), refusal);
        }

        log.info("Found {} Tank Utxos of which {} Scheduled Transactions, {} due, {} refused up "
                        + "front as permanently unprocessable, {} to process this cycle",
                tankUtxos.size(), scheduledTank.size(), dueScheduledTransactions.size(),
                refusedBeforeStarting, processableScheduledTransactions.size());

        // ⛔ ONE WALLET READ AND ONE PROTOCOL-PARAMS READ PER CYCLE, NOT PER TANK.
        //
        // Both of these are PROVIDER CALLS and both were inside the loop below. With 535 processable
        // tanks that is 1,070 Blockfrost calls per cycle, every five minutes — and AppUtxoService's
        // own javadoc promises "one provider call per cycle", which had quietly stopped being true.
        //
        // ⇒ And the read is hoisted because the dependency it served is gone: a tank transaction
        // now spends ONLY ITS OWN TANK, so two of them share no input and cannot conflict.
        List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
        if (walletUtxos.isEmpty()) {
            log.warn("No wallet UTXOs found for account: {}", account.baseAddress());
            return;
        }

        var required = requiredWalletLovelace(protocolParams);

        // ⛔ ONE COLLATERAL UTXO FOR THE WHOLE CYCLE, AND IT IS NEVER SPENT.
        //
        // Collateral is forfeited only on a PHASE-2 failure. On every successful transaction it is
        // named and left alone — so one utxo can back every transaction in the cycle, and the
        // cycle's size stops depending on the shape of the wallet.
        //
        // ⚑ THIS IS WHAT UNCAPPED THE BOT. Previously each tank was assigned its own wallet utxo
        // from a pool, so the wallet's utxo COUNT was the number of tanks per cycle: a wallet of two
        // qualifying utxos processed two tanks a minute against a backlog of 551, and a fragmented
        // wallet (16 utxos of ~1 ada, all unusable) could not be fixed by adding ada.
        //
        // ⚠ The shared-fate this creates is real and worth knowing: if one transaction DOES fail
        // phase 2, this utxo is consumed and every other transaction naming it becomes invalid.
        // That is an argument for the evaluator being wired correctly (CCL trap 8), not against
        // sharing -- a phase-2 failure is already a bug, and one that costs a cycle rather than one
        // transaction is still the same bug.
        // ⚠ Same filter the pool has always used -- ada-only, no reference script, provably
        // covering what the ledger could charge, smallest first -- so the selection rule stays under
        // test rather than drifting into an inline stream nothing exercises.
        var collateralCandidates = walletPool(walletUtxos, required);

        if (collateralCandidates.isEmpty()) {
            var largest = walletUtxos.stream()
                    .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                    .map(LedgerCeilings::lovelaceOf)
                    .max(java.math.BigInteger::compareTo);
            log.warn("no ada-only wallet utxo covers the {} lovelace of collateral this ledger could "
                            + "demand; largest available is {}. ONE such utxo is enough for the "
                            + "whole cycle — collateral is only consumed if a script fails.",
                    required, largest.map(Object::toString).orElse("none at all"));
            return;
        }
        final var collateralUtxo = collateralCandidates.poll();

        int submitted = 0;
        int failed = 0;

        // ⛔ EVERY DUE TANK, EVERY CYCLE. No pool, no per-tank budget, no break.
        //
        // The cycle used to hand each tank its own spendable wallet utxo, which made the wallet's
        // utxo COUNT the number of tanks per cycle -- two qualifying utxos meant two tanks a minute
        // against a backlog of hundreds. Nothing needs one now: the transaction spends only the
        // tank, and stripOperatorContribution removes whatever CCL adds while balancing.
        //
        // ⚠ The collateral utxo is NOT consumed and deliberately not drawn per tank. One backs every
        // transaction in the cycle, because collateral is forfeited only on a phase-2 failure.
        for (var datumTankUtxo : processableScheduledTransactions) {

            var tankPaymentUtxo = datumTankUtxo.utxo();
            var tankDatum = datumTankUtxo.datumTank();

            try {

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

                var tx = tankScriptTx(tankPaymentUtxo, redeemer, payeeAddress.getAddress(),
                        amountToSend, rewardsAddress.getAddress(), reward,
                        account.baseAddress(), parametersRefInput, stakerRefInput,
                        tankContractRefInput);

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

                // ⛔ SHAPE AND BALANCING COME FROM balanceTankTx, SHARED WITH THE TEST.
                //
                // What remains here is exactly what an offline rig must be free to replace: the
                // signers (evaluation is not signing, and the rig holds no mnemonic), the
                // evaluation-error policy, and the structural verifier, which needs datum-derived
                // arguments the caller already holds.
                var context = balanceTankTx(composed, tankPaymentUtxo, collateralUtxo,
                                account.baseAddress(), referenceScriptSafeSupplier(), slot)
                        .withSigner(SignerProviders.signerFrom(account))
                        .withSigner(SignerProviders.stakeKeySignerFrom(account))
                        .ignoreScriptCostEvaluationError(dumpCbor)
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

                // ⛔ complete(), NOT completeAndWait(). Fire and forget.
                //
                // Waiting for confirmation existed to serialise a chain of transactions that shared a
                // wallet input. Nothing is shared now -- each spends only its own tank, and the
                // collateral they have in common is not consumed on success -- so there is nothing
                // to wait FOR, and waiting ~60s per tank is what made a 551-tank backlog take days.
                //
                // ⚠ The cost is that a tank submitted late in a cycle may still look unspent to the
                // indexer when the next cycle reads. Resubmitting it is harmless: the input is gone,
                // so the ledger rejects it at phase 1, free of charge, and it is counted transient
                // rather than blacklisted.
                context.complete();
                submitted++;

            } catch (com.fluidtokens.aquarium.offchain.util.UnusableTankDatumException e) {
                // ⛔ PERMANENT, AND THE ONLY KIND THAT EARNS A BLACKLIST. The datum is written on
                // chain and cannot change, so every future attempt fails identically.
                unprocessableScheduledTransactions.add(TransactionInput.builder()
                        .transactionId(tankPaymentUtxo.getTxHash())
                        .index(tankPaymentUtxo.getOutputIndex())
                        .build());
                // ⚠ ONE LINE, NO STACK TRACE. Expected, fully explained and permanent — the message
                // already names the field and what was wrong with it. A 20-frame trace through
                // Optional.map adds nothing actionable and, at this volume, buries what does.
                //
                // ⚠ Reaching HERE rather than being caught by permanentRefusal() above means the two
                // disagree about what is permanent; the tank is still refused, but the up-front
                // classifier should have seen it.
                log.warn("Could not process Tank utxo {}:{} — {} Blacklisted until restart.",
                        tankPaymentUtxo.getTxHash(), tankPaymentUtxo.getOutputIndex(), e.getMessage());

            } catch (Exception e) {
                // ⛔ TRANSIENT BY DEFAULT — AND THIS IS THE CORRECTION, NOT A REFINEMENT.
                //
                // Until 2026-09-22 this branch did not exist: every exception blacklisted its tank
                // until restart. A provider 500, a 429, an evaluator outage, a node catching up —
                // all of them permanently discarded a perfectly good scheduled payment, and the
                // discard is invisible afterwards because a blacklist produces SILENCE, which reads
                // exactly like an empty queue.
                //
                // ⚑ THAT IS WHY THE BOT WENT QUIET SO FAST. On `main` this loop is a forEach over
                // EVERY processable tank with no bound: one systemic fault — such as the address
                // bug that made the evaluator reject every transaction — rips through the whole
                // backlog in a single cycle, blacklists all of it, and never tries again. 558 live
                // tanks can be written off in minutes by one broken thing, and nothing reports it.
                failed++;
                log.warn("Could not process Tank utxo {}:{} (attempt will be REPEATED next cycle; "
                                + "not blacklisted)",
                        tankPaymentUtxo.getTxHash(), tankPaymentUtxo.getOutputIndex(), e);

                // ⛔ NEVER BREAK THE LOOP. SWALLOW, AND GO ON TO THE NEXT TANK.
                //
                // An earlier version abandoned the cycle after three consecutive failures, on the
                // theory that a run of them means the fault is shared. The theory was wrong in the
                // one case that mattered: 329 of 403 due tanks fail for a reason that is entirely
                // their OWN (a payout below the min-UTxO floor), so three in a row proves nothing
                // about the world and the abort simply stopped the bot from ever reaching the 74
                // tanks that work.
                //
                // ⚠ Tanks are independent. A failure carries no information about the next tank, so
                // there is nothing a bound can protect -- it can only hide the backlog behind the
                // first few bad entries. Giovanni's rule: catch, swallow, continue, attempt them all.
            }

        }

        log.info("Process Payments RUN finished: {} submitted, {} failed and will be retried, {} "
                        + "refused up front, {} blacklisted in total so far",
                submitted, failed, refusedBeforeStarting, unprocessableScheduledTransactions.size());
    }

    /**
     * ⛔ <b>Wallet utxos fit to back this cycle, smallest first — today that means COLLATERAL.</b>
     *
     * <p>This used to hand one spendable utxo to each tank, which quietly made the wallet's utxo
     * COUNT the number of tanks a cycle could do: two qualifying utxos meant two tanks a minute
     * against a backlog of hundreds. That coupling is gone — the transaction spends only the tank —
     * and what survives is the <b>selection rule</b>, which is still exactly right for collateral.
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
    /**
     * ⛔ <b>Is this tank hopeless, and why? {@code null} means "no reason found", never "fine".</b>
     *
     * <p>Only <b>permanent</b> conditions belong here — properties of the datum and the tank's own
     * value, both immutable once on chain, so a refusal issued now is still correct in a week. It
     * must not reach for the provider, the wallet, or the clock: a refusal that depends on the world
     * would blacklist a healthy tank the moment the world twitched.
     *
     * <p>Two conditions qualify today:
     * <ol>
     *   <li><b>the destination address cannot be built</b> — a credential that is not a 28-byte
     *       hash. Unchecked, this produced a 29-byte address under a 57-byte header and a
     *       transaction no node could decode ({@code DeserialiseFailure 0 "expected tag"});</li>
     *   <li><b>the tank cannot cover its own payouts</b> — scheduled amount plus operator reward
     *       exceed what it holds, in any unit. Nothing can add value to a UTxO, so this is final.</li>
     * </ol>
     *
     * <p>⚠ Deliberately <b>not</b> included: whether the tank can also cover the FEE. That needs a
     * fee estimate, an estimate is a guess, and a guess in this function silently blacklists tanks
     * that would have worked. Those fail once in the loop and are retried — the honest cost of not
     * knowing.
     */
    private static String permanentRefusal(DatumTankUtxo candidate, Network network,
                                          com.bloxbean.cardano.client.api.model.ProtocolParams params) {
        String payee;
        try {
            payee = AddressUtil.toAddress(candidate.datumTank().getDestionationaaddress(), network)
                    .getAddress();
        } catch (com.fluidtokens.aquarium.offchain.util.UnusableTankDatumException e) {
            return e.getMessage();
        }

        // ⛔ A PAYOUT BELOW THE MIN-UTXO FLOOR CANNOT BE PAID, BY ANYONE, EVER.
        //
        // Every Cardano output must hold at least (160 + its size) x coinsPerUtxoByte -- about
        // 0.857 ada for a plain ada-only output. A tank whose scheduled amount is under that is
        // asking for an output the ledger will not accept.
        //
        // ⚑ THIS IS THE BULK OF THE BACKLOG. Measured on mainnet 2026-09-22: of 403 due tanks,
        // **329** declare a payout below the floor -- the failing example asked for 700,000 lovelace
        // against a floor of ~857,690. Only 74 are actually payable.
        //
        // ⚠ And it was invisible from the error, because cardano-client-lib does not refuse it: it
        // TOPS THE OUTPUT UP out of change (CCL trap 6), so the transaction builds, reaches the
        // validator, and the validator rejects it for paying an amount the datum did not name. The
        // remote evaluator then reports {"ScriptFailures":{}} -- an empty map, naming nothing.
        var minAda = minAdaFor(payee, candidate.datumTank().getScheduledamount(), params);
        if (minAda != null) {
            return minAda;
        }

        var owed = AssetAmountUtil.toValue(List.of(candidate.datumTank().getScheduledamount()))
                .plus(AssetAmountUtil.toValue(List.of(candidate.datumTank().getReward())));

        // ⚠ Compared unit by unit, because a tank can be rich in ada and still owe a token it does
        // not hold — and the reverse. A single "is it big enough" number cannot express that.
        var held = new java.util.HashMap<String, java.math.BigInteger>();
        for (var amount : candidate.utxo().getAmount()) {
            held.merge(amount.getUnit(), amount.getQuantity(), java.math.BigInteger::add);
        }

        var needed = new java.util.LinkedHashMap<String, java.math.BigInteger>();
        if (owed.getCoin().signum() > 0) {
            needed.put(LOVELACE, owed.getCoin());
        }
        for (var multiAsset : owed.getMultiAssets()) {
            for (var asset : multiAsset.getAssets()) {
                needed.merge(multiAsset.getPolicyId() + asset.getNameAsHex().replaceFirst("^0x", ""),
                        asset.getValue(), java.math.BigInteger::add);
            }
        }

        for (var entry : needed.entrySet()) {
            var have = held.getOrDefault(entry.getKey(), java.math.BigInteger.ZERO);
            if (have.compareTo(entry.getValue()) < 0) {
                return "tank is scheduled to pay out " + entry.getValue() + " of " + entry.getKey()
                        + " but holds only " + have + ", so the transaction can never balance.";
            }
        }
        return null;
    }

    /**
     * ⛔ <b>THE TANK TRANSACTION'S SHAPE, IN ONE PLACE, so a test can drive the SAME construction
     * production does.</b>
     *
     * <p>This repo has already paid for the alternative. A builder was promoted to {@code src/main}
     * byte-identically and its tests all used a rig that supplied what production had to earn — the
     * null-evaluator incident of 2026-08-21, phase-2 failure, collateral forfeit. <b>A test that
     * rebuilds the shape by hand proves the test's shape, not the service's</b>, and the two drift
     * silently because nothing compares them.
     *
     * <p>⚠ Everything about <i>pricing</i> — evaluator, signers, collateral, selection guards — stays
     * with the caller, because those are exactly what an offline rig must be able to substitute.
     * What lives here is only what must be identical: inputs, outputs, redeemer, reference inputs.
     */
    /**
     * ⛔ <b>PUT THE TRANSACTION BACK TO ONE INPUT AND TWO OUTPUTS, AND LET THE FEE TAKE THE REST.</b>
     *
     * <p>A tank is funded as {@code payout + reward + fee}, exactly. Measured on mainnet
     * 2026-09-22: eleven of the fourteen payable tanks hold precisely <b>350,000 lovelace</b> more
     * than they owe, and a fee for this shape prices at roughly
     * {@code 155,381 (minFeeB) + ~66,000 (size) + 104,415 (6,961-byte reference script x 15) +
     * ~26,045 (ex-units) = ~351,841}. Eleven tanks landing on one figure is a funding rule, not a
     * coincidence: <b>there is no spare ada in a tank because none was ever meant to be spare.</b>
     *
     * <p>⚠ <b>cardano-client-lib will not build that transaction on its own, and the reason is
     * worth knowing.</b> It balances to a change output; that output is smaller than min-UTxO; so
     * {@code ChangeOutputAdjustments} reaches into the fee payer's wallet <i>unasked</i> and pulls
     * in an input to top it up. The measured result was a transaction with the operator's utxo as a
     * second input and the operator's change as a third output — which cost the operator a little
     * ada on every single payment, since the tank's whole remainder went back out as fee.
     *
     * <p>⇒ So this runs in {@code postBalanceTx}, which QuickTxBuilder applies <b>after</b>
     * balancing: it removes every input that is not the tank, removes the change output, and sets
     * the fee to what the tank has left. The arithmetic closes exactly —
     * {@code fee = tank − payout − reward} — and the fee can only go UP relative to what CCL
     * computed for the larger body, never below the minimum for this smaller one.
     *
     * <p>⚠ <b>The redeemer index must move with it.</b> A spend redeemer names its input by position
     * in the ledger-sorted input list; dropping an input can shift the tank. Left alone it points at
     * an input that is no longer there.
     *
     * <p>⚠ <b>Ex-units are NOT recomputed, and that is safe in one direction only.</b> They were
     * measured against a body with more inputs, so the script context this ships is strictly
     * smaller and costs no more to evaluate — over-declared, never under. Under-declaring is the
     * phase-2 direction (CCL trap 8); this cannot produce it.
     *
     * @return true when the transaction was reshaped, false when it was left exactly as CCL built it
     */
    static boolean stripOperatorContribution(
            com.bloxbean.cardano.client.transaction.spec.Transaction txn,
            Utxo tankPaymentUtxo, String operatorAddress) {

        var body = txn.getBody();

        var changeOutputs = body.getOutputs().stream()
                .filter(o -> operatorAddress.equals(o.getAddress()))
                .toList();
        if (changeOutputs.size() != 1) {
            // No change to reclaim, or a shape this was not written for. Leave it alone: an
            // unexpected layout is a reason to stop touching the transaction, not to improvise.
            return false;
        }
        var change = changeOutputs.getFirst();
        if (change.getValue().getMultiAssets() != null
                && !change.getValue().getMultiAssets().isEmpty()) {
            // ⚠ Native assets cannot be folded into a fee -- they have to go somewhere. Dropping
            // them would destroy them, which is far worse than an extra output.
            return false;
        }

        var tankInput = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(tankPaymentUtxo.getTxHash())
                .index(tankPaymentUtxo.getOutputIndex())
                .build();
        if (!body.getInputs().contains(tankInput)) {
            return false;
        }

        body.getOutputs().remove(change);
        body.getInputs().removeIf(in -> !in.equals(tankInput));

        var tankLovelace = LedgerCeilings.lovelaceOf(tankPaymentUtxo);
        var paidOut = body.getOutputs().stream()
                .map(o -> o.getValue().getCoin())
                .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add);
        body.setFee(tankLovelace.subtract(paidOut));

        // The tank is now the only input, so any spend redeemer points at index 0.
        if (txn.getWitnessSet() != null && txn.getWitnessSet().getRedeemers() != null) {
            txn.getWitnessSet().getRedeemers().stream()
                    .filter(r -> r.getTag() == com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend)
                    .forEach(r -> r.setIndex(0));
        }
        return true;
    }

    /**
     * ⛔ <b>EVERYTHING THAT DECIDES THE TRANSACTION'S SHAPE AND HOW IT BALANCES, in one place, so a
     * test can exercise the configuration production actually runs.</b>
     *
     * <p>{@link #tankScriptTx} already shares the inputs, outputs and redeemer. That was not
     * enough: an audit on 2026-09-23 compared the knobs set here against those
     * {@code TankTransactionDryEvalTest} set for itself and found the test missing
     * {@code withUtxoSelectionStrategy} and {@code preBalanceTx} — <b>so the reference-script guard
     * and the pre-evaluation fee were both untested, the fee having shipped hours earlier.</b>
     *
     * <p>⚑ This repo has a name for that shape: the 2026-08-21 incident, where a builder was
     * promoted byte-identically and every test used a rig that supplied what production had to
     * earn. <b>A test that rebuilds the configuration by hand tests the rebuild.</b>
     *
     * <p>What deliberately stays with the caller is what an offline rig must be free to replace:
     * the <b>signers</b> (evaluation is not signing, and the rig holds no mnemonic), the
     * <b>evaluator</b>, and the <b>structural verifier</b>, which needs the datum-derived arguments
     * the caller already has in hand.
     */
    static QuickTxBuilder.TxContext balanceTankTx(QuickTxBuilder.TxContext context,
                                                  Utxo tankPaymentUtxo,
                                                  Utxo collateralUtxo,
                                                  String operatorAddress,
                                                  UtxoSupplier referenceScriptSafeSupplier,
                                                  long slot) {
        return context
                .withUtxoSelectionStrategy(
                        ReferenceScriptSafeUtxoSelection.strategy(referenceScriptSafeSupplier))
                // ⛔ TWO JOBS, ONE LAMBDA — because preBalanceTx is a SETTER
                // (QuickTxBuilder:263 assigns), so a second call would silently discard the
                // first. Whatever this hook needs to do has to happen here or not at all.
                .preBalanceTx((ctx, txn) -> {
                    ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(
                            referenceScriptSafeSupplier));
                    // ⛔ BALANCE THE BODY BEFORE IT IS EVALUATED, so the evaluator prices
                    // the transaction we actually ship.
                    //
                    // QuickTxBuilder runs preBalanceTx at :401 and evaluateScriptCost at
                    // :455 -- BEFORE balanceTx at :470. Left alone, the evaluator is handed
                    // a body carrying fee 0 with the tank's whole remainder unaccounted for:
                    // inputs 2,340,000 against outputs 2,000,000 and nothing to close it.
                    // Setting the fee here makes inputs == outputs + fee at the moment of
                    // evaluation, which is also exactly the shape stripOperatorContribution
                    // restores afterwards.
                    //
                    // ⚠ Hypothesis, and stated as one: this has not been reproduced
                    // locally, because the offline Aiken evaluator accepts the unbalanced
                    // body and Blockfrost's does not (it answers {"ScriptFailures":{}} --
                    // an empty map, naming nothing). What is NOT hypothetical is that
                    // evaluating a body you do not ship is wrong on its own terms.
                    var owed = txn.getBody().getOutputs().stream()
                            .map(o -> o.getValue().getCoin())
                            .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add);
                    var remainder = LedgerCeilings.lovelaceOf(tankPaymentUtxo).subtract(owed);
                    if (remainder.signum() > 0) {
                        txn.getBody().setFee(remainder);
                    }
                })
                .withRequiredSigners(
                        new com.bloxbean.cardano.client.address.Address(operatorAddress)
                                .getDelegationCredentialHash().orElseThrow())
                .validFrom(slot - 30)
                .validTo(slot + 180)
                .feePayer(operatorAddress)
                .withCollateralInputs(TransactionInput.builder()
                        .transactionId(collateralUtxo.getTxHash())
                        .index(collateralUtxo.getOutputIndex())
                        .build())
                .collateralPayer(operatorAddress)
                .mergeOutputs(false)
                .postBalanceTx((ctx, txn) -> stripOperatorContribution(
                        txn, tankPaymentUtxo, operatorAddress));
    }

    static ScriptTx tankScriptTx(Utxo tankPaymentUtxo,
                                 com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer,
                                 String payeeAddress,
                                 com.bloxbean.cardano.client.transaction.spec.Value amountToSend,
                                 String rewardsAddress,
                                 com.bloxbean.cardano.client.transaction.spec.Value reward,
                                 String changeAddress,
                                 TransactionInput parametersRefInput,
                                 TransactionInput stakerRefInput,
                                 TransactionInput tankContractRefInput) {
        // ⛔ THE TANK IS THE ONLY INPUT, AND ITS REMAINDER IS THE FEE.
        //
        // A tank is funded as payout + reward + fee, EXACTLY. Measured on mainnet 2026-09-22:
        // eleven of the fourteen payable tanks hold precisely 350,000 lovelace more than they owe,
        // and a fee for this shape prices at roughly
        //
        //     155,381 (minFeeB) + ~66,000 (size) + 104,415 (6,961-byte ref script x 15)
        //         + ~26,045 (317,812 mem / 106,896,000 steps)   =   ~351,841
        //
        // ⇒ Eleven tanks landing on the same figure is a funding rule, not a coincidence. There is
        // no spare ada in a tank because none was ever meant to be spare.
        //
        // ⚠ So a wallet input and a change output are not merely unnecessary, they are WRONG: they
        // add a third output to a transaction whose shape the validator checks. An earlier attempt
        // kept them because removing the wallet input made CCL fail to reach min-UTxO on the change
        // output -- the right reading of that failure was that THE CHANGE OUTPUT SHOULD NOT EXIST,
        // not that the tank needed topping up.
                return new ScriptTx()
                .collectFrom(tankPaymentUtxo, redeemer)
                .payToAddress(payeeAddress, ValueUtil.toAmountList(amountToSend))
                .payToAddress(rewardsAddress, ValueUtil.toAmountList(reward))
                .withChangeAddress(changeAddress)
                .readFrom(parametersRefInput)
                .readFrom(stakerRefInput)
                .readFrom(tankContractRefInput);
    }

    /**
     * @return a refusal message when this payout cannot legally become an output, else {@code null}.
     */
    private static String minAdaFor(String address,
                                    com.fluidtokens.aquarium.offchain.blueprint.types.general.model.CardanoToken token,
                                    com.bloxbean.cardano.client.api.model.ProtocolParams params) {
        var value = AssetAmountUtil.toValue(List.of(token));

        // ⛔ ONLY AN ADA PAYOUT CAN BE "TOO SMALL". A TOKEN PAYOUT DECLARING ZERO ADA IS NORMAL.
        //
        // AssetAmountUtil.toValue puts the quantity in the multi-asset and leaves coin at ZERO for a
        // token payout — so a naive floor check sees 0 < 857,690 and refuses every one of them.
        // ⚠ Measured: that mistake blacklisted 43 live, payable tanks on the first run of this
        // check. A token output's min-ada is supplied by the TRANSACTION, from the wallet, exactly
        // as it should be; the datum is not wrong to omit it.
        //
        // ⇒ So the floor applies to what the datum promises in ADA, and a datum that promises no
        // ada is promising a token, which is a different thing and not this function's business.
        if (!value.getMultiAssets().isEmpty()) {
            return null;
        }

        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address(address)
                .value(value)
                .build();
        var floor = new com.bloxbean.cardano.client.common.MinAdaCalculator(params)
                .calculateMinAda(output);
        if (value.getCoin().compareTo(floor) < 0) {
            return "scheduled payout of " + value.getCoin() + " lovelace is below the min-UTxO floor "
                    + "of " + floor + " for an output at " + address + ", so no valid transaction "
                    + "can pay it.";
        }
        return null;
    }

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
    /**
     * ⛔ <b>A UTXO WHOSE DATUM WE CANNOT READ IS BLACKLISTED, because it will never become
     * readable.</b>
     *
     * <p>A UTxO is immutable. One carrying no inline datum, or bytes that are not a
     * {@code DatumTank}, is in that state permanently — so re-decoding it every cycle can only
     * produce the same failure forever. Measured on mainnet 2026-09-22: <b>35</b> such UTxOs sit at
     * the tank credential, and each was re-read and re-logged once a minute, indefinitely.
     *
     * <p>⚠ <b>And the old log line dumped the entire {@code AddressUtxoEntity}</b> — every field,
     * including the full address, both credentials, the amount list and a row of nulls. Thirty-five
     * of those per cycle is not a diagnostic, it is a wall. One line, naming the utxo and the
     * reason, is what an operator can act on.
     */
    private Function<AddressUtxoEntity, Stream<DatumTankUtxo>> getAddressUtxoEntityStreamFunction() {
        return addressUtxoEntity -> {
            try {
                var inlineDatum = addressUtxoEntity.getInlineDatum();
                var tankDatum = datumConverter.deserialize(inlineDatum);
                return Stream.of(new DatumTankUtxo(tankDatum, toUtxo(addressUtxoEntity)));
            } catch (Exception e) {
                unprocessableScheduledTransactions.add(TransactionInput.builder()
                        .transactionId(addressUtxoEntity.getTxHash())
                        .index(addressUtxoEntity.getOutputIndex())
                        .build());
                log.warn("Could not process Tank utxo {}:{} — {} Blacklisted until restart.",
                        addressUtxoEntity.getTxHash(), addressUtxoEntity.getOutputIndex(),
                        addressUtxoEntity.getInlineDatum() == null
                                ? "it carries no inline datum at all, so it is not a tank."
                                : "its inline datum is not a readable DatumTank.");
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
