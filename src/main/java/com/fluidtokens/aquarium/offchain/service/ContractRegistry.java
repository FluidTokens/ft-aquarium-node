package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Derives the applied Aquarium script hashes at startup by applying the active
 * network's parameters to the <em>unapplied</em> {@code ft-cardano-aquarium-sc}
 * blueprint ({@code aquarium-sc.plutus.json}).
 * <p>
 * This replaces the previous approach of bundling a pre-applied, network-specific
 * blueprint (which forced a separate git branch per network). The per-network
 * derivation inputs come from configuration ({@code aquarium.genesis.*} and
 * {@code aquarium.staking.token.*}), selected by the active Spring profile.
 * <p>
 * The derivation chain (proven against the live hashes in
 * {@code ContractDerivationTest}):
 * <pre>
 *   parameters(tx0, index0)                          -> H_params (= config-NFT policy)
 *   staker(H_params, fldt_policy, fldt_asset)         -> H_staker
 *   tank(stakingContract=H_staker, H_params)          -> H_tank
 * </pre>
 * The committed {@code compiledCode} is used as-is and only parameterised — never
 * recompiled — so the aiken compiler version is irrelevant to correctness.
 */
@Service
@Getter
@Slf4j
public class ContractRegistry {

    private static final String BLUEPRINT_RESOURCE = "aquarium-sc.plutus.json";

    private final PlutusScript parametersScript;
    private final PlutusScript stakerScript;
    private final PlutusScript tankScript;

    private final byte[] parametersScriptHash;
    private final byte[] stakerScriptHash;
    private final byte[] tankScriptHash;

    private final String parametersScriptHashHex;
    private final String stakerScriptHashHex;
    private final String tankScriptHashHex;

    @SneakyThrows
    public ContractRegistry(AppConfig.AquariumConfiguration cfg) {
        Map<String, String> code = loadUnappliedCompiledCodes();

        // parameters(tx0, index0) -> H_params ; the parameters minting policy id equals this hash.
        this.parametersScript = apply(code.get("parameters.parameters.spend"),
                BytesPlutusData.of(HexUtil.decodeHexString(cfg.getGenesisTxHash())),
                BigIntPlutusData.of(BigInteger.valueOf(cfg.getGenesisOutputIndex())));
        this.parametersScriptHash = parametersScript.getScriptHash();

        // staker(params_contract_hash=H_params, fldt_policy, fldt_asset) -> H_staker
        this.stakerScript = apply(code.get("staker.staker.spend"),
                BytesPlutusData.of(parametersScriptHash),
                BytesPlutusData.of(HexUtil.decodeHexString(cfg.getStakingTokenPolicy())),
                BytesPlutusData.of(HexUtil.decodeHexString(cfg.getStakingTokenName())));
        this.stakerScriptHash = stakerScript.getScriptHash();

        // tank(stakingContract=H_staker, params_contract_hash=H_params) -> H_tank
        this.tankScript = apply(code.get("tank.tank.spend"),
                BytesPlutusData.of(stakerScriptHash),
                BytesPlutusData.of(parametersScriptHash));
        this.tankScriptHash = tankScript.getScriptHash();

        this.parametersScriptHashHex = HexUtil.encodeHexString(parametersScriptHash);
        this.stakerScriptHashHex = HexUtil.encodeHexString(stakerScriptHash);
        this.tankScriptHashHex = HexUtil.encodeHexString(tankScriptHash);

        log.info("Derived Aquarium contract hashes -> parameters(configNFT policy)={}, staker={}, tank={}",
                parametersScriptHashHex, stakerScriptHashHex, tankScriptHashHex);
    }

    /** Config-NFT policy id equals the parameters validator hash. */
    public String getConfigNftPolicyId() {
        return parametersScriptHashHex;
    }

    @SneakyThrows
    private static PlutusScript apply(String unappliedCompiledCode, PlutusData... params) {
        ListPlutusData list = ListPlutusData.builder().build();
        for (PlutusData p : params) {
            list.add(p);
        }
        String appliedCode = AikenScriptUtil.applyParamToScript(list, unappliedCompiledCode);
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(appliedCode, PlutusVersion.v3);
    }

    private static Map<String, String> loadUnappliedCompiledCodes() throws Exception {
        Map<String, String> m = new HashMap<>();
        try (InputStream is = new ClassPathResource(BLUEPRINT_RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(is);
            for (JsonNode v : root.get("validators")) {
                m.put(v.get("title").asText(), v.get("compiledCode").asText());
            }
        }
        return m;
    }
}
