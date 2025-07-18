package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WalletHealthIndicator implements HealthIndicator {

    private final AppUtxoService utxoService;

    @Override
    public Health health() {
        try {
            var walletUtxos = utxoService.listWalletUtxo();
            boolean hasAdaOnlyUtxo = walletUtxos.stream()
                    .anyMatch(utxo -> utxo.getAmount().size() == 1);
            
            if (hasAdaOnlyUtxo) {
                return Health.up()
                        .withDetail("status", "Wallet has suitable UTXOs")
                        .withDetail("utxo_count", walletUtxos.size())
                        .withDetail("component", "wallet")
                        .build();
            } else {
                log.warn("[HEALTH] No utxo found for wallet. Ensure you have at least one UTXO with only ada in it.");
                return Health.down()
                        .withDetail("status", "No suitable UTXOs found")
                        .withDetail("message", "Ensure you have at least one UTXO with only ada in it")
                        .withDetail("utxo_count", walletUtxos.size())
                        .withDetail("component", "wallet")
                        .build();
            }
        } catch (Exception e) {
            log.warn("[HEALTH] Wallet health check failed", e);
            return Health.down()
                    .withDetail("status", "Unable to check wallet UTXOs")
                    .withDetail("error", e.getMessage())
                    .withDetail("component", "wallet")
                    .build();
        }
    }
}