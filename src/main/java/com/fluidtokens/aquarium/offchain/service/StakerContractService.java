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
            SpendValidator spendValidator = new SpendValidator(network.getCardanoNetwork());
            plutusScript = spendValidator.getPlutusScript();
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
