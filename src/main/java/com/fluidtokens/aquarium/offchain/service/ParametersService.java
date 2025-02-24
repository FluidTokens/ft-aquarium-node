package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.DatumParameters;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumParametersConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParametersService {

    private final UtxoRepository utxoRepository;

    private final ParametersContractService parametersContractService;

    private final DatumParametersConverter datumParametersConverter = new DatumParametersConverter();

    public DatumParameters loadParameters() {

        var results = utxoRepository.findUnspentByOwnerPaymentCredential(parametersContractService.getScriptHashHex(), Pageable.unpaged());
        var settings = results.stream()
                .flatMap(Collection::stream)
                .toList();

        if (settings.size() != 1) {
            throw new RuntimeException(String.format("unexpected number of utxo. it should be only one, but %d found", settings.size()));
        }

        var utxoEntity = settings.getFirst();

        log.info("found settings: {}:{}", utxoEntity.getTxHash(), utxoEntity.getOutputIndex());

        return datumParametersConverter.deserialize(utxoEntity.getInlineDatum());

    }

    public TransactionInput loadParametersRefInput() {

        var results = utxoRepository.findUnspentByOwnerPaymentCredential(parametersContractService.getScriptHashHex(), Pageable.unpaged());
        var settings = results.stream()
                .flatMap(Collection::stream)
                .toList();

        if (settings.size() != 1) {
            throw new RuntimeException(String.format("unexpected number of utxo. it should be only one, but %d found", settings.size()));
        }

        var utxoEntity = settings.getFirst();

        log.info("found settings: {}:{}", utxoEntity.getTxHash(), utxoEntity.getOutputIndex());


        return TransactionInput.builder().transactionId(utxoEntity.getTxHash()).index(utxoEntity.getOutputIndex()).build();

    }


}
