package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ⛔ FAB-185: a {@link UtxoSupplier} in which the bot's wallet holds <b>exactly one output — the designated
 * input</b>, so nothing cardano-client-lib does while building can reach any other wallet output.
 *
 * <h2>Why a view, and not another guard on a selector</h2>
 * A build hands its supplier to three places — {@code QuickTxBuilder} itself, the
 * {@code UtxoSelectionStrategy} and the {@code UtxoSelector} that {@code ChangeOutputAdjustments} tries first
 * ({@code ccl-transaction-building-traps} §9b) — and each of them lists the fee payer's address to find
 * balancing inputs. Guarding one seam leaves the others open. Handing all three this view instead means the
 * wallet's other outputs (C, the token outputs, a published reference script) are simply not there to be
 * chosen: a designated input too small for the build refuses, it never pulls a second one.
 *
 * <h2>What is hidden, and what is not</h2>
 * <ul>
 *   <li><b>Hidden:</b> every address whose payment credential is the wallet's — same type, same bytes. The
 *       base address and the same-key enterprise address are both the bot's money, so matching the address
 *       STRING would leave the other one open. Such an address lists {@code [designated]} on page 0 when it
 *       is the designated output's own address, and nothing otherwise, and nothing on any later page.</li>
 *   <li><b>Delegated unchanged:</b> every other address — an unparseable one included, since it cannot be
 *       shown to be the wallet's — and every {@link #getTxOutput}. The pinned collateral, the loan, the bond
 *       and every reference input are resolved by out-ref ({@code QuickTxBuilder.buildCollateralOutput},
 *       {@code ReferenceScriptUtil}, the evaluator), so hiding them there would break the build, not guard
 *       it.</li>
 * </ul>
 */
final class DesignatedInputUtxoSupplier implements UtxoSupplier {

    private final UtxoSupplier delegate;
    private final Credential walletCredential;
    private final Utxo designated;

    private DesignatedInputUtxoSupplier(UtxoSupplier delegate, Credential walletCredential, Utxo designated) {
        this.delegate = delegate;
        this.walletCredential = walletCredential;
        this.designated = designated;
    }

    /**
     * The view of {@code delegate} in which {@code walletAddress}'s payment credential holds only
     * {@code designated}.
     *
     * @throws IllegalArgumentException when {@code walletAddress} has no payment credential to hide behind
     */
    static UtxoSupplier of(UtxoSupplier delegate, String walletAddress, Utxo designated) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(designated, "designated");
        Credential credential = paymentCredentialOf(Objects.requireNonNull(walletAddress, "walletAddress"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "wallet address " + walletAddress + " has no payment credential to scope a view to"));
        return new DesignatedInputUtxoSupplier(delegate, credential, designated);
    }

    /**
     * FAB-224 — the view of {@code delegate} in which {@code walletAddress}'s payment credential holds
     * <b>NOTHING</b>: every page of every address with that payment credential is empty, so a build balanced
     * through it cannot spend a single wallet output. Every other address and every {@link #getTxOutput} are
     * delegated unchanged, so the pinned collateral, the loan, the bond and every reference input still resolve
     * by out-ref. For a build that funds itself from its own script inputs and is handed no wallet input at all.
     *
     * @throws IllegalArgumentException when {@code walletAddress} has no payment credential to hide behind,
     *                                  exactly as {@link #of} refuses it
     */
    static UtxoSupplier withoutWalletInput(UtxoSupplier delegate, String walletAddress) {
        Objects.requireNonNull(delegate, "delegate");
        Credential credential = paymentCredentialOf(Objects.requireNonNull(walletAddress, "walletAddress"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "wallet address " + walletAddress + " has no payment credential to scope a view to"));
        return new DesignatedInputUtxoSupplier(delegate, credential, null);
    }

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        if (!isWallet(address)) {
            return delegate.getPage(address, nrOfItems, page, order);
        }
        return designatedAt(address, page);
    }

    @Override
    public List<Utxo> getAll(String address) {
        if (!isWallet(address)) {
            return delegate.getAll(address);
        }
        return designatedAt(address, 0);
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        return delegate.getTxOutput(txHash, outputIndex);
    }

    @Override
    public boolean isUsedAddress(Address address) {
        return delegate.isUsedAddress(address);
    }

    /**
     * {@code [designated]} on page 0 of its own address; nothing anywhere else in the wallet — and nothing at all
     * in a {@link #withoutWalletInput} view, which has no designated input.
     */
    private List<Utxo> designatedAt(String address, Integer page) {
        if (designated == null || (page != null && page > 0)) {
            return List.of();
        }
        return designated.getAddress() != null && designated.getAddress().equals(address)
                ? List.of(designated)
                : List.of();
    }

    private boolean isWallet(String address) {
        return paymentCredentialOf(address)
                .map(credential -> credential.getType() == walletCredential.getType()
                        && Arrays.equals(credential.getBytes(), walletCredential.getBytes()))
                .orElse(false);
    }

    /** The address's payment credential, or empty when it is null, unparseable or has none. */
    private static Optional<Credential> paymentCredentialOf(String address) {
        if (address == null) {
            return Optional.empty();
        }
        try {
            return AddressProvider.getPaymentCredential(new Address(address));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
