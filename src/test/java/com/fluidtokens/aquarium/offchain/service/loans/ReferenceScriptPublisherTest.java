package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.BuiltTransaction;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Mutation;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Plan;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.PublishedScript;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.UnspendableDestination;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptPublisher.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold, offline proof that the two reference-script publishing transactions are what they claim
 * to be. No network, no wallet, no submission path: the publisher is constructed with a null
 * {@code TransactionProcessor} and everything below reads transactions back out of their own
 * CBOR.
 *
 * <h2>Measured, on preview protocol params (coinsPerUtxoSize 4310, maxTxSize 16_384)</h2>
 * Min-ada is per output, for a base address (57 bytes) carrying that one script and no datum.
 * <pre>
 *   script                 body bytes    min-ada (lovelace)
 *   loan                        2_547            12_003_350
 *   loanSpend                   1_158             6_016_760
 *   lenderManager                 968             5_197_860
 *   lenderManagerSpend          1_158             6_016_760
 *   loanClaimAction             8_662            38_359_000
 *   lmLiquidateAction           4_227            19_244_150
 *   total                      18_720            86_837_880   (86.83788 ada)
 * </pre>
 * The briefed estimate was ~86.55 ada; the measurement is 86.838 ada, 0.33% (0.288 ada) above it.
 * That is rounding in the per-script estimate, not a disagreement — the measurement is the number
 * to fund against. The 18_720-byte script total is what makes a single transaction impossible:
 * {@link #aSingleTransactionForAllSixExceedsMaxTxSize()} measures that at 19_315 bytes against a
 * 16_384 limit.
 *
 * <h2>Transaction sizes and fees, minimum split</h2>
 * <pre>
 *   TX1  loanClaimAction + lmLiquidateAction               13_136 bytes   fee   738_029
 *   TX2  loan + loanSpend + lenderManager + lmSpend         6_234 bytes   fee   434_341
 * </pre>
 * The fees are for an unsigned body budgeted for one vkey witness, so they are what the eventual
 * signed transaction pays rather than an understatement. Total outlay: 86_837_880 locked (all of
 * it recoverable by spending the outputs) plus 1_172_370 in fees, i.e. ~88.01 ada.
 * <p>
 * Sizes move a little with the addresses involved: the funder and change addresses here are
 * 29-byte enterprise addresses, and {@code ReferenceScriptPublishRunnerTest} — whose funder and
 * change are 57-byte base addresses — measures 13_164 and 6_262 bytes for the same two
 * transactions. The min-ada figures do not move: those depend only on the destination, which is a
 * base address in both.
 */
class ReferenceScriptPublisherTest {

    /** Where the reference-script outputs are paid. The runner uses wallet A's base address. */
    private static final String DESTINATION = LoanFixtures.botAddress();

    /**
     * A funder distinct from the destination, so the "exactly N outputs at the destination
     * address" check is exercised in its strict form.
     * {@link #theRunnerShapeFundsFromTheSameWallet()} covers the shape the manual runner actually
     * uses, where change lands at the destination too.
     */
    private static final String FUNDER = LoanFixtures.entAddress(
            "99999999999999999999999999999999999999999999999999999999");

    /** A second address of the same wallet, where the change goes in the runner's shape. */
    private static final String CHANGE = LoanFixtures.entAddress(
            "88888888888888888888888888888888888888888888888888888888");

    /**
     * The validators the shipped plan publishes, taken FROM THE PLAN rather than from
     * {@code Validator.values()}.
     * <p>
     * These tests are about the liquidation validator set and the measurements that follow from it.
     * Driving them off the enum instead coupled them to its cardinality, so adding
     * {@code LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION} on 2026-08-25 broke four of them at once, in the
     * middle of unrelated work, with failures that named byte totals rather than the cause. The enum
     * will grow again; the plan is the thing these assertions actually mean.
     */
    private static final List<Validator> PLANNED = Plan.minimumSplit().flattened();

    private static ReferenceScriptPublisher publisher(String funderAddress) {
        List<Utxo> wallet = List.of(
                LoanFixtures.adaUtxo("aa".repeat(32), 0, funderAddress, 60_000_000L),
                LoanFixtures.adaUtxo("bb".repeat(32), 0, funderAddress, 60_000_000L),
                LoanFixtures.adaUtxo("cc".repeat(32), 0, funderAddress, 60_000_000L));
        return new ReferenceScriptPublisher(LoanFixtures.registry(),
                LoanFixtures.utxoSupplier(wallet), LoanFixtures.protocolParams());
    }

    @Test
    void theMinimumSplitPublishesTheSixAndNothingElse() throws Exception {
        List<BuiltTransaction> built = publisher(FUNDER).build(Plan.minimumSplit(), DESTINATION, FUNDER);
        assertEquals(2, built.size(), "the minimum split is two transactions");

        List<Validator> covered = new ArrayList<>();
        long totalMinAda = 0L;
        for (int i = 0; i < built.size(); i++) {
            BuiltTransaction tx = built.get(i);
            // Everything below reads the transaction back from its own bytes, never the builder's
            // record of what it did.
            Transaction decoded = Transaction.deserialize(tx.cbor());

            assertTrue(tx.sizeBytes() < ReferenceScriptPublisher.MAX_TX_SIZE,
                    "tx" + (i + 1) + " is " + tx.sizeBytes() + " bytes, over maxTxSize");

            int scriptOutputs = 0;
            for (TransactionOutput output : decoded.getBody().getOutputs()) {
                assertNull(output.getInlineDatum(), "no reference-script output carries an inline datum");
                assertNull(output.getDatumHash(), "no reference-script output carries a datum hash");
                if (output.getScriptRef() == null) {
                    assertEquals(FUNDER, output.getAddress(),
                            "the only script-less output is the change to the funder");
                    continue;
                }
                scriptOutputs++;
                assertEquals(DESTINATION, output.getAddress(),
                        "every reference-script output is paid to the destination");

                PlutusScript attached = PlutusScript.deserializeScriptRef(output.getScriptRef());
                assertNotNull(attached, "the script_ref field deserialises to a Plutus script");
            }
            assertEquals(Plan.minimumSplit().groups().get(i).size(), scriptOutputs,
                    "tx" + (i + 1) + " carries exactly the planned number of reference scripts");

            for (PublishedScript published : tx.published()) {
                covered.add(published.validator());
                totalMinAda += published.lovelace();
                // The property that matters: the hash on chain must be the hash this repo derives,
                // or the coordinate is 86 ada locked behind something no verifier will accept.
                assertEquals(publisher(FUNDER).scriptHashOf(published.validator()),
                        published.scriptHash(),
                        published.validator() + " must be published under its derived hash");
                assertEquals(hashOf(PlutusScript.deserializeScriptRef(
                                scriptRefOf(decoded, published.scriptHash()))),
                        published.scriptHash(),
                        "the reported hash is the hash of the script actually in the body");
            }

            System.out.printf("tx%d  %d bytes, fee %d lovelace, locks %d lovelace, publishes %s%n",
                    i + 1, tx.sizeBytes(), tx.feeLovelace(), tx.lockedLovelace(),
                    tx.published().stream().map(p -> p.validator().configKey()).toList());
            tx.published().forEach(p -> System.out.printf("      %-22s %6d body bytes  %10d lovelace"
                            + " (estimate %10d)%n", p.validator().configKey(), p.scriptBodyBytes(),
                    p.lovelace(), ReferenceScriptPublisher.estimatedMinAdaFor(p.validator())));
        }

        Set<Validator> distinct = new LinkedHashSet<>(covered);
        assertEquals(covered.size(), distinct.size(), "no validator is published twice");
        assertEquals(Set.copyOf(PLANNED), distinct,
                "exactly the validators the plan names are published, and no others");

        long totalFee = built.stream().mapToLong(BuiltTransaction::feeLovelace).sum();
        System.out.printf("TOTAL min-ada %d lovelace (%.6f ada); fees %d lovelace; sizes %s%n",
                totalMinAda, totalMinAda / 1_000_000d, totalFee,
                built.stream().map(BuiltTransaction::sizeBytes).toList());

        // Pinned: the measurement, not the estimate. The briefed ~86.55 ada estimate and this
        // differ by 0.288 ada (0.33%), which is rounding in the per-script table rather than a
        // disagreement — but it is the measurement that has to be funded.
        assertEquals(86_837_880L, totalMinAda, "total min-ada across the six reference-script outputs");
        assertTrue(Math.abs(totalMinAda - 86_550_000L) < 500_000L,
                "the measured total must stay within half an ada of the briefed ~86.55 ada estimate");
        assertEquals(13_136, built.get(0).sizeBytes(), "TX1 size");
        assertEquals(6_234, built.get(1).sizeBytes(), "TX2 size");
    }

    /**
     * The measured script bytes, pinned. This is the number the whole slice turns on: 18_720
     * bytes of validator against a 16_384-byte maxTxSize is why a liquidation cannot carry its
     * own scripts and why publishing them is not optional.
     */
    @Test
    void theSixScriptsTotalMoreThanMaxTxSize() {
        ReferenceScriptPublisher publisher = publisher(FUNDER);
        // Pinned per script: bytes, then the min-ada cardano-client-lib computes for an output at
        // a 57-byte base address carrying it. Both are in the class javadoc table.
        Map<Validator, int[]> pinned = new EnumMap<>(Map.of(
                Validator.LOAN, new int[]{2_547, 12_003_350},
                Validator.LOAN_SPEND, new int[]{1_158, 6_016_760},
                Validator.LENDER_MANAGER, new int[]{968, 5_197_860},
                Validator.LENDER_MANAGER_SPEND, new int[]{1_158, 6_016_760},
                Validator.LOAN_CLAIM_ACTION, new int[]{8_662, 38_359_000},
                Validator.LM_LIQUIDATE_ACTION, new int[]{4_227, 19_244_150}));

        int total = 0;
        long totalMinAda = 0L;
        for (Validator validator : PLANNED) {
            int bytes = bodyBytes(publisher.scriptOf(validator));
            long minAda = publisher.minAdaFor(validator, DESTINATION);
            total += bytes;
            totalMinAda += minAda;
            System.out.printf("%-22s %6d bytes  min-ada %10d lovelace%n",
                    validator.configKey(), bytes, minAda);
            assertEquals(pinned.get(validator)[0], bytes, validator + " applied script bytes");
            assertEquals(pinned.get(validator)[1], minAda, validator + " min-ada");
        }
        assertEquals(18_720, total, "the six liquidation validators, in applied bytes");
        assertEquals(86_837_880L, totalMinAda, "total min-ada, the number that has to be funded");
        assertTrue(total > ReferenceScriptPublisher.MAX_TX_SIZE,
                "the six do not fit in one transaction's worth of bytes at all");
    }

    /**
     * The split is a measurement, not a preference: a plan carrying all six in one transaction is
     * built far enough to be measured and then refused.
     */
    @Test
    void aSingleTransactionForAllSixExceedsMaxTxSize() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> publisher(FUNDER).build(
                        Plan.of(PLANNED.toArray(new Validator[0])), DESTINATION, FUNDER));
        System.out.println("single-transaction plan refused: " + e.getMessage());
        assertTrue(e.getMessage().contains("maxTxSize " + ReferenceScriptPublisher.MAX_TX_SIZE),
                "the refusal must name the limit it broke: " + e.getMessage());
        assertTrue(e.getMessage().contains("19315"),
                "the refusal must report the measured size: " + e.getMessage());
    }

    /**
     * Falsifiability. Attach {@code lmLiquidateAction}'s script where the plan says
     * {@code loanClaimAction} goes and the self-check must catch it by hash — a publisher whose
     * hash check cannot fail is worthless, because the failure it is there to prevent (~86 ada
     * locked behind a coordinate that will never verify) is silent on chain.
     */
    @Test
    void aSubstitutedScriptIsCaughtByHash() {
        ReferenceScriptPublisher publisher = publisher(FUNDER);
        Mutation swap = new Mutation(0, 0, Validator.LM_LIQUIDATE_ACTION);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> publisher.build(Plan.minimumSplit(), DESTINATION, FUNDER, FUNDER, swap));
        System.out.println("substituted script rejected: " + e.getMessage());

        assertTrue(e.getMessage().contains("LOAN_CLAIM_ACTION"),
                "the rejection must name what was planned: " + e.getMessage());
        assertTrue(e.getMessage().contains("LM_LIQUIDATE_ACTION"),
                "the rejection must name what was found: " + e.getMessage());
        assertTrue(e.getMessage().contains(publisher.scriptHashOf(Validator.LM_LIQUIDATE_ACTION)),
                "the rejection must quote the hash actually attached: " + e.getMessage());
        assertNotEquals(publisher.scriptHashOf(Validator.LOAN_CLAIM_ACTION),
                publisher.scriptHashOf(Validator.LM_LIQUIDATE_ACTION),
                "the two hashes must differ, or the mutation proves nothing");
    }

    /**
     * A partial plan is <b>accepted</b>, and this test used to assert the opposite.
     * <p>
     * It was written when publishing all six was assumed necessary. Measurement showed it is not:
     * only enough scripts have to travel by reference to bring the liquidation under
     * {@code maxTxSize}, and five inline plus {@code LOAN_CLAIM_ACTION} referenced measures 11,713
     * bytes against 16,384. So the completeness guard was enforcing an assumption already known to
     * be false, and it would have forced an 86.84 ADA spend where 38.36 does the job.
     * <p>
     * The duplicate guard survives — see {@link #aDuplicatingPlanIsRefused()} — because that one
     * defends against a real waste rather than against an assumption. Which subset is correct is a
     * property of the transaction being built, not of the publisher, so the publisher reports what
     * it published and the caller owns the choice.
     */
    @Test
    void aPartialPlanIsAcceptedBecauseNotEveryScriptHasToBePublished() {
        Plan justTheBigOne = Plan.of(Validator.LOAN_CLAIM_ACTION);
        List<BuiltTransaction> built = publisher(FUNDER).build(justTheBigOne, DESTINATION, FUNDER);
        assertEquals(1, built.size(), "one group means one transaction");
        assertEquals(List.of(Validator.LOAN_CLAIM_ACTION),
                built.get(0).published().stream().map(PublishedScript::validator).toList(),
                "the plan must publish exactly what it named and nothing else");
    }

    /** A plan publishing the same script twice is refused: it would lock its min-ada twice over. */
    @Test
    void aDuplicatingPlanIsRefused() {
        Plan duplicated = new Plan(List.of(
                List.of(Validator.LOAN_CLAIM_ACTION, Validator.LM_LIQUIDATE_ACTION),
                List.of(Validator.LOAN, Validator.LOAN, Validator.LOAN_SPEND,
                        Validator.LENDER_MANAGER, Validator.LENDER_MANAGER_SPEND)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> publisher(FUNDER).build(duplicated, DESTINATION, FUNDER));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
    }

    /**
     * The shape the manual runner uses: one wallet funds the transactions and receives the
     * reference-script outputs, with the change sent to a second address of that same wallet. Six
     * script outputs across the two transactions, each holding exactly its min-ada, plus one
     * change output each.
     */
    @Test
    void theRunnerShapeFundsFromTheSameWallet() throws Exception {
        ReferenceScriptPublisher publisher = publisher(DESTINATION);
        List<BuiltTransaction> built =
                publisher.build(Plan.minimumSplit(), DESTINATION, DESTINATION, CHANGE);

        int scriptOutputs = 0;
        int plainOutputs = 0;
        long locked = 0L;
        for (BuiltTransaction tx : built) {
            Transaction decoded = Transaction.deserialize(tx.cbor());
            for (TransactionOutput output : decoded.getBody().getOutputs()) {
                assertNull(output.getInlineDatum(), "no output carries an inline datum");
                assertNull(output.getDatumHash(), "no output carries a datum hash");
                if (output.getScriptRef() != null) {
                    scriptOutputs++;
                    locked += output.getValue().getCoin().longValueExact();
                    assertEquals(DESTINATION, output.getAddress(),
                            "reference scripts are paid to the destination");
                } else {
                    plainOutputs++;
                    assertEquals(CHANGE, output.getAddress(), "change goes to the change address");
                }
            }
        }
        assertEquals(6, scriptOutputs, "six reference-script outputs across the two transactions");
        assertEquals(2, plainOutputs, "one change output per transaction, carrying nothing");
        assertEquals(86_837_880L, locked, "and each of the six holds exactly its own min-ada");
    }

    /**
     * The reason the runner sends its change somewhere of its own, made into a measurement.
     * <p>
     * cardano-client-lib deducts the fee from the largest output at the fee payer's address; when
     * that is also the destination, a reference-script output is a candidate. With 60-ada funding
     * UTxOs and a 38.359-ada {@code loanClaimAction} output, the fee comes out of the script
     * output, {@code ChangeOutputAdjustments} finds it short of min-ada and swallows a whole extra
     * 60-ada UTxO into it. Output count, addresses, datums and every script hash stay correct —
     * <b>only the value is wrong</b>, which is why the exact-min-ada check exists.
     */
    @Test
    void changeAtTheDestinationAddressCorruptsAnOutputAndIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> publisher(DESTINATION).build(Plan.minimumSplit(), DESTINATION, DESTINATION,
                        DESTINATION));
        System.out.println("change-at-destination refused: " + e.getMessage());
        assertTrue(e.getMessage().contains("LOAN_CLAIM_ACTION"),
                "the refusal must name the corrupted output: " + e.getMessage());
        assertTrue(e.getMessage().contains("38359000"),
                "the refusal must state what it should have held: " + e.getMessage());
        assertTrue(e.getMessage().contains("97613711"),
                "the refusal must state what it actually held: " + e.getMessage());
    }

    // ======================================================================================
    // T-028 — an unspendable-by-us destination, fail-closed on mainnet
    // ======================================================================================

    /**
     * The mainnet fail-closed guard, made mutant-proof by pairing it with the testnet controls.
     * A mutant that "always throws" fails the preview/preprod positives; a mutant that "never
     * throws" fails the mainnet negative. Only a guard that throws on mainnet and returns on the
     * testnets passes both, so this pins the guard rather than either half of it.
     * <p>
     * Mainnet is refused <b>before any address is produced</b>: no mainnet destination is derived
     * here, ever — that is a separate, later decision.
     */
    @Test
    void forNetworkRefusesMainnetButReturnsOnTheTestnets() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> UnspendableDestination.forNetwork(Networks.mainnet()),
                "deriving an unspendable destination on mainnet must be refused");
        System.out.println("mainnet destination refused: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("mainnet"),
                "the refusal must name mainnet: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("separate, later"),
                "the refusal must state it is a separate, later decision: " + refused.getMessage());

        assertNotNull(UnspendableDestination.forNetwork(Networks.preview()).address(),
                "preview must return a destination — the guard is not a blanket always-throw");
        assertNotNull(UnspendableDestination.forNetwork(Networks.preprod()).address(),
                "preprod must return a destination too");
    }

    /**
     * The destination is structurally not one we could sign for: its payment credential is a
     * <b>script</b> credential whose hash is the always-fails script's, and it carries no stake
     * credential (an enterprise address). A wallet base address we control would parse to a key
     * payment credential instead, so this rules that out by construction rather than by trust.
     */
    @Test
    void theUnspendableDestinationIsAScriptCredentialWeCannotSignFor() {
        UnspendableDestination dest = UnspendableDestination.forNetwork(Networks.preview());
        Address parsed = new Address(dest.address());

        assertTrue(parsed.getPaymentCredentialHash().isPresent(),
                "the destination must have a payment credential");
        assertEquals(dest.scriptHash(),
                HexUtil.encodeHexString(parsed.getPaymentCredentialHash().orElseThrow()),
                "the payment credential must be the always-fails script's hash");
        assertTrue(parsed.isScriptHashInPaymentPart(),
                "the payment credential must be a SCRIPT credential, not a key we could sign for");
        assertEquals(Optional.empty(), parsed.getDelegationCredentialHash(),
                "an enterprise address: no stake credential, so it is not a wallet base address");
    }

    /**
     * Permanence, proven rather than assumed. A synthetic UTxO placed at the destination — carrying
     * an inline datum, so the V3-optional datum does not let the spend skip script execution — is
     * spent through the real PlutusV3 machine, and the always-fails script must refuse it.
     * <p>
     * Mutant-proof: an always-<em>succeeds</em> script substituted here would make
     * {@code outcome.successful()} true and turn this red. The refusal is asserted to be the spend
     * redeemer's ({@code tag: "Spend", index: 0}), so a rig fault that fails elsewhere cannot pass
     * for the script refusing.
     */
    @Test
    void spendingAUtxoAtTheUnspendableDestinationIsRefusedByItsAlwaysFailsScript() throws Exception {
        UnspendableDestination dest = UnspendableDestination.forNetwork(Networks.preview());

        String spentTxHash = "dd".repeat(32);
        PlutusData unit = ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();
        Utxo atDestination = LoanFixtures.utxo(spentTxHash, 0, dest.address(),
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L))), unit.serializeToHex());

        Redeemer spend = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(unit)
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(1_000_000)).steps(BigInteger.valueOf(500_000_000)).build())
                .build();
        TransactionBody body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput(spentTxHash, 0))))
                .outputs(new ArrayList<>(List.of(TransactionOutput.builder()
                        .address(LoanFixtures.botAddress())
                        .value(Value.builder().coin(BigInteger.valueOf(1_000_000L)).build())
                        .build())))
                .fee(BigInteger.valueOf(1_000_000L))
                .build();
        // The always-fails script travels in the witness set so the input's spend redeemer resolves
        // to it, and is passed to the supplier too so the evaluator can run it — both, exactly as a
        // real witness-attached spend would be.
        Transaction spendTx = Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(new ArrayList<>(List.of(spend)))
                        .plutusV3Scripts(new ArrayList<>(List.of(
                                (com.bloxbean.cardano.client.plutus.spec.PlutusV3Script) dest.script())))
                        .build())
                .build();

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(spendTx, List.of(atDestination),
                LoanFixtures.registry(), List.of(dest.script()));
        System.out.println("spend of the unspendable destination refused: "
                + outcome.detail().replace("\n", " | "));

        assertFalse(outcome.successful(),
                "the always-fails script must refuse any spend of a UTxO at its address");
        assertTrue(outcome.detail().contains("tag: \"Spend\", index: 0"),
                "the refusal must be the spend redeemer's, not a rig fault elsewhere: " + outcome.detail());
    }

    /**
     * The six are published to the unspendable destination with the same shape the base-address
     * publish has: two transactions, six reference-script outputs, every script-bearing output at
     * the destination, none carrying a datum, each hash the repo-derived one. The min-ada is freshly
     * computed against the 29-byte enterprise destination — <b>not</b> the base-address pins, which
     * are specific to {@code botAddress()} and stay untouched.
     */
    @Test
    void theSixArePublishedToTheUnspendableDestination() throws Exception {
        String destination = UnspendableDestination.forNetwork(Networks.preview()).address();
        ReferenceScriptPublisher publisher = publisher(FUNDER);
        List<BuiltTransaction> built = publisher.build(Plan.minimumSplit(), destination, FUNDER, CHANGE);
        assertEquals(2, built.size(), "the minimum split is two transactions");

        int scriptOutputs = 0;
        long totalMinAda = 0L;
        for (BuiltTransaction tx : built) {
            assertTrue(tx.sizeBytes() < ReferenceScriptPublisher.MAX_TX_SIZE,
                    "each transaction must fit maxTxSize");
            Transaction decoded = Transaction.deserialize(tx.cbor());
            for (TransactionOutput output : decoded.getBody().getOutputs()) {
                assertNull(output.getInlineDatum(), "no reference-script output carries an inline datum");
                assertNull(output.getDatumHash(), "no reference-script output carries a datum hash");
                if (output.getScriptRef() == null) {
                    assertEquals(CHANGE, output.getAddress(), "the only script-less output is the change");
                    continue;
                }
                scriptOutputs++;
                assertEquals(destination, output.getAddress(),
                        "every reference script is paid to the unspendable destination");
            }
            for (PublishedScript published : tx.published()) {
                totalMinAda += published.lovelace();
                assertEquals(publisher.scriptHashOf(published.validator()), published.scriptHash(),
                        published.validator() + " must be published under its derived hash");
                assertEquals(publisher.minAdaFor(published.validator(), destination), published.lovelace(),
                        published.validator() + " must hold exactly its min-ada at the unspendable "
                                + "destination, freshly computed for the 29-byte enterprise address");
            }
        }
        assertEquals(6, scriptOutputs, "six reference-script outputs across the two transactions");

        long expectedTotal = 0L;
        for (Validator validator : PLANNED) {
            expectedTotal += publisher.minAdaFor(validator, destination);
        }
        assertEquals(expectedTotal, totalMinAda,
                "the locked total is the sum of the enterprise-destination min-adas");
        assertNotEquals(86_837_880L, totalMinAda,
                "and it is NOT the base-address pin — the enterprise destination is a smaller output");
        System.out.printf("published six to %s, locking %d lovelace (%.6f ada)%n",
                destination, totalMinAda, totalMinAda / 1_000_000d);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static int bodyBytes(PlutusScript script) {
        try {
            return script.serializeScriptBody().length;
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise the script body", e);
        }
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash the script", e);
        }
    }

    /** The raw {@code script_ref} bytes of the output whose attached script hashes to {@code hash}. */
    private static byte[] scriptRefOf(Transaction decoded, String hash) {
        for (TransactionOutput output : decoded.getBody().getOutputs()) {
            if (output.getScriptRef() == null) {
                continue;
            }
            if (hashOf(PlutusScript.deserializeScriptRef(output.getScriptRef())).equals(hash)) {
                return output.getScriptRef();
            }
        }
        throw new IllegalStateException("no output in the body carries a script hashing to " + hash);
    }
}
