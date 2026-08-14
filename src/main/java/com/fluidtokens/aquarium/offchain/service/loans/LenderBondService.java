package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads indexed Lending v4 lender-bond UTxOs and decodes them.
 * <p>
 * Read-only. Everything comes from the local index — no Blockfrost, no chain queries — so this
 * is only as fresh as the Yaci Store cursor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LenderBondService {

    private final UtxoRepository utxoRepository;

    private final LoansContractRegistry registry;

    private final LenderManagerDatumConverter converter = new LenderManagerDatumConverter();

    /**
     * Every lender bond currently unspent at the LenderManager spend credential.
     * <p>
     * A UTxO that fails to decode is logged and skipped rather than failing the whole call:
     * anyone can pay junk to a script address, and one bad output must not blank the endpoint.
     */
    public List<LenderBond> findAll() {
        var lenderBondPolicyId = registry.getLenderBondPolicyId();

        var utxos = utxoRepository
                .findUnspentByOwnerPaymentCredential(registry.getLenderManagerSpendScriptHash(), Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();

        var bonds = utxos.stream()
                .map(utxo -> toLenderBond(utxo, lenderBondPolicyId))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(LenderBond::loanId))
                .toList();

        log.debug("{} utxos at the lender-manager credential, {} decoded as lender bonds",
                utxos.size(), bonds.size());
        return bonds;
    }

    Optional<LenderBond> toLenderBond(Utxo utxo, String lenderBondPolicyId) {
        // The lender-bond NFT is what separates a genuine bond from anything else paid to the address.
        var bondTokens = utxo.getAmount().stream()
                .filter(amount -> amount.getUnit().startsWith(lenderBondPolicyId))
                .toList();
        if (bondTokens.size() != 1) {
            log.debug("skipping {}#{}: {} lender-bond NFTs", utxo.getTxHash(), utxo.getOutputIndex(),
                    bondTokens.size());
            return Optional.empty();
        }
        if (utxo.getInlineDatum() == null) {
            log.warn("lender-bond utxo {}#{} carries the lender-bond NFT but has no inline datum",
                    utxo.getTxHash(), utxo.getOutputIndex());
            return Optional.empty();
        }

        try {
            var datum = converter.deserialize(utxo.getInlineDatum());
            var loanId = bondTokens.getFirst().getUnit().substring(lenderBondPolicyId.length());
            return Optional.of(new LenderBond(
                    utxo.getTxHash(),
                    utxo.getOutputIndex(),
                    utxo.getAddress(),
                    loanId,
                    utxo.getInlineDatum(),
                    datum));
        } catch (Exception e) {
            log.warn("could not decode lender-bond datum at {}#{}: {}",
                    utxo.getTxHash(), utxo.getOutputIndex(), e.getMessage());
            log.debug("undecodable lender-bond datum", e);
            return Optional.empty();
        }
    }
}
