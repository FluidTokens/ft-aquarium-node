package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoStorageImpl;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Repository
@Slf4j
public class TankUtxoStorage extends UtxoStorageImpl {

    private final UtxoRepository utxoRepository;

    private final Set<String> contractPaymentPkh;

    /**
     * Inputs of the CURRENT block whose output was not indexed when {@link #saveSpent} saw them, keyed
     * by the output they spend. ⚠ Thread-confined on purpose: Yaci 0.1.7's {@code UtxoProcessor} calls
     * {@code saveSpent} then {@code saveUnspent} for one block on one thread, and this bean is a
     * singleton — a shared field would let one block's inputs attach to another block's outputs.
     */
    private final ThreadLocal<Map<UtxoKey, TxInput>> pendingSpends = ThreadLocal.withInitial(HashMap::new);

    public TankUtxoStorage(UtxoRepository utxoRepository,
                           TxInputRepository spentOutputRepository,
                           DSLContext dsl,
                           UtxoCache utxoCache,
                           PlatformTransactionManager platformTransactionManager,
                           Account account,
                           ParametersContractService parametersContractService,
                           StakerContractService stakerContractService,
                           TankContractService tankContractService,
                           ObjectProvider<LoansContractRegistry> loansContractRegistry,
                           @Value("${loans.minswap.pool-spend-script-hash:}") String minswapPoolSpendScriptHash) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, platformTransactionManager);
        this.utxoRepository = utxoRepository;
        var pkhs = new LinkedHashSet<>(List.of(
                account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get(),
                parametersContractService.getScriptHashHex(),
                stakerContractService.getScriptHashHex(),
                tankContractService.getScriptHashHex()
        ));
        // ⛔ THE REGISTRY IS ALWAYS PRESENT NOW — `loans.enabled` was removed on 2026-09-04 and v4
        // indexing is unconditional. What varies is whether it has COORDINATES: an unconfigured
        // registry returns an EMPTY credential list, so a fresh install indexes the Aquarium set and
        // nothing else. The ObjectProvider is kept because a test may still wire this class without
        // one, not because the bean is conditional.
        //
        // ⚠ THIS SET IS BUILT ONCE, HERE, AND IT IS WHY THE FLAG HAD TO GO. `saveUnspent` drops
        // everything not in it, at write time, leaving no trace the row was ever offered — while the
        // cursor advances regardless. A credential added later therefore only ever sees blocks from
        // that moment on; the ones that passed meanwhile are unrecoverable short of a cursor delete
        // and a full re-sync. A filter that narrows at startup is a filter that loses history.
        loansContractRegistry.ifAvailable(loans -> pkhs.addAll(loans.indexedPaymentCredentials()));
        // ⛔ FAB-137: THE MINSWAP V2 POOL PAYMENT CREDENTIAL IS WATCHED UNCONDITIONALLY — whatever the
        // registry's state, whatever any convert setting says. A flag here would be a DATA-RETENTION
        // switch, not a feature toggle: while off, a pool UTxO that swaps is marked spent (saveSpent is
        // not filtered) and its successor is dropped, so the pool vanishes from the index while live on
        // chain, and turning the flag back on restores nothing (officina yaci-store-index-scoping §2a).
        // Configuration alone decides: blank means "no Minswap deployment on this network" and adds
        // nothing; malformed is a typo and fails here, naming the key. ⚠ Like every credential in this
        // set, it only sees blocks from the cursor onwards: a pool idle since then is invisible until it
        // next trades, which is why upgrading to the release that added it requires a cursor wipe.
        String minswapPool = minswapPoolSpendScriptHash == null ? "" : minswapPoolSpendScriptHash.strip();
        if (!minswapPool.isEmpty()) {
            if (!minswapPool.matches("[0-9a-fA-F]{56}")) {
                throw new IllegalStateException("loans.minswap.pool-spend-script-hash must be blank or a 56-hex "
                        + "script hash, got [" + minswapPoolSpendScriptHash + "]");
            }
            pkhs.add(minswapPool.toLowerCase(Locale.ROOT));
        }
        this.contractPaymentPkh = Set.copyOf(pkhs);
        log.info("Indexing UTxOs for {} payment credentials: {}", contractPaymentPkh.size(), contractPaymentPkh);
    }

    /**
     * The payment credentials this index keeps, fixed at construction — the ONLY source of truth for
     * "what the index watches". An output under any other credential was discarded at write time and
     * left no trace, so an index answer about it would be indistinguishable from "empty".
     */
    public Set<String> indexedPaymentCredentials() {
        return contractPaymentPkh;
    }

    @Override
    public void saveUnspent(List<AddressUtxo> addressUtxoList) {
        try {
            var fluidtokensRentsAddresses = addressUtxoList
                    .stream()
                    .filter(this::shouldSaveUtxo)
                    .toList();

            super.saveUnspent(fluidtokensRentsAddresses);

            // ⛔ FAB-134 B2b: an output this block both CREATED and SPENT. Its input reached saveSpent
            // first (Yaci 0.1.7 UtxoProcessor writes every input of a block, then every output) and was
            // held back because the output was not indexed yet. Now that it is, its spend is recorded —
            // without this the output stays "unspent" in the index forever: a ghost that coin and
            // collateral selection pick and every build fails on. Only SAVED outputs qualify, so a
            // foreign pair (filtered out above) leaves no row of either kind.
            Map<UtxoKey, TxInput> pending = pendingSpends.get();
            if (!pending.isEmpty()) {
                var sameBlockSpends = fluidtokensRentsAddresses.stream()
                        .map(utxo -> pending.get(new UtxoKey(utxo.getTxHash(), utxo.getOutputIndex())))
                        .filter(Objects::nonNull)
                        .toList();
                super.saveSpent(sameBlockSpends);
            }
        } finally {
            // The remembered inputs live exactly one block. Inputs of outputs that are not ours are
            // dropped here, as they always were.
            pendingSpends.remove();
        }
    }

    private boolean shouldSaveUtxo(AddressUtxo addressUtxo) {
        return addressUtxo.getOwnerPaymentCredential() != null && contractPaymentPkh.contains(addressUtxo.getOwnerPaymentCredential());
    }

    @Override
    public void saveSpent(List<TxInput> txInputs) {
        // A block's remembered inputs live from its saveSpent to its saveUnspent. Starting clean here makes a
        // leak across blocks impossible even if a previous block died between the two calls.
        pendingSpends.remove();
        var fluidtokensRentsInputs = new ArrayList<TxInput>();
        Map<UtxoKey, TxInput> pending = pendingSpends.get();
        for (TxInput txInput : txInputs) {
            if (utxoRepository.findById(new UtxoId(txInput.getTxHash(), txInput.getOutputIndex())).isPresent()) {
                fluidtokensRentsInputs.add(txInput);
            } else {
                // Not indexed YET — possibly an output created earlier in this same block, which
                // saveUnspent is about to store. Held until then; see saveUnspent.
                pending.put(new UtxoKey(txInput.getTxHash(), txInput.getOutputIndex()), txInput);
            }
        }
        try {
            super.saveSpent(fluidtokensRentsInputs);
        } catch (RuntimeException | Error e) {
            // The block is abandoned (UtxoProcessor rethrows and stops the fetcher); its remembered
            // inputs must not survive on this thread into whatever block runs next.
            pendingSpends.remove();
            throw e;
        }
    }

}
