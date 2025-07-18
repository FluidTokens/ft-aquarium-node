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
    private final MetricsService metricsService;
    private final DatumParametersConverter datumParametersConverter = new DatumParametersConverter();

    public DatumParameters loadParameters() {
        return metricsService.timeParameterLoad(() -> {
            try {
                var results = utxoRepository.findUnspentByOwnerPaymentCredential(parametersContractService.getScriptHashHex(), Pageable.unpaged());
                var settings = results.stream()
                        .flatMap(Collection::stream)
                        .toList();

                if (settings.isEmpty()) {
                    metricsService.updateDatabaseConnected(false);
                    throw new RuntimeException("Could not find Parameters utxo. This is usually a temporary issue that can happen during initial syncing, " +
                            "but if problem persist ensure the Yaci store syncing process is correctly configured: i.e. that starting block is before " +
                            "protocol bootstrap transaction. Alternatively your syncing might either be stuck or very slow.");
                }

                if (settings.size() != 1) {
                    throw new RuntimeException(String.format("unexpected number of utxo. it should be only one, but %d found", settings.size()));
                }

                var utxoEntity = settings.getFirst();
                metricsService.updateDatabaseConnected(true);
                return datumParametersConverter.deserialize(utxoEntity.getInlineDatum());
                
            } catch (Exception e) {
                metricsService.updateDatabaseConnected(false);
                throw e;
            }
        });
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

        return TransactionInput.builder()
                .transactionId(utxoEntity.getTxHash())
                .index(utxoEntity.getOutputIndex())
                .build();
    }

}
