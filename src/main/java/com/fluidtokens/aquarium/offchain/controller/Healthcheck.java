package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.stream.Stream;

@RestController
@RequestMapping("/__internal__/healthcheck")
@Slf4j
@RequiredArgsConstructor
public class Healthcheck {

    public record HealthCheck(Boolean dbOkay,
                              Boolean parametersOk,
                              Boolean parametersRefInputOk) {

    }

    private final Account account;

    private final UtxoRepository utxoRepository;

    private final ParametersService parametersService;

    private final StakerService stakerService;

    private final BlockEventListener blockEventListener;

    @GetMapping
    public ResponseEntity<?> healthCheck() {

        if (blockEventListener.getIsSyncing().get()) {
            log.info("[HEALTH] Aquarium Node is correctly syncing the blockchain.");
            return ResponseEntity.ok("...syncing...");
        }

        boolean dbOkay = false;
        boolean walletOk = false;
        try {
            var walletUtxos = utxoRepository.findUnspentByOwnerAddr(account.baseAddress(), Pageable.unpaged());
            dbOkay = true;
            walletOk = walletUtxos.stream()
                    .flatMap(Collection::stream)
                    .anyMatch(addressUtxoEntity -> addressUtxoEntity.getAmounts().size() == 1);
        } catch (Exception e) {
            log.warn("[HEALTH] possible db connection issues", e);
        }

        boolean parametersOk = false;
        try {
            parametersService.loadParameters();
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
                parametersRefInputOk);

        if (!walletOk) {
            log.warn("[HEALTH] No utxo found for wallet. Ensure you have at least one UTXO with only ada in it.");
        }

        if (!stakingFound) {
            log.warn("[HEALTH] The current wallet does not have any FLDT delegated. Ensure you're staking FLDT to the Node's wallet.");
        }

        if (healthCheck) {
            log.info("[HEALTH] Aquarium Node is healthy");
            return ResponseEntity.ok(healthCheckStatus);
        } else {
            return ResponseEntity.internalServerError().body(healthCheckStatus);
        }

    }

}
