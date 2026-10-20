package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
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

    // ---- FAB-152: the bot-output fold -------------------------------------------------------

    private static final String BOT = LoanFixtures.botAddress();
    private static final String SCRIPT = AddressProvider.getEntAddress(
            Credential.fromScript("aa".repeat(28)), NETWORK).getAddress();

    private static TransactionOutput out(String address, long lovelace, boolean datum) {
        TransactionOutput o = new TransactionOutput(address, Value.builder().coin(BigInteger.valueOf(lovelace)).build());
        if (datum) {
            o.setInlineDatum(ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build());
        }
        return o;
    }

    /**
     * ⛔ The fold selects by ADDRESS AND CONTENT, never by index. Output 0 here is a datum-bearing
     * script output — not the withdrawal dummy — and a datum-bearing output also sits at the bot's own
     * address; neither may be touched. Only the two datum-free bot outputs merge, into the LAST of them,
     * carrying every unit across. A fold that assumed "index 0 is the dummy" merges the script output.
     */
    @Test
    void theFoldMatchesByAddressAndContentNeverByIndex() {
        TransactionOutput script = out(SCRIPT, 1_500_000L, true);
        TransactionOutput dummy = out(BOT, 1_000_000L, false);
        TransactionOutput botWithDatum = out(BOT, 2_000_000L, true);
        TransactionOutput other = out(SCRIPT, 3_000_000L, false);
        TransactionOutput change = out(BOT, 10_000_000L, false);
        String unit = "bb".repeat(28) + "cafe";
        change.setValue(change.getValue().add(Value.from("bb".repeat(28), "0xcafe", BigInteger.valueOf(7))));
        Transaction txn = Transaction.builder().body(TransactionBody.builder()
                .outputs(new ArrayList<>(List.of(script, dummy, botWithDatum, other, change))).build()).build();

        CompoundTransactionBuilder.foldBotOutputs(txn, BOT);

        List<TransactionOutput> outputs = txn.getBody().getOutputs();
        assertEquals(4, outputs.size(), "exactly the dummy is gone");
        assertTrue(outputs.get(0) == script && script.getValue().getCoin().longValue() == 1_500_000L,
                "the datum-bearing script output at index 0 is untouched");
        assertTrue(outputs.contains(botWithDatum) && botWithDatum.getValue().getCoin().longValue() == 2_000_000L,
                "a datum-bearing output at the bot's address is not the bot's change");
        assertEquals(3_000_000L, other.getValue().getCoin().longValue());
        assertTrue(outputs.get(3) == change, "the LAST datum-free bot output is the one kept");
        assertEquals(11_000_000L, change.getValue().getCoin().longValue());
        assertEquals(BigInteger.valueOf(7), change.getValue().amountOf("bb".repeat(28), "0xcafe"),
                "every unit is carried across, not just lovelace: " + unit);
    }

    @Test
    void oneDatumFreeBotOutputOrNoneIsLeftAlone() {
        TransactionOutput change = out(BOT, 10_000_000L, false);
        Transaction one = Transaction.builder().body(TransactionBody.builder()
                .outputs(new ArrayList<>(List.of(out(SCRIPT, 2_000_000L, true), change))).build()).build();
        CompoundTransactionBuilder.foldBotOutputs(one, BOT);
        assertEquals(2, one.getBody().getOutputs().size());
        assertEquals(10_000_000L, change.getValue().getCoin().longValue());

        Transaction none = Transaction.builder().body(TransactionBody.builder()
                .outputs(new ArrayList<>(List.of(out(SCRIPT, 2_000_000L, true)))).build()).build();
        CompoundTransactionBuilder.foldBotOutputs(none, BOT);
        assertEquals(1, none.getBody().getOutputs().size());
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
