package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⛔ <b>{@code LenderManagerAction}'s constructor indices, pinned against the deployed blueprint.</b>
 *
 * <p>{@code lender_manager.ak}'s withdraw handler reads this action, uses it to pick one of seven
 * action-script hashes out of the LM config datum, and then <b>requires that script's withdrawal to be
 * present in the transaction</b>. Naming the wrong action therefore produces a well-formed redeemer
 * that sends the validator hunting for a script the transaction does not withdraw through — and it
 * refuses with nothing in the failure that says "wrong action".
 *
 * <p>⚑ <b>This is not hypothetical.</b> The convert builder called
 * {@code LiquidationTxEncoder.lenderManagerWithdrawRedeemer(index)}, whose one-argument form hard-codes
 * {@code Liquidate}, and the live dry-eval failed at exactly that withdrawal. The pay-in-advance
 * builder had already hit it, written its own inline copy with constructor 3, and left a javadoc
 * saying so — <b>the warning existed, in a sibling, in writing, and reuse still went to the name
 * rather than the note</b> (findings §46).
 */
class LenderManagerActionSchemaTest {

    /** The ordinal IS the constructor index, so the enum's declaration order is load-bearing. */
    @Test
    void theEnumsOrderMatchesTheDeployedBlueprint() {
        List<String> blueprint = variants("fluidtokens/types/lender_manager/LenderManagerAction");

        assertEquals(List.of("WithdrawBonds", "Liquidate", "Compound", "LiquidateAndPayInAdvance",
                        "LiquidateAndConvert", "LiquidatePayInAdvanceAndCompound",
                        "LiquidateConvertAndCompound"),
                blueprint,
                "the contract's action list changed; every builder's redeemer moves with it");

        assertEquals(blueprint.size(), LiquidationTxEncoder.LenderManagerAction.values().length,
                "the enum and the contract disagree on how many actions exist");
    }

    /** Each action encodes to its own index — the property a wrong one silently violates. */
    @Test
    void eachActionEncodesToItsBlueprintIndex() {
        var actions = LiquidationTxEncoder.LenderManagerAction.values();
        for (int i = 0; i < actions.length; i++) {
            var redeemer = (ConstrPlutusData) LiquidationTxEncoder
                    .lenderManagerWithdrawRedeemer(7L, actions[i]);
            var action = (ConstrPlutusData) redeemer.getData().getPlutusDataList().get(1);
            assertEquals(i, action.getAlternative(), actions[i] + " encodes to the wrong constructor");
        }
    }

    /**
     * ⚠ The one-argument overload is PLAIN {@code Liquidate}, and this pins that so nobody "fixes" it
     * into something more helpful. It is right for {@code LiquidateTransactionBuilder} and wrong
     * everywhere else; the two-argument form is the one that cannot be got wrong by omission.
     */
    @Test
    void theOneArgumentOverloadIsPlainLiquidateAndNothingElse() {
        var oneArg = (ConstrPlutusData) LiquidationTxEncoder.lenderManagerWithdrawRedeemer(7L);
        var explicit = (ConstrPlutusData) LiquidationTxEncoder.lenderManagerWithdrawRedeemer(7L,
                LiquidationTxEncoder.LenderManagerAction.LIQUIDATE);

        assertEquals(explicit.serializeToHex(), oneArg.serializeToHex());
        assertEquals(1, ((ConstrPlutusData) oneArg.getData().getPlutusDataList().get(1))
                .getAlternative());
    }

    private static List<String> variants(String definition) {
        try (InputStream is = LenderManagerActionSchemaTest.class
                .getResourceAsStream("/loans-v4.plutus.json")) {
            JsonNode defs = new ObjectMapper().readTree(Objects.requireNonNull(is)).get("definitions");
            var titles = new ArrayList<String>();
            defs.get(definition).get("anyOf").forEach(c -> titles.add(c.get("title").asText()));
            return titles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
