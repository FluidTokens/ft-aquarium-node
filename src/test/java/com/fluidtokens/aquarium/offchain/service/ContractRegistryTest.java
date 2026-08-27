package com.fluidtokens.aquarium.offchain.service;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit coverage for {@link ContractRegistry}: constructs it with the real
 * mainnet and preview derivation inputs (matching {@code application.yaml}) and
 * asserts the exposed hex getters equal the committed on-chain script hashes.
 * <p>
 * {@link ContractDerivationTest} proves the same derivation math inline but never
 * references {@link ContractRegistry} itself, so a bug localized to the class
 * (arg-order wiring, wrong Plutus version, mis-aliased getter, wrong blueprint
 * resource) would previously go undetected. These tests close that gap.
 */
class ContractRegistryTest {

    private static final String MAINNET_GENESIS_TX_HASH =
            "45f379b3436263146ab3a5423506ce11555113384d45655b50c77dab8a3473ff";
    private static final Integer MAINNET_GENESIS_OUTPUT_INDEX = 0;
    private static final String MAINNET_STAKING_TOKEN_POLICY =
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e";
    private static final String MAINNET_STAKING_TOKEN_NAME = "0014df10464c4454";
    private static final String MAINNET_EXPECTED_PARAMETERS_HASH =
            "f0e403df77b2bfee7c0799ef927b3763165033cbe38bddc802934883";
    private static final String MAINNET_EXPECTED_STAKER_HASH =
            "bae773ecdbabb746d2dcd7d1630a5761180b19766803b8a65fa52901";
    private static final String MAINNET_EXPECTED_TANK_HASH =
            "f9724c47299e745cb4f50f9d36cbbadcdf87e015a9d99d927dc4e866";

    private static final String PREVIEW_GENESIS_TX_HASH =
            "d35f81f6bc88babe5dcf088e3a800ecbb4d75373df6b144f7561d393cb5d9b2f";
    private static final Integer PREVIEW_GENESIS_OUTPUT_INDEX = 1;
    private static final String PREVIEW_STAKING_TOKEN_POLICY =
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    private static final String PREVIEW_STAKING_TOKEN_NAME = "0014df1074464c4454";
    private static final String PREVIEW_EXPECTED_PARAMETERS_HASH =
            "1dc94155a9550a0a0c9b6a0f4d609265e0e7d385a2e4535c9d4cba76";
    private static final String PREVIEW_EXPECTED_STAKER_HASH =
            "655bacb11218d31f42bb88400cf733846843d0850d8c109396e34ae4";
    private static final String PREVIEW_EXPECTED_TANK_HASH =
            "421e18527ff74170b8c6bd780c71417fa2a70953e150a037a985008f";

    private static AppConfig.AquariumConfiguration configFor(String genesisTxHash, Integer genesisOutputIndex,
                                                               String stakingTokenPolicy, String stakingTokenName) {
        AppConfig.AquariumConfiguration cfg = mock(AppConfig.AquariumConfiguration.class);
        when(cfg.getGenesisTxHash()).thenReturn(genesisTxHash);
        when(cfg.getGenesisOutputIndex()).thenReturn(genesisOutputIndex);
        when(cfg.getStakingTokenPolicy()).thenReturn(stakingTokenPolicy);
        when(cfg.getStakingTokenName()).thenReturn(stakingTokenName);
        return cfg;
    }

    @Test
    void mainnetInputsDeriveCommittedHashes() {
        AppConfig.AquariumConfiguration cfg = configFor(MAINNET_GENESIS_TX_HASH, MAINNET_GENESIS_OUTPUT_INDEX,
                MAINNET_STAKING_TOKEN_POLICY, MAINNET_STAKING_TOKEN_NAME);

        ContractRegistry registry = new ContractRegistry(cfg);

        assertEquals(MAINNET_EXPECTED_PARAMETERS_HASH, registry.getParametersScriptHashHex(), "mainnet H_params");
        assertEquals(MAINNET_EXPECTED_STAKER_HASH, registry.getStakerScriptHashHex(), "mainnet H_staker");
        assertEquals(MAINNET_EXPECTED_TANK_HASH, registry.getTankScriptHashHex(), "mainnet H_tank");
    }

