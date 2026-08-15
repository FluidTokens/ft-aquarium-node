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
