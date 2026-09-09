package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.quicktx.VerifierException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-059 slice 1 — the only mainnet path now asserts the structure of what it built.
 *
 * <h2>⚠ Note which checks are negative and which are positive</h2>
 * Every hard check below has a case that MUST REJECT, not only a case that passes. A verifier tested
 * solely on well-formed transactions is the shape that has failed repeatedly in this workspace: it
 * runs, it is green, and it can never speak.
 */
class TankStructureVerifierTest {

    private static final String PAYEE = "addr_test1payee";
    private static final String REWARDS = "addr_test1rewards";

    /** Sorts AFTER the tank hash, so the tank input is index 0 in canonical order. */
    private static final String WALLET_TX = "ff".repeat(32);
    private static final String TANK_TX = "11".repeat(32);
    private static final String PARAMS_TX = "22".repeat(32);
    private static final String STAKER_TX = "33".repeat(32);

    private static final Utxo TANK = utxo(TANK_TX, 0);
    private static final TransactionInput PARAMS_REF = new TransactionInput(PARAMS_TX, 0);
    private static final TransactionInput STAKER_REF = new TransactionInput(STAKER_TX, 0);

    private static Utxo utxo(String hash, int ix) {
        Utxo u = new Utxo();
        u.setTxHash(hash);
        u.setOutputIndex(ix);
        return u;
    }

    private static TransactionOutput out(String address, long lovelace) {
        return TransactionOutput.builder().address(address)
                .value(Value.builder().coin(BigInteger.valueOf(lovelace))
                        .multiAssets(new ArrayList<>()).build())
                .build();
    }

