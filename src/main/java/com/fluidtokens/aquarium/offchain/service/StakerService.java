package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StakerService {

    private final Account account;

    private final UtxoRepository utxoRepository;

    private final StakerContractService stakerContractService;

    @PostConstruct
    public void init() {
        var stakerRefInput = findStakerRefInput();

        log.info("found {} staker inputs", stakerRefInput.size());

        if (!stakerRefInput.isEmpty()) {
            var stakerInput = stakerRefInput.getFirst();
            log.info("Staker Input: {}:{}", stakerInput.getTransactionId(), stakerInput.getIndex());
        }

    }

    public List<TransactionInput> findStakerRefInput() {

        var stakingToken = AssetType.fromPlutusData(stakerContractService.getScriptHash(), account.getBaseAddress().getDelegationCredentialHash().get());

        var fldtStakes = utxoRepository.findUnspentByOwnerPaymentCredential(stakerContractService.getScriptHashHex(), Pageable.unpaged());
        var stakes = fldtStakes.stream().flatMap(Collection::stream)
                .filter(addressUtxoEntity -> addressUtxoEntity.getAmounts().stream().anyMatch(amount -> stakingToken.toUnit().equals(amount.getUnit())))
                .toList();

        if (stakes.isEmpty()) {
            log.warn("It was not possible to find staked tokens.");
        }

        return stakes.stream()
                .map(utxo -> TransactionInput.builder().transactionId(utxo.getTxHash()).index(utxo.getOutputIndex()).build())
                .toList();

    }

}
