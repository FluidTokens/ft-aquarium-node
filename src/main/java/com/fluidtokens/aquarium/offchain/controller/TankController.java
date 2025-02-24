package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tanks")
@Slf4j
@RequiredArgsConstructor
public class TankController {

    private final UtxoRepository utxoRepository;

    private final TankContractService tankContractService;

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Tank(String txHash, Integer outputIndex) {

    }

    @GetMapping
    public ResponseEntity<List<Tank>> getTanks() {

        var unspentTanks = utxoRepository.findUnspentByOwnerPaymentCredential(tankContractService.getScriptHashHex(), Pageable.unpaged());

        List<Tank> tanks = unspentTanks
                .stream()
                .flatMap(List::stream)
                .map(utxoEntity -> new Tank(utxoEntity.getTxHash(), utxoEntity.getOutputIndex()))
                .toList();

        return ResponseEntity.ok(tanks);

    }

}
