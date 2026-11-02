package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ⛔ FAB-185 (a): the view the plain liquidation builder hands cardano-client-lib. The wallet's payment
 * credential — at ANY address that carries it — lists only the designated input, and only at that input's own
 * address; everything else, and every {@code getTxOutput}, is exactly what the delegate answers.
 */
class DesignatedInputUtxoSupplierTest {

    private static final String WALLET_KEY = "11".repeat(28);

    /** The bot's base address: the wallet key with its stake key — {@link LoanFixtures#botAddress()}. */
    private static final String BASE = LoanFixtures.botAddress();
    /** The same payment key with no stake part. Matching address STRINGS would leave this open. */
    private static final String ENTERPRISE = AddressProvider.getEntAddress(Credential.fromKey(WALLET_KEY),
            LoanFixtures.NETWORK).getAddress();
    /** The same payment key under a different stake key. */
    private static final String OTHER_STAKE = AddressProvider.getBaseAddress(Credential.fromKey(WALLET_KEY),
            Credential.fromKey("44".repeat(28)), LoanFixtures.NETWORK).getAddress();
    /** The wallet key's BYTES as a SCRIPT credential: a different credential, so not the wallet. */
    private static final String SAME_BYTES_AS_SCRIPT = AddressProvider.getEntAddress(
            Credential.fromScript(WALLET_KEY), LoanFixtures.NETWORK).getAddress();
    /** Somebody else entirely. */
    private static final String FOREIGN = AddressProvider.getBaseAddress(Credential.fromKey("55".repeat(28)),
            Credential.fromKey("22".repeat(28)), LoanFixtures.NETWORK).getAddress();
    private static final String UNPARSEABLE = "not-an-address";

