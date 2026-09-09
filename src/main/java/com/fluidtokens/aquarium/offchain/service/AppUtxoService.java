package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUtxoService {

    private final Account account;

    private final UtxoRepository utxoRepository;

    private final BFBackendService bfBackendService;

    /**
     * The wallet's unspent outputs, <b>from the provider</b>, with the local index as a fallback.
     *
     * <h2>⛔ Why the order is this way round (T-049)</h2>
     * This used to read the index first and consult the provider <b>only when the index returned
     * EMPTY</b>. That is the anti-pattern {@code officina:yaci-store-index-scoping} §5 exists to name:
     * <b>an index-backed balance cannot tell "the wallet is empty" from "the wallet's history starts
     * below our sync point", and returning a PARTIAL set silently is worse than returning none.</b>
     * Empty triggers a fallback and you get the truth; partial returns quietly, and every downstream
     * decision — coin selection, fee headroom, "can we afford this" — is made on an understated
     * balance with no warning anywhere.
     *
     * <p><b>And the wallet IS indexed</b>, so this is live rather than theoretical:
     * {@code TankUtxoStorage:47} adds the bot's own payment credential to the kept set, so
     * {@code findUnspentByOwnerAddr} returns rows — as many as the configured start point happened to
     * capture.
     *
     * <h2>Who reads this</h2>
     * {@code ScheduledTransactionService} (the <b>mainnet</b> tank processor),
     * {@code LiquidationExecutor} (both liquidation paths) and the healthcheck's {@code wallet_ok}.
     * <b>One partial read and all three are wrong in the same direction at once.</b>
     *
     * <h2>Cost, and why it is already paid</h2>
     * One provider call per cycle. The node already requires this backend for script-cost evaluation
     * on every build — {@code QuickTxBuilder(BackendService)} wires its {@code TransactionProcessor}
     * as the evaluator — so this adds a dependency on nothing that was optional.
     */
    public List<Utxo> listWalletUtxo() {
        return walletUtxos(
                () -> {
                    try {
                        var response = bfBackendService.getUtxoService()
                                .getUtxos(account.baseAddress(), 100, 1);
                        if (response.isSuccessful()) {
                            return response.getValue();
                        }
                        log.warn("provider rejected the wallet utxo query for {}: {}",
                                account.baseAddress(), response.getResponse());
                        return null;
                    } catch (Exception e) {
                        log.warn("provider could not be reached for the wallet utxo query", e);
                        return null;
                    }
                },
                () -> utxoRepository.findUnspentByOwnerAddr(account.baseAddress(), Pageable.unpaged())
                        .stream()
                        .flatMap(Collection::stream)
                        .map(UtxoUtil::toUtxo)
                        .toList());
    }

    /**
     * The decision, separated so it can be tested without a backend or a database.
     *
     * <p>⚠ <b>A provider that answers wins, even when it answers with nothing.</b> An empty provider
     * result is a real answer — the wallet is empty — and must not be second-guessed by an index that
     * may be holding rows the chain has since spent. Only a provider that <em>fails</em> hands over.
     */
    static List<Utxo> walletUtxos(Supplier<List<Utxo>> fromProvider, Supplier<List<Utxo>> fromIndex) {
        List<Utxo> provider = fromProvider.get();
        if (provider != null) {
            return provider;
        }
        List<Utxo> index = fromIndex.get();
        log.warn("wallet utxos are being served from the LOCAL INDEX because the provider did not "
                + "answer; this view may be PARTIAL if the wallet has history below the configured "
                + "sync start, and every balance decision taken from it inherits that. {} utxo(s).",
                index.size());
        return index;
    }
}
