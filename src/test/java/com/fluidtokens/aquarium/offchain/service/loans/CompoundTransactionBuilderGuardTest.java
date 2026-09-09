package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder's two hard guards, tested without standing up a transaction build.
 *
 * <p>These are the guards that carry the weight: findings §22.3 establishes that a wrong
 * {@code self.redeemers} index is the one error in this transaction that can be wrong <b>and still
 * evaluate green</b>, so the dry-eval cannot be relied on to catch it. That makes the arithmetic
 * below, and the byte-echo refusal, the real protection.
 */
class CompoundTransactionBuilderGuardTest {

    private static final com.bloxbean.cardano.client.common.model.Network NETWORK =
            com.bloxbean.cardano.client.common.model.Networks.preview();

    private static String reward(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), NETWORK).getAddress();
    }

    // ---- the redeemer-index arithmetic -------------------------------------------------------

    /**
     * A reward redeemer's list position is {@code SCRIPT_SPEND_COUNT + its withdrawal's position},
     * and the withdrawal order is the ledger's, not the order the builder added them in.
     */
    @Test
    void aRewardRedeemerIndexIsTheSpendCountPlusItsWithdrawalPosition() {
        List<String> order = List.of("aa".repeat(28), "bb".repeat(28), "cc".repeat(28));

        assertEquals(4L, CompoundTransactionBuilder.rewardRedeemerIndex(order, "aa".repeat(28)));
        assertEquals(5L, CompoundTransactionBuilder.rewardRedeemerIndex(order, "bb".repeat(28)));
        assertEquals(6L, CompoundTransactionBuilder.rewardRedeemerIndex(order, "cc".repeat(28)));
        assertEquals(4, CompoundTransactionBuilder.SCRIPT_SPEND_COUNT,
                "four script inputs, so four spend redeemers sort before the first reward redeemer");
    }

    @Test
    void anUnknownScriptHashIsRefusedRatherThanReturningMinusOne() {
        var e = assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> CompoundTransactionBuilder.rewardRedeemerIndex(List.of("aa".repeat(28)), "bb".repeat(28)));
        assertEquals(CompoundTransactionBuilder.Refusal.REDEEMER_INDEX_MISMATCH, e.getReason());
    }

    /**
     * ⛔ The body's withdrawal order is canonical — by reward-address bytes — and NOT the order the
     * builder emitted. This is what makes an index computed from insertion order wrong.
     */
    @Test
    void theBodyOrderIsCanonicalNotInsertionOrder() {
        String first = "00".repeat(28);
        String last = "ff".repeat(28);

        // Added last-then-first; the body must still report first-then-last.
        Transaction txn = Transaction.builder()
                .body(TransactionBody.builder()
                        .withdrawals(List.of(
                                new Withdrawal(reward(last), BigInteger.ZERO),
                                new Withdrawal(reward(first), BigInteger.ZERO)))
                        .build())
                .build();

        List<String> inBodyOrder = CompoundTransactionBuilder.rewardScriptHashesInBodyOrder(txn);

        assertEquals(List.of(first, last), inBodyOrder,
                "reward redeemer indexes follow the ledger's ordering, not the builder's");
        assertNotEquals(List.of(last, first), inBodyOrder);
    }

    @Test
    void aBodyWithNoWithdrawalsYieldsAnEmptyOrderRatherThanThrowing() {
        Transaction txn = Transaction.builder().body(TransactionBody.builder().build()).build();
        assertTrue(CompoundTransactionBuilder.rewardScriptHashesInBodyOrder(txn).isEmpty());
    }

    // ---- the byte-echo refusal (CCL trap 4) --------------------------------------------------

    @Test
    void aCanonicalDatumRoundTripsAndIsAccepted() {
        String canonical = "d8799f0102ff";
        assertEquals(canonical,
                assertDoesNotThrowHex(canonical));
    }

    /**
     * ⛔ CCL trap 4, with the fixture the trap itself prescribes: a bytestring over 64 bytes chunked
     * NON-maximally. The chain accepts any chunking; cardano-client-lib always re-emits the maximal
     * 64-byte split, so this datum cannot survive a round trip — and {@code equals_data} would
     * reject the echo on chain, after the fee is spent.
     *
     * <p>A fixture the library produced itself is canonical by construction and would prove nothing,
     * which is why this one is hand-rolled.
     */
    @Test
    void aNonCanonicallyChunkedDatumIsRefusedRatherThanSilentlyReEncoded() {
        // Constr0[ bytes(65) chunked 60 + 5 ] — indefinite-length bytestring, 5f … ff.
        String chunked = "d8799f5f" + "" + "583c" + "ab".repeat(60) + "45" + "cd".repeat(5) + "ff" + "ff";

        var e = assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> CompoundTransactionBuilder.echo(chunked,
                        CompoundTransactionBuilder.Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool"));

        assertEquals(CompoundTransactionBuilder.Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, e.getReason());
        assertTrue(e.getMessage().contains("re-encodes to different bytes"), e.getMessage());
    }

    @Test
    void anAbsentOrUndecodableDatumIsRefused() {
        assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> CompoundTransactionBuilder.echo(null,
                        CompoundTransactionBuilder.Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "bond"));
        assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> CompoundTransactionBuilder.echo("deadbeef",
                        CompoundTransactionBuilder.Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "bond"));
    }

    private static String assertDoesNotThrowHex(String hex) {
        try {
            return CompoundTransactionBuilder.echo(hex,
                    CompoundTransactionBuilder.Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool")
                    .serializeToHex();
        } catch (Exception e) {
            throw new AssertionError("canonical datum was refused: " + e, e);
        }
    }
}
