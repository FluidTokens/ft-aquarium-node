package com.fluidtokens.aquarium.offchain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exposes the parameters spend script hash, derived per-network at startup by
 * {@link ContractRegistry}. This hash is also the Aquarium config-NFT policy id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParametersContractService {

    private final ContractRegistry contractRegistry;

    public byte[] getScriptHash() {
        return contractRegistry.getParametersScriptHash();
    }

    public String getScriptHashHex() {
        return contractRegistry.getParametersScriptHashHex();
    }

}
