package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoStorageImpl;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.OracleOutRefIndex;
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
public class TankUtxoStorage extends UtxoStorageImpl implements OracleOutRefIndex {

    private final UtxoRepository utxoRepository;

    /** Yaci's spent-output table, read by {@link #lookup} only; the writes stay {@code super}'s. */
    private final TxInputRepository txInputRepository;

    private final Set<String> contractPaymentPkh;

    private final Set<String> oracleNftPolicies;

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
                           @Value("${loans.minswap.pool-spend-script-hash:}") String minswapPoolSpendScriptHash,
                           @Value("${loans.oracle.indexed-nft-policies:}") String oracleNftPolicies) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, platformTransactionManager);
        this.utxoRepository = utxoRepository;
        this.txInputRepository = spentOutputRepository;
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
        // everything not in it (and holding no token of a configured oracle NFT policy, the second
        // clause added by FAB-196 below) at write time, leaving no trace the row was ever offered —
        // while the cursor advances regardless. A credential added later therefore only ever sees
        // blocks from that moment on; the ones that passed meanwhile are unrecoverable short of a
        // cursor delete and a full re-sync. A filter that narrows at startup is a filter that loses history.
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
        // ⛔ FAB-196: THE SECOND CLAUSE — an output holding a token of a configured oracle NFT policy is
        // kept AT ANY ADDRESS, so the oracle feed and Charli3 provider out-refs a build references can be
        // answered from the index. UNCONDITIONAL, like the Minswap credential: it reads no
        // `loans.oracle.*` switch, because a flag that narrows a write-time filter is a DATA-RETENTION
        // switch (officina yaci-store-index-scoping §2a). Configuration alone decides: blank keeps no
        // policy; malformed is a typo and fails here, naming the key. Fixed at construction, and like the
        // credential set it only sees blocks from the cursor onwards: changing the list re-indexes nothing.
        this.oracleNftPolicies = parseOracleNftPolicies(oracleNftPolicies);
        if (this.oracleNftPolicies.isEmpty()) {
            log.info("Indexing no oracle NFT policy (loans.oracle.indexed-nft-policies is blank): only the "
                    + "payment credentials above are kept");
        } else {
            log.info("Indexing outputs that hold a token of {} oracle NFT policies, at any address: {}",
                    this.oracleNftPolicies.size(), this.oracleNftPolicies);
        }
    }

    private static Set<String> parseOracleNftPolicies(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        var policies = new LinkedHashSet<String>();
        for (String element : raw.split(",", -1)) {
            String policy = element.strip();
            if (!policy.matches("[0-9a-fA-F]{56}")) {
                throw new IllegalStateException("loans.oracle.indexed-nft-policies must be blank or a comma-separated "
                        + "list of 56-hex policy ids, got [" + raw + "]");
            }
            policies.add(policy.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(policies);
    }

    /**
     * The payment credentials this index keeps, fixed at construction — the ONLY source of truth for
     * "what the index watches" by credential. An output under any other credential was discarded at
     * write time and left no trace — UNLESS it holds a token of one of {@link #indexedOracleNftPolicies()}
     * (FAB-196), the second clause, which is likewise fixed at construction and likewise only sees blocks
     * from the cursor onwards. So an index answer about another credential is indistinguishable from
     * "empty", and such an oracle output is reachable by out-ref only.
     */
    public Set<String> indexedPaymentCredentials() {
        return contractPaymentPkh;
    }

    /**
     * The oracle NFT policies whose tokens keep an output at ANY address ({@code loans.oracle.indexed-nft-policies},
     * lowercase), fixed at construction. Empty when the key is blank.
     */
    public Set<String> indexedOracleNftPolicies() {
        return oracleNftPolicies;
    }

    /**
     * ⛔ FAB-196: what this index knows about ONE oracle out-ref expected to hold {@code nft} — LIVE, SPENT or
     * UNKNOWN ({@link OracleOutRefIndex}). Two repository reads by out-ref (the tx hash lowercased, as Yaci
     * stores it), never the {@code UtxoCache}, never Blockfrost.
     *
     * <h2>Read order: the spend FIRST</h2>
     * {@code address_utxo} keeps spent rows; spentness lives in {@code tx_input} ({@code officina:
     * yaci-store-index-scoping} §4b), so an unspent-looking row alone is not "live". The spend is read first
     * because a concurrent prune ({@code deleteBySpentAndBlockLessThan}) deletes BOTH rows in one transaction:
     * <ul>
     *   <li>spend first — a prune between the two reads finds the output row gone too, so "spent" can become
     *       "unknown" but never "live";</li>
     *   <li>output first — a prune between the reads would leave a row read as present and a spend read as
     *       absent: "live" for an output already spent and pruned.</li>
     * </ul>
     * So LIVE needs both reads to agree: no spend recorded, AND a row for exactly that {@code (tx hash, index)}
     * holding the NFT's unit.
     *
     * <h2>UNKNOWN is the default, on purpose</h2>
     * Never seen, pruned, discarded by the write-time filter (§2: indistinguishable from absence), or a row that
     * does not hold the expected NFT — all UNKNOWN. A row without the NFT is not refused here: the probe then
     * asks Blockfrost, which gives today's verdict and today's text.
     */
    @Override
    public Answer lookup(TransactionInput outRef, AssetType nft) {
        var id = new UtxoId(outRef.getTransactionId().toLowerCase(Locale.ROOT), outRef.getIndex());
        var spend = txInputRepository.findById(id);
        if (spend.isPresent()) {
            return Answer.spent(spend.get().getSpentTxHash());
        }
        var output = utxoRepository.findById(id);
        if (output.isPresent() && output.get().getAmounts() != null) {
            String unit = nft.toUnit();
            for (Amt amount : output.get().getAmounts()) {
                if (amount != null && unit.equalsIgnoreCase(amount.getUnit())) {
                    return Answer.live();
                }
            }
        }
        return Answer.unknown();
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
        return (addressUtxo.getOwnerPaymentCredential() != null && contractPaymentPkh.contains(addressUtxo.getOwnerPaymentCredential()))
                || holdsAnOracleNft(addressUtxo);
    }

    /** FAB-196: any amount under a configured oracle NFT policy keeps the output, whatever its address. */
    private boolean holdsAnOracleNft(AddressUtxo addressUtxo) {
        if (oracleNftPolicies.isEmpty() || addressUtxo.getAmounts() == null) {
            return false;
        }
        for (Amt amount : addressUtxo.getAmounts()) {
            if (amount != null && amount.getPolicyId() != null
                    && oracleNftPolicies.contains(amount.getPolicyId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
