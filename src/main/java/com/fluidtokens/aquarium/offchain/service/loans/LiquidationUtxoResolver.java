package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Turns the coordinates a {@link com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment}
 * carries back into the live {@link Utxo}s a liquidation has to spend, and finds the two config
 * reference inputs the transaction reads.
 * <p>
 * <b>Everything comes from the local index.</b> Same posture as {@link LoanService} and
 * {@link LenderBondService}: {@code findUnspentByOwnerPaymentCredential} on the Yaci Store tables,
 * no Blockfrost, no chain query. That matters twice over here — it is the only source that can say
 * "this UTxO is <em>still</em> unspent" without a round trip per candidate, and it keeps a
 * liquidation cycle's cost independent of how many candidates it looks at.
 * <p>
 * <b>Config identification.</b> The main and LM config NFTs each sit at their own validator's
 * script address, so the policy id doubles as the payment credential, and the holder of
 * {@code policyId + configAssetName} at that credential is the config UTxO. That is exactly the
 * rule {@code LoansConfigVerifier.fetchConfigDatumHex} applies — the difference is only where the
 * UTxO set comes from: the verifier asks Blockfrost once at startup, this asks the index every
 * cycle, because a config UTxO can be re-created by an admin action while the node runs.
 * <p>
 * Every method is public and overridable on purpose: the liquidation tests drive a real
 * {@link LiquidateTransactionBuilder} against hand-built UTxOs, and this is the seam they replace.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LiquidationUtxoResolver {

    private final UtxoRepository utxoRepository;

    private final LoansContractRegistry registry;

    private final AppConfig.Network network;

    public LiquidationUtxoResolver(UtxoRepository utxoRepository, LoansContractRegistry registry,
                                   AppConfig.Network network) {
        this.utxoRepository = utxoRepository;
        this.registry = registry;
        this.network = network;
    }

    /**
     * The loan UTxO named by this loan, or empty if it is no longer unspent — the borrower repaid,
     * or somebody else liquidated it, between the scan and now.
     */
    public Optional<Utxo> resolveLoanUtxo(Loan loan) {
        return resolveAt(registry.getLoanSpendScriptHash(), loan.txHash(), loan.outputIndex());
    }

    /** The lender-bond UTxO named by this bond, or empty if it is no longer unspent. */
    public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
        return resolveAt(registry.getLenderManagerSpendScriptHash(), bond.txHash(), bond.outputIndex());
    }

    /** The UTxO holding the main config NFT, read from the index. */
    public Optional<Utxo> resolveConfigUtxo() {
        return resolveConfigHolder(registry.getConfigPolicyId(), "config");
    }

    /** The UTxO holding the LenderManager config NFT, read from the index. */
    public Optional<Utxo> resolveLmConfigUtxo() {
        return resolveConfigHolder(registry.getLmConfigPolicyId(), "lm-config");
    }

    /**
     * The unspent UTxO at {@code paymentCredential} with these coordinates. Scanning the credential's
     * unspent set rather than looking the output up by hash is what makes "still unspent" the
     * question being answered: a spent output is simply not in the set.
     */
    public Optional<Utxo> resolveAt(String paymentCredential, String txHash, int outputIndex) {
        return unspentAt(paymentCredential)
                .filter(utxo -> utxo.getTxHash().equals(txHash) && utxo.getOutputIndex() == outputIndex)
                .findFirst();
    }

    /**
     * T-070 — what a {@code poolId} lookup found, and how far it got.
     *
     * <h2>Three outcomes, because two would be a lie</h2>
     * The obvious shape is found / not-found, with not-found treated as "the index is behind, retry".
     * <b>The index cannot support that distinction:</b> it is a cache of the chain, and an absent row
     * means either the pool does not exist or we have not seen it yet. Those are indistinguishable
     * from here and pretending otherwise would put a guess in a refusal message.
     * <p>
     * What the index <em>can</em> settle is {@link #HALF_VISIBLE}: if exactly one of the pool and its
     * manager is present, <b>the pool provably exists</b> — something minted its NFT — so the missing
     * half is a real gap rather than a non-existent pool. That is the one case where "wait and retry"
     * is a claim rather than a hope.
     */
    public enum PoolLookup {
        /** Both found, both carrying the {@code poolId} NFT, so they are provably the pair. */
        RESOLVED,
        /** Exactly one found. The pool exists; the other half is missing. Transient or unmodelled. */
        HALF_VISIBLE,
        /** Neither found. Cannot distinguish "no such pool" from "index behind" — do not claim to. */
        NOT_VISIBLE
    }

    /** The result of {@link #resolvePool}. {@code pool} and {@code poolManager} may be null. */
    public record PoolPair(PoolLookup outcome, Utxo pool, Utxo poolManager, String detail) {
    }

    /**
     * Resolves the pool and pool-manager UTxOs a lender bond's {@code poolId} names.
     *
     * <h2>⚠ What makes them THE PAIR rather than two UTxOs (T-070, finding M-1)</h2>
     * Locating one UTxO at the pool credential and one at the pool-manager credential is <b>not</b>
     * enough: a node with several pools indexed would happily return two that belong to different
     * ones. Both are matched on the NFT whose <b>asset name is the {@code poolId} itself</b>, under
     * their respective policies, so a mismatched pair cannot be returned as a match.
     *
     * <h2>Unspentness is asserted by the query, not assumed — with one caveat that must be stated</h2>
     * {@link #unspentAt} reads only unspent rows, so both are unspent <em>as far as the index knows</em>.
     * <b>The index is a cache of the chain</b>, and these UTxOs travel as REFERENCE INPUTS, where a
     * stale one is not caught at build time — it fails at submission. This method cannot close that
     * gap and does not claim to; the freshness of the index is the executor's problem, as it already
     * is for the loan and bond UTxOs.
     */
    public PoolPair resolvePool(String poolId) {
        if (poolId == null || poolId.isBlank()) {
            return new PoolPair(PoolLookup.NOT_VISIBLE, null, null,
                    "the bond names no pool, so there is nothing to resolve");
        }
        Optional<Utxo> pool = holderOf(registry.getPoolSpendScriptHash(),
                registry.getPoolPolicyId() + poolId);
        Optional<Utxo> manager = holderOf(registry.getPoolManagerSpendScriptHash(),
                registry.getPoolManagerPolicyId() + poolId);

        if (pool.isPresent() && manager.isPresent()) {
            return new PoolPair(PoolLookup.RESOLVED, pool.get(), manager.get(),
                    "pool " + pool.get().getTxHash() + "#" + pool.get().getOutputIndex()
                            + " and manager " + manager.get().getTxHash() + "#"
                            + manager.get().getOutputIndex() + " both carry poolId " + poolId);
        }
        if (pool.isPresent() || manager.isPresent()) {
            String found = pool.isPresent() ? "pool" : "pool manager";
            String missing = pool.isPresent() ? "pool manager" : "pool";
            return new PoolPair(PoolLookup.HALF_VISIBLE, pool.orElse(null), manager.orElse(null),
                    "poolId " + poolId + " has a visible " + found + " but no " + missing
                            + " in the index; the pool exists, so this is an index gap rather than a "
                            + "missing pool");
        }
        return new PoolPair(PoolLookup.NOT_VISIBLE, null, null,
                "poolId " + poolId + " has neither a pool nor a pool manager in the index; this is "
                        + "indistinguishable from a pool that does not exist");
    }

    /** One unspent UTxO at a credential carrying a specific unit, if the index holds one. */
    private Optional<Utxo> holderOf(String paymentCredential, String unit) {
        return unspentAt(paymentCredential)
                .filter(utxo -> utxo.getAmount() != null
                        && utxo.getAmount().stream().map(Amount::getUnit).anyMatch(unit::equals))
                .findFirst();
    }

    private Optional<Utxo> resolveConfigHolder(String policyId, String what) {
        String unit = policyId + registry.getConfigAssetName();
        Optional<Utxo> configUtxo = unspentAt(policyId)
                .filter(utxo -> utxo.getAmount() != null
                        && utxo.getAmount().stream().map(Amount::getUnit).anyMatch(unit::equals))
                .findFirst();
        if (configUtxo.isEmpty()) {
            // The derived address is the one LoansConfigVerifier reports, so an operator chasing
            // this warning is looking at the same place the startup check looked.
            log.warn("no unspent {} utxo holding {} in the index at {} — the index may not have "
                            + "reached the config yet, or the configured policy id is stale",
                    what, unit, entAddressOf(policyId));
        }
        return configUtxo;
    }

    private Stream<Utxo> unspentAt(String paymentCredential) {
        return utxoRepository
                .findUnspentByOwnerPaymentCredential(paymentCredential, Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo);
    }

    private String entAddressOf(String scriptHash) {
        return AddressProvider
                .getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)),
                        network.getCardanoNetwork())
                .getAddress();
    }
}
