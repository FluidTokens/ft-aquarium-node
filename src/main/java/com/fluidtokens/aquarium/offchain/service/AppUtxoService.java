package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AppUtxoService {

    private final Account account;

    private final UtxoRepository utxoRepository;

    /**
     * The wallet's unspent outputs, <b>from the local Yaci index only, by payment credential</b>.
     *
     * <h2>⛔ No provider, and no fallback (FAB-134 B2)</h2>
     * Giovanni ruled it first-hand: the wallet is never read from Blockfrost. The old fear —
     * {@code officina:yaci-store-index-scoping} §5, an index-backed balance that is silently PARTIAL
     * because the wallet's history starts below the sync point — is addressed by the startup one-shot
     * rebalance ({@code WalletSweepService}): near tip it lists the wallet once and, if any UTxO is not
     * indexed, spends the whole wallet into fresh outputs the index watches being created. It is NOT a
     * guarantee: a failed listing or rebalance releases the processors anyway (Giovanni 2026-10-03), and
     * a partial view then simply under-reports — "eventually consistent; a tx with a missing input just
     * fails". Nothing here second-guesses the index either way.
     *
     * <h2>By credential, not by address</h2>
     * The credential is derived exactly as {@code TankUtxoStorage:47} derives the one it keeps, so
     * this reads precisely what the index stores: every address under the bot's payment key — the
     * base address, the enterprise address, and any other stake part.
     *
     * <h2>Failure and order</h2>
     * A database failure PROPAGATES; it is never turned into an empty wallet. The query has no
     * ORDER BY, so the result is sorted by {@code (txHash, outputIndex)} for a deterministic order.
     * Rows map through {@link UtxoUtil#toUtxo(com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity)},
     * the overload that keeps the reference-script HASH faithful.
     *
     * <h2>Who reads this</h2>
     * {@code ScheduledTransactionService}, {@code LiquidationExecutor}, {@code CompoundExecutor},
     * the healthcheck's {@code wallet_ok} and the readiness page's wallet panel.
     */
    public List<Utxo> listWalletUtxo() {
        String walletPkh = account.getBaseAddress().getPaymentCredentialHash()
                .map(HexUtil::encodeHexString).get();
        return utxoRepository.findUnspentByOwnerPaymentCredential(walletPkh, Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .sorted(Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex))
                .toList();
    }
}