    @Test
    void previewInputsDeriveCommittedHashes() {
        AppConfig.AquariumConfiguration cfg = configFor(PREVIEW_GENESIS_TX_HASH, PREVIEW_GENESIS_OUTPUT_INDEX,
                PREVIEW_STAKING_TOKEN_POLICY, PREVIEW_STAKING_TOKEN_NAME);

        ContractRegistry registry = new ContractRegistry(cfg);

        assertEquals(PREVIEW_EXPECTED_PARAMETERS_HASH, registry.getParametersScriptHashHex(), "preview H_params");
        assertEquals(PREVIEW_EXPECTED_STAKER_HASH, registry.getStakerScriptHashHex(), "preview H_staker");
        assertEquals(PREVIEW_EXPECTED_TANK_HASH, registry.getTankScriptHashHex(), "preview H_tank");
    }

    @Test
    void configNftPolicyIdEqualsParametersHash() {
        AppConfig.AquariumConfiguration cfg = configFor(MAINNET_GENESIS_TX_HASH, MAINNET_GENESIS_OUTPUT_INDEX,
                MAINNET_STAKING_TOKEN_POLICY, MAINNET_STAKING_TOKEN_NAME);

        ContractRegistry registry = new ContractRegistry(cfg);

        assertEquals(MAINNET_EXPECTED_PARAMETERS_HASH, registry.getConfigNftPolicyId());
        assertEquals(registry.getParametersScriptHashHex(), registry.getConfigNftPolicyId());
    }

    /**
     * ⇑ T-066 ROUTE B — the query key, derived and pinned so the measurement is one step whenever it
     * is authorised.
     *
     * <h2>What this is for</h2>
     * T-066 asks whether {@code ScheduledTransactionService:216}'s hardcoded
     * {@code setInputtankindex(ZERO)} is correct, given the ledger re-sorts inputs lexicographically.
     * The measurement that would settle it is a correlation over <b>historical mainnet tank
     * transactions</b>: if the successful ones cluster where {@code wallet.txHash > tank.txHash},
     * the defect is real. That needs one thing this repo had never written down — <b>which address to
     * ask about.</b>
     *
     * <h2>Why there is exactly ONE address</h2>
     * The tank is paid at an <b>enterprise</b> address ({@code AddressProvider.getEntAddress}, see
     * {@code MainnetTankTest:114}) — no staking part, so the script does not spread across a family
     * of addresses the way a base-address contract would. One credential, one address, and every
     * public chain API can be asked about it directly.
     *
     * <h2>⚠ This is arithmetic, not an action</h2>
     * Derived entirely from {@code aquarium-sc.plutus.json} and the four mainnet values in
     * {@code application.yaml}, both already committed. <b>No socket is opened, no endpoint is named,
     * no profile is set.</b> Recording it is PREPARATION; running the query against mainnet data is a
     * separate decision that is Giovanni's, and nothing here performs it.
     */
    @Test
    void mainnetTankEnterpriseAddressIsTheT066QueryKey() {
        AppConfig.AquariumConfiguration cfg = configFor(MAINNET_GENESIS_TX_HASH, MAINNET_GENESIS_OUTPUT_INDEX,
                MAINNET_STAKING_TOKEN_POLICY, MAINNET_STAKING_TOKEN_NAME);

        ContractRegistry registry = new ContractRegistry(cfg);

        String address = com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                        com.bloxbean.cardano.client.address.Credential.fromScript(
                                registry.getTankScriptHash()),
                        com.bloxbean.cardano.client.common.model.Networks.mainnet())
                .getAddress();

        // The address is not an independent fact — it is the tank hash in another encoding. Asserting
        // BOTH, and that one contains the other, is what makes a transcription error here impossible
        // to mistake for a derivation change.
        assertEquals(MAINNET_EXPECTED_TANK_HASH, registry.getTankScriptHashHex(), "mainnet H_tank");
        assertEquals(MAINNET_TANK_ENTERPRISE_ADDRESS, address,
                "the mainnet tank enterprise address changed — if H_tank is unchanged above, this is a "
                        + "transcription error, not a derivation one");
    }

    /**
     * The bech32 form of {@link #MAINNET_EXPECTED_TANK_HASH} as an enterprise address. Pinned rather
     * than only computed, so the value can be read straight out of this file by whoever runs the
     * T-066 correlation without needing to execute anything.
     */
    private static final String MAINNET_TANK_ENTERPRISE_ADDRESS =
            "addr1w8uhynz89x08gh95758e6dkthtwdlplqzk5an8vj0hzwsesq3894w";
}
