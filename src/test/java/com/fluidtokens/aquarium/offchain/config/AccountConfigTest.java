package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>{@code wallet.account-index} selects a CIP-1852 ACCOUNT — its payment key AND its stake key.</b>
 *
 * <p>The trap this exists to stop: cardano-client-lib 0.7.2's {@code new Account(network, mnemonic, N)}
 * sets the ADDRESS index under account 0. It still moves the payment address, so a test that checks
 * only the payment address passes on the bug — while the stake key, which the 30k FLDT stake is keyed
 * on, silently stays account 0's. Every assertion below on a non-zero index therefore checks the
 * STAKE address too.
 *
 * <p>Index 0 / unset / blank must be byte-identical to the wallet every node derived before the
 * setting existed ({@code new Account(network, mnemonic)}), or every existing operator's node wakes up
 * signing with a different wallet.
 *
 * <p>The mnemonic is a public test vector from cardano-client-lib's own {@code AccountTest} — never a
 * real seed.
 */
class AccountConfigTest {

    /** cardano-client-lib v0.7.2 AccountTest#getStakeAddressFromMnemonic_whenMainnet. */
    private static final String MNEMONIC = "damp wish scrub sentence vibrant gauge tumble raven game extend "
            + "winner acid side amused vote edge affair buzz hospital slogan patient drum day vital";
    /** That test's pinned expectation — a literal, so a change on both sides of an equality still shows. */
    private static final String MAINNET_STAKE_ADDRESS_ACCOUNT_0 = "stake1u9xeg0r67z4wca682l28ghg69jxaxgswdmpvnher7at697quawequ";

    private static Account account(String network, String index) {
        return new AccountConfig().account(MNEMONIC, network, index);
    }

    private static Network networkOf(String network) {
        return "preview".equals(network) ? Networks.preview() : Networks.mainnet();
    }

    private static String paymentKeyHash(Account account) {
        return HexUtil.encodeHexString(account.hdKeyPair().getPublicKey().getKeyHash());
    }

    @SuppressWarnings("deprecation")
    private static Account legacy(String network) {
        return new Account(networkOf(network), MNEMONIC);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "", "  ", " 0 "})
    void theDefaultIndexIsTheWalletEveryNodeDerivedBefore(String index) {
        for (String network : List.of("mainnet", "preview")) {
            Account account = account(network, index);
            Account before = legacy(network);
            assertEquals(before.baseAddress(), account.baseAddress(), network + " base address moved");
            assertEquals(before.stakeAddress(), account.stakeAddress(), network + " stake address moved");
            assertEquals(paymentKeyHash(before), paymentKeyHash(account), network + " payment key hash moved");
        }
        assertEquals(MAINNET_STAKE_ADDRESS_ACCOUNT_0, account("mainnet", index).stakeAddress());
    }

    @Test
    void indexOneIsAccountOneWithItsOwnStakeKey() {
        for (String network : List.of("mainnet", "preview")) {
            Account zero = account(network, "0");
            Account one = account(network, "1");
            Account expected = Account.createFromMnemonic(networkOf(network), MNEMONIC, 1, 0);

            assertEquals(expected.baseAddress(), one.baseAddress(), network);
            assertEquals(expected.stakeAddress(), one.stakeAddress(), network);
            assertEquals(paymentKeyHash(expected), paymentKeyHash(one), network);

            assertNotEquals(zero.baseAddress(), one.baseAddress(), network + ": account 1 kept account 0's address");
            assertNotEquals(zero.stakeAddress(), one.stakeAddress(),
                    network + ": account 1 kept account 0's STAKE key — the 30k FLDT would be looked up "
                            + "under the wrong stake key");
        }
    }

    /** The address-index constructor: same stake key as account 0, which is exactly the bug. */
    @Test
    @SuppressWarnings("deprecation")
    void indexOneIsNotTheAddressIndexConstructor() {
        for (String network : List.of("mainnet", "preview")) {
            Account addressIndexOne = new Account(networkOf(network), MNEMONIC, 1);
            Account one = account(network, "1");
            assertNotEquals(addressIndexOne.stakeAddress(), one.stakeAddress(),
                    network + ": wallet.account-index derived an ADDRESS index under account 0, not account 1");
            assertNotEquals(addressIndexOne.baseAddress(), one.baseAddress(), network);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483648", "abc", "1.5", "0x1", "1 2"})
    void anInvalidIndexFailsTheBootAndNamesTheSetting(String index) {
        var e = assertThrows(IllegalStateException.class, () -> account("mainnet", index));
        assertTrue(e.getMessage().contains("wallet.account-index"), e.getMessage());
        assertTrue(e.getMessage().contains("WALLET_ACCOUNT_INDEX"), e.getMessage());
        assertTrue(e.getMessage().contains(index.trim()), e.getMessage());
    }

    @Test
    void theParserAcceptsTheWholeNonNegativeIntRange() {
        assertEquals(0, AccountConfig.parseAccountIndex(null));
        assertEquals(0, AccountConfig.parseAccountIndex(""));
        assertEquals(0, AccountConfig.parseAccountIndex("   "));
        assertEquals(7, AccountConfig.parseAccountIndex(" 7 "));
        assertEquals(Integer.MAX_VALUE, AccountConfig.parseAccountIndex("2147483647"));
        assertEquals(Account.createFromMnemonic(Networks.mainnet(), MNEMONIC, Integer.MAX_VALUE, 0).stakeAddress(),
                account("mainnet", "2147483647").stakeAddress());
    }

    /** A blank seed must keep failing the boot as loudly as it did before the setting existed. */
    @Test
    @SuppressWarnings("deprecation")
    void aBlankMnemonicStillFailsAsItDidBefore() {
        for (String blank : new String[]{"", "   "}) {
            var before = assertThrows(RuntimeException.class, () -> new Account(Networks.mainnet(), blank));
            var now = assertThrows(RuntimeException.class, () -> new AccountConfig().account(blank, "mainnet", "0"));
            assertEquals(before.getClass(), now.getClass());
            assertEquals(before.getMessage(), now.getMessage());
        }
    }

    // ── the shipped yaml: does WALLET_ACCOUNT_INDEX reach wallet.account-index? ────────────────────

    private static List<PropertySource<?>> walletDocuments() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        List<PropertySource<?>> withWallet = documents.stream()
                .filter(d -> d.containsProperty("wallet.mnemonic"))
                .toList();
        assertEquals(2, withWallet.size(), "expected the wallet block in the mainnet AND the preview document");
        return withWallet;
    }

    private static String resolve(PropertySource<?> document, Map<String, Object> env) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        environment.getPropertySources().addLast(document);
        return environment.resolvePlaceholders("${wallet.account-index:0}");
    }

    @Test
    void theEnvVarReachesThePropertyInBothProfiles() throws IOException {
        for (PropertySource<?> document : walletDocuments()) {
            assertEquals("3", resolve(document, Map.of("WALLET_ACCOUNT_INDEX", "3")), document.getName());
            assertEquals(0, AccountConfig.parseAccountIndex(resolve(document, Map.of())), document.getName());
            // Compose env_file forwards `WALLET_ACCOUNT_INDEX=` as an empty string: it must mean 0.
            Map<String, Object> empty = new HashMap<>();
            empty.put("WALLET_ACCOUNT_INDEX", "");
            assertEquals(0, AccountConfig.parseAccountIndex(resolve(document, empty)), document.getName());
        }
    }
}
