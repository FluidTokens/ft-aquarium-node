package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUtxoService {

    private final Account account;

    private final UtxoRepository utxoRepository;

    private final BFBackendService bfBackendService;

    public List<Utxo> listWalletUtxo() {
        var walletUtxosOpt = utxoRepository.findUnspentByOwnerAddr(account.baseAddress(), Pageable.unpaged());
        var walletUtxos = walletUtxosOpt.stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();

        if (!walletUtxos.isEmpty()) {
            return walletUtxos;
        } else {
            try {
                var response = bfBackendService.getUtxoService().getUtxos(account.baseAddress(), 100, 1);
                if (response.isSuccessful()) {
                 return response.getValue();
                } else {
                    log.warn("Failed to fetch UTXOs for address {}: {}", account.baseAddress(), response.getResponse());
                    return List.of();
                }
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
