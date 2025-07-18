package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.blueprint.staker.SpendValidator;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Getter
@Service
@Slf4j
public class StakerContractService {

    private final Credential paymentCredentials;

    private final PlutusScript plutusScript;

    public StakerContractService(AppConfig.Network network) {
        try {
            log.debug("Initializing StakerContractService for network: {}", network.getCardanoNetwork());
            
            SpendValidator spendValidator = new SpendValidator(network.getCardanoNetwork());
            plutusScript = spendValidator.getPlutusScript();
            paymentCredentials = Credential.fromScript(plutusScript.getScriptHash());
            
            log.info("INIT staker contract hash: {}", HexUtil.encodeHexString(paymentCredentials.getBytes()));
            
        } catch (CborSerializationException e) {
            log.error("Failed to initialize StakerContractService: CBOR serialization error while creating staker spend validator for network {}", 
                    network.getCardanoNetwork(), e);
            throw new RuntimeException("Failed to initialize Staker contract service due to CBOR serialization error", e);
        } catch (Exception e) {
            log.error("Failed to initialize StakerContractService: Unexpected error while creating staker spend validator for network {}", 
                    network.getCardanoNetwork(), e);
            throw new RuntimeException("Failed to initialize Staker contract service due to unexpected error", e);
        }
    }

    public byte[] getScriptHash() {
        return paymentCredentials.getBytes();
    }

    public String getScriptHashHex() {
        return HexUtil.encodeHexString(this.getScriptHash());
    }

}
