package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

@RestController
@RequestMapping("/__internal__/healthcheck")
@Slf4j
@RequiredArgsConstructor
public class Healthcheck {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HealthCheck(Boolean dbOk,
                              Boolean parametersOk,
                              Boolean parametersRefInputOk,
                              Boolean walletOk,
                              Boolean stakingOk) {

    }

    private final ParametersService parametersService;
    private final StakerService stakerService;
    private final BlockEventListener blockEventListener;
    private final AppUtxoService utxoService;

    @GetMapping
    public ResponseEntity<?> healthCheck() {

        if (blockEventListener.getIsSyncing().get()) {
            log.info("[HEALTH] Aquarium Node is correctly syncing the blockchain.");
            return ResponseEntity.ok("...syncing...");
        }

        boolean walletOk = false;
        try {
            var walletUtxos = utxoService.listWalletUtxo();
            walletOk = walletUtxos.stream()
                    .anyMatch(utxo -> utxo.getAmount().size() == 1);
        } catch (Exception e) {
            log.warn("[HEALTH] unable to find wallet's utxos", e);
        }

        boolean dbOkay = false;
        boolean parametersOk = false;
        try {
            parametersService.loadParameters();
            dbOkay = true;
            parametersOk = true;
        } catch (Exception e) {
            log.warn("[HEALTH] could not load parameters", e);
        }

        boolean parametersRefInputOk = false;
        try {
            parametersService.loadParametersRefInput();
            parametersRefInputOk = true;
        } catch (Exception e) {
            log.warn("[HEALTH] could not load parameters ref input", e);
        }

        boolean stakingFound = false;
        try {
            var stakerRefInputs = stakerService.findStakerRefInput();
            stakingFound = !stakerRefInputs.isEmpty();
        } catch (Exception e) {
            log.warn("[HEALTH] could not load parameters", e);
        }

        var healthCheck = Stream.of(dbOkay,
                        parametersOk,
                        parametersRefInputOk)
                .reduce(Boolean::logicalAnd)
                .orElse(false);

        var healthCheckStatus = new HealthCheck(dbOkay,
                parametersOk,
                parametersRefInputOk,
                walletOk,
                stakingFound);

        if (!walletOk) {
            log.warn("[HEALTH] No utxo found for wallet. Ensure you have at least one UTXO with only ada in it.");
        }

        if (!stakingFound) {
            log.warn("[HEALTH] The current wallet does not have any FLDT delegated. Ensure you're staking FLDT to the Node's wallet.");
        }

        if (healthCheck) {
            if (walletOk && stakingFound) {
                log.info("[HEALTH] Aquarium Node is healthy and ready to process transactions.");
            } else {
                log.warn("[HEALTH] Aquarium Node is healthy but wallet or staking issues detected. Check logs above for details.");
            }
            return ResponseEntity.ok(healthCheckStatus);
        } else {
            return ResponseEntity.internalServerError().body(healthCheckStatus);
        }

    }

}