    /** Inputs deliberately in NON-canonical order, so the verifier must sort them itself. */
    private static Transaction tx(List<TransactionInput> refInputs, List<TransactionOutput> outputs) {
        return Transaction.builder().body(TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput(WALLET_TX, 0),
                        new TransactionInput(TANK_TX, 0))))
                .referenceInputs(new ArrayList<>(refInputs))
                .outputs(new ArrayList<>(outputs))
                .fee(BigInteger.valueOf(200_000))
                .build()).build();
    }

    private static void verify(Transaction txn, BigInteger paramsIx, BigInteger stakingIx) {
        TankStructureVerifier.of(TANK, PARAMS_REF, STAKER_REF, paramsIx, stakingIx,
                BigInteger.ZERO, PAYEE, BigInteger.valueOf(5_000_000),
                REWARDS, BigInteger.valueOf(1_000_000)).verify(txn);
    }

    private static Transaction wellFormed() {
        // canonical ref-input order is 22… then 33…, so params=0 staker=1
        return tx(List.of(STAKER_REF, PARAMS_REF), List.of(out(PAYEE, 5_000_000), out(REWARDS, 1_000_000)));
    }

    @Test
    void aWellFormedTankTransactionPasses() {
        assertDoesNotThrow(() -> verify(wellFormed(), BigInteger.ZERO, BigInteger.ONE));
    }

    /** ⇒ The reference-input indexes are checked against the SORTED order, not insertion order. */
    @Test
    void aReferenceIndexPointingAtTheWrongInputIsRejected() {
        VerifierException e = assertThrows(VerifierException.class,
                () -> verify(wellFormed(), BigInteger.ONE, BigInteger.ZERO));
        assertTrue(e.getMessage().contains("referenceParamsIndex"), e.getMessage());
    }

    @Test
    void aReferenceIndexOutOfRangeIsRejected() {
        assertThrows(VerifierException.class,
                () -> verify(wellFormed(), BigInteger.valueOf(7), BigInteger.ONE));
    }

    /** ⇒ Paying the wrong address is the failure that costs real money on mainnet. */
    @Test
    void aPayeeOutputAtTheWrongAddressIsRejected() {
        Transaction txn = tx(List.of(STAKER_REF, PARAMS_REF),
                List.of(out("addr_test1someone_else", 5_000_000), out(REWARDS, 1_000_000)));
        VerifierException e = assertThrows(VerifierException.class,
                () -> verify(txn, BigInteger.ZERO, BigInteger.ONE));
        assertTrue(e.getMessage().contains("payee"), e.getMessage());
    }

    @Test
    void aPayeeOutputThatIsShortIsRejected() {
        Transaction txn = tx(List.of(STAKER_REF, PARAMS_REF),
                List.of(out(PAYEE, 4_999_999), out(REWARDS, 1_000_000)));
        assertThrows(VerifierException.class, () -> verify(txn, BigInteger.ZERO, BigInteger.ONE));
    }

    /** But MORE is fine: cardano-client-lib tops outputs up to the min-ada floor. */
    @Test
    void aPayeeOutputToppedUpToMinAdaIsAccepted() {
        Transaction txn = tx(List.of(STAKER_REF, PARAMS_REF),
                List.of(out(PAYEE, 5_500_000), out(REWARDS, 1_000_000)));
        assertDoesNotThrow(() -> verify(txn, BigInteger.ZERO, BigInteger.ONE));
    }

    @Test
    void aMissingRewardsOutputIsRejected() {
        Transaction txn = tx(List.of(STAKER_REF, PARAMS_REF), List.of(out(PAYEE, 5_000_000)));
        VerifierException e = assertThrows(VerifierException.class,
                () -> verify(txn, BigInteger.ZERO, BigInteger.ONE));
        assertTrue(e.getMessage().contains("rewards"), e.getMessage());
    }

    /**
     * ⛔ The "true today" pin the Machine Owner asked for. The tank has no withdrawals, so
     * cardano-client-lib prepends no dummy output and positions are plain emission order. If someone
     * adds a withdrawal, every position in this verifier shifts by one — <b>and this fails loudly
     * rather than silently mis-asserting.</b>
     */
    @Test
    void addingAWithdrawalIsRejectedBecauseItShiftsEveryOutputPosition() {
        Transaction txn = wellFormed();
        txn.getBody().setWithdrawals(new ArrayList<>(List.of(
                Withdrawal.builder().rewardAddress("stake_test1x").coin(BigInteger.ZERO).build())));
        VerifierException e = assertThrows(VerifierException.class,
                () -> verify(txn, BigInteger.ZERO, BigInteger.ONE));
        assertTrue(e.getMessage().contains("dummy output"), e.getMessage());
    }

    /**
     * ⛔⛔ T-066 IS OBSERVED, NOT ENFORCED — and this test exists to keep it that way.
     * <p>
     * Here the wallet hash sorts BEFORE the tank hash, so the tank input is at index 1 while the
     * redeemer hardcodes 0. <b>The verifier must NOT throw.</b> Enforcing it would assume the very
     * thing under test: if the on-chain validator does not read that field as an index into the
     * sorted inputs, a hard assertion would refuse the half of mainnet transactions that currently
     * succeed — <b>turning a suspected 50% failure into a certain one.</b>
     */
    @Test
    void aTankIndexThatDisagreesWithTheSortedOrderIsObservedAndNotEnforced() {
        Utxo lateTank = utxo("ee".repeat(32), 0);   // sorts AFTER the wallet's "ff"? no — before
        Transaction txn = Transaction.builder().body(TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput("11".repeat(32), 0),
                        new TransactionInput(lateTank.getTxHash(), 0))))
                .referenceInputs(new ArrayList<>(List.of(STAKER_REF, PARAMS_REF)))
                .outputs(new ArrayList<>(List.of(out(PAYEE, 5_000_000), out(REWARDS, 1_000_000))))
                .fee(BigInteger.valueOf(200_000)).build()).build();

        assertDoesNotThrow(() -> TankStructureVerifier.of(lateTank, PARAMS_REF, STAKER_REF,
                        BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO,
                        PAYEE, BigInteger.valueOf(5_000_000),
                        REWARDS, BigInteger.valueOf(1_000_000)).verify(txn),
                "the tank index is at 1 here and the redeemer claims 0 — it must be LOGGED, never "
                        + "thrown, until the chain says how the validator reads that field");
    }
}
