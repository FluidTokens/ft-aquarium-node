package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
@Slf4j
public class TankUtxoStorage extends UtxoStorageImpl {

    private final UtxoRepository utxoRepository;

    private final Set<String> contractPaymentPkh;

    public TankUtxoStorage(UtxoRepository utxoRepository,
                           TxInputRepository spentOutputRepository,
                           DSLContext dsl,
                           UtxoCache utxoCache,
                           PlatformTransactionManager platformTransactionManager,
                           Account account,
                           ParametersContractService parametersContractService,
                           StakerContractService stakerContractService,
                           TankContractService tankContractService,
                           ObjectProvider<LoansContractRegistry> loansContractRegistry) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, platformTransactionManager);
        this.utxoRepository = utxoRepository;
        var pkhs = new LinkedHashSet<>(List.of(
                account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get(),
                parametersContractService.getScriptHashHex(),
                stakerContractService.getScriptHashHex(),
                tankContractService.getScriptHashHex()
        ));
        // Absent unless loans.enabled=true — the node then also indexes Lending v4 UTxOs.
        loansContractRegistry.ifAvailable(loans -> pkhs.addAll(loans.indexedPaymentCredentials()));
        this.contractPaymentPkh = Set.copyOf(pkhs);
        log.info("Indexing UTxOs for {} payment credentials: {}", contractPaymentPkh.size(), contractPaymentPkh);
    }

    @Override
    public void saveUnspent(List<AddressUtxo> addressUtxoList) {
        var fluidtokensRentsAddresses = addressUtxoList
                .stream()
                .filter(this::shouldSaveUtxo)
                .toList();

        super.saveUnspent(fluidtokensRentsAddresses);
    }

    private boolean shouldSaveUtxo(AddressUtxo addressUtxo) {
        return addressUtxo.getOwnerPaymentCredential() != null && contractPaymentPkh.contains(addressUtxo.getOwnerPaymentCredential());
    }

    @Override
    public void saveSpent(List<TxInput> txInputs) {
        var fluidtokensRentsInputs = txInputs
                .stream()
                .filter(txInput -> utxoRepository.findById(new UtxoId(txInput.getTxHash(), txInput.getOutputIndex())).isPresent())
                .toList();
        super.saveSpent(fluidtokensRentsInputs);
    }

}
