package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exposes the staker spend script (and hash), derived per-network at startup by
 * {@link ContractRegistry}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StakerContractService {

    private final ContractRegistry contractRegistry;

    public PlutusScript getPlutusScript() {
        return contractRegistry.getStakerScript();
    }

    public byte[] getScriptHash() {
        return contractRegistry.getStakerScriptHash();
    }

    public String getScriptHashHex() {
        return contractRegistry.getStakerScriptHashHex();
    }

}
