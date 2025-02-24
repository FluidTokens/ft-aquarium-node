package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.blueprint.parameters.SpendValidator;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ParametersContractService {

    private final Credential paymentCredentials;

    public ParametersContractService(AppConfig.Network network) {
        try {
            SpendValidator spendValidator = new SpendValidator(network.getCardanoNetwork());
            PlutusScript plutusScript = spendValidator.getPlutusScript();
            paymentCredentials = Credential.fromScript(plutusScript.getScriptHash());
        } catch (CborSerializationException e) {
            throw new RuntimeException(e);
        }
        log.info("INIT contract hash: {}", HexUtil.encodeHexString(paymentCredentials.getBytes()));
    }

    public byte[] getScriptHash() {
        return paymentCredentials.getBytes();
    }

    public String getScriptHashHex() {
        return HexUtil.encodeHexString(this.getScriptHash());
    }

}