    private static final Utxo U = LoanFixtures.adaUtxo("e0".repeat(32), 0, BASE, 50_000_000L);
    private static final Utxo C = LoanFixtures.adaUtxo("c5".repeat(32), 0, BASE, 5_000_000L);
    private static final Utxo SPARE = LoanFixtures.adaUtxo("e3".repeat(32), 0, BASE, 30_000_000L);
    private static final Utxo TOKENS = LoanFixtures.utxo("e4".repeat(32), 0, BASE,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)), Amount.asset("ab".repeat(28) + "01",
                    BigInteger.TEN)), null);
    private static final Utxo PUBLISHED_SCRIPT = Utxo.builder().txHash("a1".repeat(32)).outputIndex(0)
            .address(BASE).amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
            .referenceScriptHash("66".repeat(28)).build();
    private static final Utxo AT_ENTERPRISE = LoanFixtures.adaUtxo("e5".repeat(32), 0, ENTERPRISE, 7_000_000L);
    private static final Utxo AT_OTHER_STAKE = LoanFixtures.adaUtxo("e6".repeat(32), 0, OTHER_STAKE, 8_000_000L);
    private static final Utxo AT_SCRIPT = LoanFixtures.adaUtxo("e7".repeat(32), 0, SAME_BYTES_AS_SCRIPT, 9_000_000L);
    private static final Utxo AT_FOREIGN = LoanFixtures.adaUtxo("e8".repeat(32), 0, FOREIGN, 11_000_000L);
    private static final Utxo AT_UNPARSEABLE = LoanFixtures.adaUtxo("e9".repeat(32), 0, UNPARSEABLE, 12_000_000L);

    private static final UtxoSupplier DELEGATE = LoanFixtures.utxoSupplier(List.of(U, C, SPARE, TOKENS,
            PUBLISHED_SCRIPT, AT_ENTERPRISE, AT_OTHER_STAKE, AT_SCRIPT, AT_FOREIGN, AT_UNPARSEABLE));

    /** The wallet's base address and every same-key address list the designated input or nothing. */
    @Test
    void everyAddressOfTheWalletsPaymentCredentialIsHiddenExceptTheDesignatedInput() {
        UtxoSupplier view = DesignatedInputUtxoSupplier.of(DELEGATE, BASE, U);

        // control: the delegate really does hold the outputs the view must hide
        assertEquals(5, DELEGATE.getAll(BASE).size(), "control: C, U, a spare, a token output, a script");
        assertEquals(List.of(AT_ENTERPRISE), DELEGATE.getAll(ENTERPRISE), "control");
        assertEquals(List.of(AT_OTHER_STAKE), DELEGATE.getAll(OTHER_STAKE), "control");

        assertEquals(List.of(U), view.getAll(BASE));
        assertEquals(List.of(U), view.getPage(BASE, 100, 0, OrderEnum.asc));
        for (String sameKey : List.of(ENTERPRISE, OTHER_STAKE)) {
            assertEquals(List.of(), view.getAll(sameKey), sameKey + " carries the wallet key and must list nothing");
            assertEquals(List.of(), view.getPage(sameKey, 100, 0, OrderEnum.asc), sameKey);
        }
    }

    /** The designated input is listed at its own address and at no other address of the wallet. */
    @Test
    void theDesignatedInputIsVisibleOnlyAtItsOwnAddress() {
        UtxoSupplier atEnterprise = DesignatedInputUtxoSupplier.of(DELEGATE, BASE, AT_ENTERPRISE);
        assertEquals(List.of(AT_ENTERPRISE), atEnterprise.getAll(ENTERPRISE));
        assertEquals(List.of(AT_ENTERPRISE), atEnterprise.getPage(ENTERPRISE, 100, 0, OrderEnum.desc));
        assertEquals(List.of(), atEnterprise.getAll(BASE), "U, C and the rest at the base address stay hidden");
        assertEquals(List.of(), atEnterprise.getAll(OTHER_STAKE));

        // the view is scoped by the wallet CREDENTIAL, so which wallet address names it does not matter
        UtxoSupplier namedByEnterprise = DesignatedInputUtxoSupplier.of(DELEGATE, ENTERPRISE, U);
        assertEquals(List.of(U), namedByEnterprise.getAll(BASE));
        assertEquals(List.of(), namedByEnterprise.getAll(ENTERPRISE));
    }

    /** Every other address, an unparseable one included, and every {@code getTxOutput} answer as the delegate does. */
    @Test
    void otherAddressesAndEveryTxOutputAreDelegatedUnchanged() {
        UtxoSupplier view = DesignatedInputUtxoSupplier.of(DELEGATE, BASE, U);

        for (String other : List.of(FOREIGN, SAME_BYTES_AS_SCRIPT, UNPARSEABLE, LoanFixtures.loanAddress())) {
            assertEquals(DELEGATE.getAll(other), view.getAll(other), other);
            for (int page = 0; page < 2; page++) {
                assertEquals(DELEGATE.getPage(other, 100, page, OrderEnum.asc),
                        view.getPage(other, 100, page, OrderEnum.asc), other + " page " + page);
            }
        }
        assertEquals(List.of(AT_FOREIGN), view.getAll(FOREIGN), "control: delegation is not vacuous");
        assertEquals(List.of(AT_SCRIPT), view.getAll(SAME_BYTES_AS_SCRIPT),
                "a script credential with the wallet key's bytes is a different credential");
        assertEquals(List.of(AT_UNPARSEABLE), view.getAll(UNPARSEABLE));

        // out-refs resolve through the view exactly as through the delegate — C above all, which the builder
        // pins as collateral and CCL resolves by getTxOutput while it is hidden from every listing
        for (Utxo utxo : List.of(C, U, SPARE, TOKENS, PUBLISHED_SCRIPT, AT_ENTERPRISE, AT_FOREIGN)) {
            assertEquals(Optional.of(utxo), view.getTxOutput(utxo.getTxHash(), utxo.getOutputIndex()),
                    utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }
        assertEquals(DELEGATE.getTxOutput("ff".repeat(32), 3), view.getTxOutput("ff".repeat(32), 3));
        assertFalse(view.getTxOutput("ff".repeat(32), 3).isPresent(), "control: an unknown out-ref is empty");
    }

    /** A wallet address answers nothing on any page after the first, whatever the delegate holds. */
    @Test
    void everyLaterPageOfAWalletAddressIsEmpty() {
        UtxoSupplier view = DesignatedInputUtxoSupplier.of(DELEGATE, BASE, U);
        for (int page = 1; page <= 3; page++) {
            assertEquals(List.of(), view.getPage(BASE, 100, page, OrderEnum.asc), "page " + page);
            assertEquals(List.of(), view.getPage(ENTERPRISE, 100, page, OrderEnum.asc), "page " + page);
        }
        assertEquals(List.of(U), view.getPage(BASE, 100, null, OrderEnum.asc), "a null page is the first page");
    }

    /** A wallet address with no payment credential to scope by is refused at construction. */
    @Test
    void aWalletAddressWithoutAPaymentCredentialIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DesignatedInputUtxoSupplier.of(DELEGATE, UNPARSEABLE, U));
        assertThrows(NullPointerException.class, () -> DesignatedInputUtxoSupplier.of(DELEGATE, BASE, null));
    }
}
