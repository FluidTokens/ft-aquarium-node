package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T-060 — the scan must distinguish a loan that no longer exists from one this node cannot read.
 *
 * <h2>What this is guarding</h2>
 * {@code LiquidationExclusion.LOAN_NOT_FOUND} is raised whenever no loan in our index shares the
 * bond's asset name. That collapses two very different worlds: a <b>settled</b> loan (the NFT was
 * burned, the bond survives as the lender's claim ticket) and a loan that is <b>alive, indexed, at
 * the right address, and simply unreadable by us</b> — because the loans datum decoder is
 * hand-written and a datum shape we do not model produces exactly that. The first is nothing; the
 * second is blindness, and it is the one a "stop counting them" fix would hide forever.
 *
 * <h2>⚠ The correction this class exists to encode</h2>
 * The obvious discriminator — {@code utxos.size() - loans.size()} — is <b>wrong</b>, and I proposed
 * it before reading the drop paths. Anyone can pay junk to a public script address, so that delta
 * mixes UTxOs that were never loans with UTxOs that are. <b>Only a UTxO that carries a loan NFT and
 * still fails to become a {@code Loan} is evidence of anything.</b> Every test below turns on that
 * distinction.
 */
class LoanCensusTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    private static final String POLICY = REGISTRY.getLoanPolicyId();

    private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";

    /** A real preview loan datum, the same fixture the pay-in-advance tests decode. */
    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    private static final String ADDRESS = "addr_test1wq" + "0".repeat(50);

    /** The classification only; no repository is touched. */
    private static LoanService.Census census(List<Utxo> utxos) {
        return new LoanService(null, null).classify(utxos, POLICY);
    }

    private static Utxo loanUtxo(String hash, String datumHex) {
        return LoanFixtures.utxo(hash, 0, ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(3_000_000L)),
                        Amount.asset(POLICY + LOAN_ID, BigInteger.ONE)),
                datumHex);
    }

    @Test
    void arealLoanDecodes() {
        LoanService.Census census = census(List.of(loanUtxo("aa".repeat(32), LOAN_DATUM_HEX)));
        assertEquals(1, census.loans().size());
        assertEquals(0, census.unreadable(), "a loan that decoded is not evidence of blindness");
        assertEquals(0, census.notALoan());
    }

    /**
     * ⇒ <b>THE CORRECTION.</b> Junk at a public script address must NOT count as unreadable. If it
     * did, {@code unreadable} would be permanently non-zero on any real deployment and the whole
     * signal would be useless — it would cry blindness at an address doing exactly what a public
     * address does.
     */
    @Test
    void junkAtThePublicAddressIsNotEvidenceOfBlindness() {
        Utxo junk = LoanFixtures.adaUtxo("bb".repeat(32), 0, ADDRESS, 2_000_000L);
        Utxo moreJunk = LoanFixtures.utxo("cc".repeat(32), 0, ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset("ff".repeat(28) + "abcd", BigInteger.ONE)), "d87980");

        LoanService.Census census = census(List.of(junk, moreJunk));
        assertEquals(0, census.unreadable(),
                "utxos carrying NO loan NFT were never loans and must not inflate the count");
        assertEquals(2, census.notALoan());
        assertEquals(0, census.loans().size());
    }

    /**
     * ⛔ <b>THE SIGNAL.</b> A UTxO carrying a loan NFT whose datum this node cannot decode. It is on
     * chain, it is in the index, it is at the right credential — and some bond is reporting
     * {@code LOAN_NOT_FOUND} because of it.
     */
    @Test
    void aLoanBearingUtxoWithAnUndecodableDatumIsUnreadable() {
        LoanService.Census census = census(List.of(loanUtxo("dd".repeat(32), "d87980")));
        assertEquals(1, census.unreadable());
        assertEquals(0, census.loans().size());
        assertEquals(0, census.notALoan(), "it carries a loan NFT, so it is not junk");
    }

    /** Same signal by a different route: the NFT is there, the datum is absent entirely. */
    @Test
    void aLoanBearingUtxoWithNoDatumIsUnreadable() {
        LoanService.Census census = census(List.of(loanUtxo("ee".repeat(32), null)));
        assertEquals(1, census.unreadable());
        assertEquals(0, census.loans().size());
    }

    /** And a third: two loan NFTs on one output, so the loan's identity is ambiguous. */
    @Test
    void anAmbiguousMultiNftUtxoIsUnreadableRatherThanSilentlyDropped() {
        Utxo twoNfts = LoanFixtures.utxo("ff".repeat(32), 0, ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(3_000_000L)),
                        Amount.asset(POLICY + LOAN_ID, BigInteger.ONE),
                        Amount.asset(POLICY + "aa".repeat(28), BigInteger.ONE)),
                LOAN_DATUM_HEX);

        LoanService.Census census = census(List.of(twoNfts));
        assertEquals(1, census.unreadable(),
                "two loan NFTs is not junk and not a readable loan — it is a loan we cannot identify");
        assertEquals(0, census.notALoan());
    }

    /**
     * The mixed population an operator actually has, and the reading that follows from it: three
     * readable loans, two invisible ones, and a pile of junk that means nothing.
     */
    @Test
    void aMixedPopulationSeparatesCleanlyIntoTheThreeKinds() {
        LoanService.Census census = census(List.of(
                loanUtxo("a1".repeat(32), LOAN_DATUM_HEX),
                loanUtxo("a2".repeat(32), LOAN_DATUM_HEX),
                loanUtxo("a3".repeat(32), LOAN_DATUM_HEX),
                loanUtxo("b1".repeat(32), "d87980"),
                loanUtxo("b2".repeat(32), null),
                LoanFixtures.adaUtxo("c1".repeat(32), 0, ADDRESS, 2_000_000L)));

        assertEquals(3, census.loans().size());
        assertEquals(2, census.unreadable(), "two bonds will report LOAN_NOT_FOUND for LIVE loans");
        assertEquals(1, census.notALoan());
        assertEquals(6, census.utxosAtCredential());
    }

    /** Positive control: the three counts are not the same number wearing three names. */
    @Test
    void theThreeCountsAreIndependent() {
        LoanService.Census onlyJunk = census(List.of(
                LoanFixtures.adaUtxo("d1".repeat(32), 0, ADDRESS, 2_000_000L)));
        LoanService.Census onlyBroken = census(List.of(loanUtxo("d2".repeat(32), "d87980")));
        assertEquals(0, onlyJunk.unreadable());
        assertEquals(1, onlyBroken.unreadable());
        assertEquals(1, onlyJunk.notALoan());
        assertEquals(0, onlyBroken.notALoan());
    }
}
