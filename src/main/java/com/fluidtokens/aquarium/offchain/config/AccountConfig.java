package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.MnemonicUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AccountConfig {

    /**
     * The node's wallet: CIP-1852 account {@code wallet.account-index} of the mnemonic, i.e.
     * {@code m/1852'/1815'/N'/0/0} — its own payment key AND its own stake key. Index 0 (the default)
     * is the wallet every node derived before the setting existed.
     * <p>
     * ⛔ Not {@code new Account(network, mnemonic, N)}: that constructor sets the ADDRESS index under
     * account 0, so it moves the payment address while keeping account 0's stake key.
     * <p>
     * The index is bound as a String on purpose: Docker Compose's {@code env_file} forwards
     * {@code WALLET_ACCOUNT_INDEX=} as an empty string, which overrides the {@code :0} default.
     */
    @Bean
    public Account account(@Value("${wallet.mnemonic}") String mnemonic,
                           @Value("${network}") String network,
                           @Value("${wallet.account-index:0}") String accountIndex) {

        var actualNetwork = switch (network) {
            case "preprod" -> Networks.preprod();
            case "preview" -> Networks.preview();
            default -> Networks.mainnet();
        };

        var index = parseAccountIndex(accountIndex);

        // createFromMnemonic skips validation for a blank mnemonic and fails later with a misleading
        // "HDKeyPair derivation failed"; validate first so a missing seed still says what it is.
        MnemonicUtil.validateMnemonic(mnemonic);
        var account = Account.createFromMnemonic(actualNetwork, mnemonic, index, 0);
        log.info("INIT - Aquarium Node wallet: account index {}, address {}, stake address {}",
                index, account.baseAddress(), account.stakeAddress());
        return account;

    }

    /**
     * Blank (after trim) or null means account 0. Anything else must be an integer in
     * [0, 2147483647]; a bad value fails the boot rather than silently selecting account 0.
     */
    static int parseAccountIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        var trimmed = raw.trim();
        int index;
        try {
            index = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw invalidAccountIndex(trimmed);
        }
        if (index < 0) {
            throw invalidAccountIndex(trimmed);
        }
        return index;
    }

    private static IllegalStateException invalidAccountIndex(String value) {
        return new IllegalStateException("wallet.account-index (env WALLET_ACCOUNT_INDEX) must be a whole number "
                + "from 0 to 2147483647, or blank for account 0 — got '" + value + "'");
    }


}
