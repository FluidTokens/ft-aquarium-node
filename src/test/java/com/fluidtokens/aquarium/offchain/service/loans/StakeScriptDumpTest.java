package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ⚙ OPERATIONAL HELPER, not a check. Writes the applied withdraw-script bodies as cardano-cli
 * text-envelope files so a reward account can be REGISTERED on chain.
 *
 * <p>A withdraw-zero invocation is only valid from a reward account that exists. FluidTokens
 * registered 25 of them in the deployment transaction
 * {@code 7b9f20db…} (2 ada deposit each, and — visible in that transaction's 2 redeemers against 25
 * certificates — <b>registering a script credential needs no script witness</b>). The convert-family
 * scripts postdate that batch and were never registered, so every convert fails at submit with
 * {@code ConwayWithdrawalsMissingAccounts}.
 *
 * <p>Disabled unless {@code DUMP_STAKE_SCRIPTS} names an output directory. It builds nothing,
 * submits nothing, and touches no key.
 */
@EnabledIfEnvironmentVariable(named = "DUMP_STAKE_SCRIPTS", matches = ".+")
class StakeScriptDumpTest {

    @Test
    void writeTheUnregisteredConvertFamilyScripts() throws Exception {
        Path out = Path.of(System.getenv("DUMP_STAKE_SCRIPTS"));
        Files.createDirectories(out);
        // The shipped mainnet coordinates, same as ConvertLiveDryEvalTest's.
        LoansContractRegistry registry = new LoansContractRegistry(
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa",
                "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
                "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
                "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");

        write(out, "lm-liquidate-and-convert-action",
                registry.getLmLiquidateAndConvertActionScript());
        write(out, "lm-liquidate-convert-and-compound-action",
                scriptOf(registry, "lmLiquidateConvertAndCompoundActionScriptHash"));
    }

    private static void write(Path dir, String name, PlutusScript script) throws Exception {
        assertNotNull(script, name + " could not be derived");
        String cbor = HexUtil.encodeHexString(script.serialize());
        Files.writeString(dir.resolve(name + ".plutus"), """
                {
                    "type": "PlutusScriptV3",
                    "description": "%s (applied) — for a stake registration certificate only",
                    "cborHex": "%s"
                }
                """.formatted(name, cbor));
        System.out.println("wrote " + name + ".plutus  hash="
                + HexUtil.encodeHexString(script.getScriptHash()));
    }

    /** The convert-and-compound action has no public getter; its hash field does. */
    private static PlutusScript scriptOf(LoansContractRegistry registry, String hashField)
            throws Exception {
        var f = LoansContractRegistry.class.getDeclaredField(hashField);
        f.setAccessible(true);
        String hash = (String) f.get(registry);
        assertNotNull(hash, hashField + " is not derived on this configuration");
        Method m = LoansContractRegistry.class.getDeclaredMethod("scriptOf", String.class);
        m.setAccessible(true);
        PlutusScript script = (PlutusScript) m.invoke(registry, hash);
        assertEquals(hash, HexUtil.encodeHexString(script.getScriptHash()));
        return script;
    }
}
