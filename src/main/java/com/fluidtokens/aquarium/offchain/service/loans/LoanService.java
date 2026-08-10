package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads indexed Lending v4 loan UTxOs and decodes them.
 * <p>
 * Read-only. Everything comes from the local index — no Blockfrost, no chain queries — so this
 * is only as fresh as the Yaci Store cursor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LoanService {

    private final UtxoRepository utxoRepository;

    private final LoansContractRegistry registry;

    private final LoanDatumConverter converter = new LoanDatumConverter();

    /**
     * Every loan currently unspent at the loan {@code general_spend} credential.
     * <p>
     * Indexing is by payment credential, which is deliberate: loan UTxOs carry the lender's
     * stake credential so the bech32 address differs per lender while the payment credential
     * is fixed. It is also market-independent — {@code loan.ak} is parameterised only by the
     * config NFT, so USDM, DJED and ada loans all live at the same credential and are told
     * apart by {@code datum.principalAsset}.
     * <p>
     * A UTxO that fails to decode is logged and skipped rather than failing the whole call:
     * anyone can pay junk to a script address, and one bad output must not blank the endpoint.
     */
    public List<Loan> findAll() {
        var loanPolicyId = registry.getLoanPolicyId();

        var utxos = utxoRepository
                .findUnspentByOwnerPaymentCredential(registry.getLoanSpendScriptHash(), Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();

        var loans = utxos.stream()
                .map(utxo -> toLoan(utxo, loanPolicyId))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Loan::loanId))
                .toList();

        log.debug("{} utxos at the loan credential, {} decoded as loans", utxos.size(), loans.size());
        return loans;
    }

    private Optional<Loan> toLoan(Utxo utxo, String loanPolicyId) {
        // The loan NFT is what separates a genuine loan from anything else paid to the address.
        var loanTokens = utxo.getAmount().stream()
                .filter(amount -> amount.getUnit().startsWith(loanPolicyId))
                .toList();
        if (loanTokens.size() != 1) {
            log.debug("skipping {}#{}: {} loan NFTs", utxo.getTxHash(), utxo.getOutputIndex(), loanTokens.size());
            return Optional.empty();
        }
        if (utxo.getInlineDatum() == null) {
            log.warn("loan utxo {}#{} carries the loan NFT but has no inline datum",
                    utxo.getTxHash(), utxo.getOutputIndex());
            return Optional.empty();
        }

        try {
            var datum = converter.deserialize(utxo.getInlineDatum());
            var loanId = loanTokens.getFirst().getUnit().substring(loanPolicyId.length());
            return Optional.of(new Loan(
                    utxo.getTxHash(),
                    utxo.getOutputIndex(),
                    utxo.getAddress(),
                    loanId,
                    collateralAmount(utxo, datum.collateral()),
                    quantityOf(utxo, AssetType.LOVELACE),
                    datum));
        } catch (Exception e) {
            log.warn("could not decode loan datum at {}#{}: {}",
                    utxo.getTxHash(), utxo.getOutputIndex(), e.getMessage());
            log.debug("undecodable loan datum", e);
            return Optional.empty();
        }
    }

    /**
     * {@code finance.get_collateral_amount}: a named collateral is that one asset's quantity,
     * while a collection (no asset name) is the sum across the whole policy.
     * <p>
     * Ada collateral is the empty policy id, and on chain
     * {@code quantity_of(value, "", "")} is the <em>lovelace</em> quantity. Matching a unit
     * literally named {@code ""} instead finds nothing and reports zero collateral, which reads
     * as a fully undercollateralised loan.
     */
    private static BigInteger collateralAmount(Utxo utxo, CollateralAsset collateral) {
        if (collateral.isAda()) {
            return quantityOf(utxo, AssetType.LOVELACE);
        }
        return collateral.assetName()
                .map(name -> quantityOf(utxo, collateral.policyId() + name))
                .orElseGet(() -> utxo.getAmount().stream()
                        .filter(amount -> amount.getUnit().startsWith(collateral.policyId()))
                        .map(Amount::getQuantity)
                        .reduce(BigInteger.ZERO, BigInteger::add));
    }

    private static BigInteger quantityOf(Utxo utxo, String unit) {
        if (unit == null) {
            return BigInteger.ZERO;
        }
        return utxo.getAmount().stream()
                .filter(amount -> unit.equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }
}
