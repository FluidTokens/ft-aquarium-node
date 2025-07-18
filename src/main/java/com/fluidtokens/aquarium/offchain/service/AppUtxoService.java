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
    private final MetricsService metricsService;

    public List<Utxo> listWalletUtxo() {
        return metricsService.timeUtxoFetch(() -> {
            // First try to get UTXOs from local repository
            var walletUtxos = getLocalWalletUtxos();
            metricsService.updateWalletUtxoCount(walletUtxos.size());
            
            if (!walletUtxos.isEmpty()) {
                return walletUtxos;
            }
            
            // If no local UTXOs found, fetch from Blockfrost
            return fetchUtxosFromBlockfrost();
        });
    }

    private List<Utxo> getLocalWalletUtxos() {
        var walletUtxosOpt = utxoRepository.findUnspentByOwnerAddr(account.baseAddress(), Pageable.unpaged());
        return walletUtxosOpt.stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();
    }

    private List<Utxo> fetchUtxosFromBlockfrost() {
        try {
            metricsService.incrementBlockfrostApiCall();
            var response = bfBackendService.getUtxoService().getUtxos(account.baseAddress(), 100, 1);
            
            if (response.isSuccessful()) {
                var utxos = response.getValue();
                metricsService.updateWalletUtxoCount(utxos.size());
                return utxos;
            }
            
            log.warn("Failed to fetch UTXOs for address {}: {}", account.baseAddress(), response.getResponse());
            metricsService.incrementBlockfrostApiError();
            return List.of();
            
        } catch (ApiException e) {
            log.error("API exception while fetching UTXOs for address {}", account.baseAddress(), e);
            metricsService.incrementBlockfrostApiError();
            throw new RuntimeException("Failed to fetch UTXOs from Blockfrost", e);
        }
    }

}
