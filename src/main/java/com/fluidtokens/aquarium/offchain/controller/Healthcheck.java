package com.fluidtokens.aquarium.offchain.controller;

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
@RequestMapping("/healthcheck")
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
            walletOk = walletUtxos.stream().anyMatch(Healthcheck::spendableByTheBuilders);
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


    /**
     * The minimum a wallet UTxO must hold to be usable, in lovelace.
     * <p>
     * Not arbitrary: the builders nominate ONE ada-only UTxO and use it as BOTH the transaction's
     * only wallet input AND its collateral input, and the ledger requires collateral to cover
     * {@code fee x collateral_percent}. Measured on preview 2026-08-25: a liquidation fee of
     * 1,133,033 at 150% needed 1,699,550 of collateral. 2 ADA clears that with room and is still
     * small enough that "the wallet is fine" means something.
     */
    private static final java.math.BigInteger MIN_SPENDABLE_LOVELACE =
            java.math.BigInteger.valueOf(2_000_000L);

    /**
     * Whether a UTxO is one the transaction builders could actually nominate.
     *
     * <h2>Why this mirrors the builders' predicate exactly</h2>
     * This used to be {@code getAmount().size() == 1} alone, and on 2026-08-25 it reported
     * {@code wallet_ok: true} while the bot held 9,966 ADA and <b>could not build anything</b>: one
     * liquidation had returned its change carrying the collateral tokens it received, which
     * {@code LiquidationExecutor.adaOnlyWalletUtxo()} correctly refuses, leaving only a 1 ADA output
     * that cardano-client-lib had emitted as a withdrawal dummy — too small to collateralise a
     * liquidation and too small for the tank processor to survive spending.
     * <p>
     * <b>A health check whose predicate is narrower than the gate it reports on will say "fine" while
     * the gate says no.</b> So this asks the same four questions {@code adaOnlyWalletUtxo()} asks —
     * single asset, no reference script, no inline datum, no datum hash — and adds the size floor
     * neither of them had. If those predicates ever diverge again, they should diverge deliberately.
     */
    private static boolean spendableByTheBuilders(com.bloxbean.cardano.client.api.model.Utxo utxo) {
        if (utxo.getAmount().size() != 1
                || utxo.getReferenceScriptHash() != null
                || utxo.getInlineDatum() != null
                || utxo.getDataHash() != null) {
            return false;
        }
        return utxo.getAmount().getFirst().getQuantity().compareTo(MIN_SPENDABLE_LOVELACE) >= 0;
    }

}
