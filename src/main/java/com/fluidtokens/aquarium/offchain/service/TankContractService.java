package com.fluidtokens.aquarium.offchain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exposes the tank spend script hash, derived per-network at startup by
 * {@link ContractRegistry}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TankContractService {

    private final ContractRegistry contractRegistry;

    public byte[] getScriptHash() {
        return contractRegistry.getTankScriptHash();
    }

    public String getScriptHashHex() {
        return contractRegistry.getTankScriptHashHex();
    }

}
