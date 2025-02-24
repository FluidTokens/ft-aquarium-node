package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Script;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.VerificationKey;
import com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.AddressData;
import com.fluidtokens.aquarium.offchain.blueprint.model.Inline;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.InlineData;
import com.fluidtokens.aquarium.offchain.blueprint.model.impl.VerificationKeyData;

import java.util.Optional;

public class AddressUtil {

    public static com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Address toOnchainAddress(Address address) {
        AddressData addressData = new AddressData();
        //payment credential
        VerificationKeyData paymentKey = new VerificationKeyData();
        paymentKey.setVerificationKeyHash(address.getPaymentCredentialHash().get());
        addressData.setPaymentCredential(paymentKey);

        // stake credentials
        var stakingKey = new com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.impl.VerificationKeyData();
        stakingKey.setVerificationKeyHash(address.getDelegationCredentialHash().get());
        var inlineData = new InlineData();
        inlineData.setCredential(stakingKey);
        addressData.setStakeCredential(Optional.of(inlineData));

        return addressData;
    }

    public static Address toAddress(com.fluidtokens.aquarium.offchain.blueprint.cardano.address.model.Address addressData, Network network) {
        var paymentCredential = switch (addressData.getPaymentCredential()) {
            case com.fluidtokens.aquarium.offchain.blueprint.model.VerificationKey key -> Credential.fromKey(key.getVerificationKeyHash());
            case com.fluidtokens.aquarium.offchain.blueprint.model.Script script -> Credential.fromScript(script.getScriptHash());
            default ->
                    throw new RuntimeException("Unexpected payment credential type: " + addressData.getPaymentCredential());
        };

        var stakeCredentialOpt = addressData.getStakeCredential()
                .flatMap(stakeCredential ->
                        switch (stakeCredential) {
                            case Inline inline -> Optional.of(inline.getCredential());
                            default -> Optional.empty();
                        }
                )
                .map(credential -> switch (credential) {
                    case VerificationKey key ->
                            Credential.fromKey(key.getVerificationKeyHash());
                    case Script script ->
                            Credential.fromKey(script.getScriptHash());
                    default ->
                            throw new RuntimeException("Unexpected staking credential type: " + credential);
                });

        return stakeCredentialOpt
                .map(stakePkh -> AddressProvider.getBaseAddress(paymentCredential, stakePkh, network))
                .orElseGet(() -> AddressProvider.getEntAddress(paymentCredential, network));
    }

}
