package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoStorageImpl;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Repository
@Slf4j
public class TankUtxoStorage extends UtxoStorageImpl {

    private final UtxoRepository utxoRepository;

    private final List<String> contractPaymentPkh;

    public TankUtxoStorage(UtxoRepository utxoRepository,
                           TxInputRepository spentOutputRepository,
                           DSLContext dsl,
                           UtxoCache utxoCache,
                           PlatformTransactionManager platformTransactionManager,
                           ParametersContractService parametersContractService,
                           StakerContractService stakerContractService,
                           TankContractService tankContractService) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, platformTransactionManager);
        this.utxoRepository = utxoRepository;
        contractPaymentPkh = List.of(
                parametersContractService.getScriptHashHex(),
                stakerContractService.getScriptHashHex(),
                tankContractService.getScriptHashHex()
        );
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
