package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ⚙ DERIVE, DON'T TRUST. Applies this node's eleven convert parameters to a candidate blueprint and
 * reports the resulting script hash, so it can be compared against what is actually PUBLISHED at the
 * deployed coordinate before anything is vendored or repointed.
 *
 * <p>A coordinate that resolves proves only that a UTxO exists there. The check that matters is that
 * the blueprint we ship DERIVES the hash the chain publishes — otherwise the node points at a script
 * it cannot reproduce, which is how it once booted clean against a superseded deployment.
 *
 * <p>Disabled unless {@code CANDIDATE_BLUEPRINT} names a plutus.json.
 */
@EnabledIfEnvironmentVariable(named = "CANDIDATE_BLUEPRINT", matches = ".+")
class NewBlueprintDerivesPublishedHashTest {

    @Test
    void deriveConvertActionFromCandidateBlueprint() throws Exception {
        LoansContractRegistry registry = new LoansContractRegistry(
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa",
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");

        JsonNode root = new ObjectMapper().readTree(new File(System.getenv("CANDIDATE_BLUEPRINT")));
        String unapplied = null;
        for (JsonNode v : root.get("validators")) {
            if (v.get("title").asText()
                    .equals("lender_manager/lm_liquidate_and_convert_action.actionValidator.withdraw")) {
                unapplied = v.get("compiledCode").asText();
            }
        }
        assertNotNull(unapplied, "the candidate blueprint has no convert validator");

        ListPlutusData params = ListPlutusData.builder().build();
        for (String hex : new String[]{
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "706172616d6574657273",
                registry.getLenderManagerSpendScriptHash(),
                registry.getAssetManagerSpendScriptHash(),
                registry.getAssetManagerWithdrawScriptHash(),
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c",
                ""}) {
            params.add(BytesPlutusData.of(HexUtil.decodeHexString(hex)));
        }
        params.add(ConstrPlutusData.builder().alternative(1)
                .data(ListPlutusData.of(BytesPlutusData.of(
                        HexUtil.decodeHexString(registry.getLoanClaimActionScriptHash()))))
                .build());

        String applied = AikenScriptUtil.applyParamToScript(params, unapplied);
        String derived = HexUtil.encodeHexString(PlutusBlueprintUtil
                .getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3).getScriptHash());

        String published = System.getenv().getOrDefault("PUBLISHED_HASH", "");
        System.out.println("DERIVED   " + derived);
        System.out.println("PUBLISHED " + published);
        if (!published.isBlank()) {
            assertEquals(published, derived,
                    "the candidate blueprint does NOT derive the published script — vendoring it would "
                            + "point the node at a script it cannot reproduce");
        }
    }
}
