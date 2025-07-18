package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.blueprint.tank.SpendValidator;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TankContractService {

    @Getter
    private final SpendValidator spendValidator;

    private final Credential paymentCredentials;

    public TankContractService(AppConfig.Network network) {
        try {
            log.debug("Initializing TankContractService for network: {}", network.getCardanoNetwork());
            
            spendValidator = new SpendValidator(network.getCardanoNetwork());
            PlutusScript plutusScript = spendValidator.getPlutusScript();
            paymentCredentials = Credential.fromScript(plutusScript.getScriptHash());
            
            log.info("INIT tank contract hash: {}", HexUtil.encodeHexString(paymentCredentials.getBytes()));
            
        } catch (CborSerializationException e) {
            log.error("Failed to initialize TankContractService: CBOR serialization error while creating tank spend validator for network {}", 
                    network.getCardanoNetwork(), e);
            throw new RuntimeException("Failed to initialize Tank contract service due to CBOR serialization error", e);
        } catch (Exception e) {
            log.error("Failed to initialize TankContractService: Unexpected error while creating tank spend validator for network {}", 
                    network.getCardanoNetwork(), e);
            throw new RuntimeException("Failed to initialize Tank contract service due to unexpected error", e);
        }
    }

    public byte[] getScriptHash() {
        return paymentCredentials.getBytes();
    }

    public String getScriptHashHex() {
        return HexUtil.encodeHexString(this.getScriptHash());
    }


}
