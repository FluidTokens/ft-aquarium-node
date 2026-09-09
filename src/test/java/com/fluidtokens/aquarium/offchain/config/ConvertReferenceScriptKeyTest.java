package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The convert action's reference-script coordinate — the key without which a convert cannot be
 * built small enough to submit.</b>
 *
 * <p>{@code lm_liquidate_and_convert_action} is a different script from both
 * {@code lm-liquidate-action} (the plain path's) and
 * {@code lm-liquidate-and-pay-in-advance-action}. Left unpublished it travels inline, and a convert
 * already carries four validators plus a Minswap order — the same arithmetic that put a
 * pay-in-advance transaction at 20,548 bytes against a 16,384 limit.
 *
 * <p>FluidTokens has published it on mainnet as part of the verified 27-of-27 set:
 * {@code 56840ffb…#0} (findings §24).
 *
 * <p>⚑ FAB-75 tracks replacing all nine named keys with one coordinate list the chain resolves by
 * {@code referenceScriptHash} — the shape the compound path already uses, where a mislabelled
 * coordinate is not expressible. This adds the one key convert needs rather than blocking on that.
 */
class ConvertReferenceScriptKeyTest {

    /** The coordinate FluidTokens published, verified on mainnet 2026-09-03. */
    private static final String PUBLISHED =
            "56840ffb07ca0ad4e1eb921695bad5d2719f838612008e13bfe7f775933a7def#0";

    private static LiquidationConfiguration configured(String coordinate) {
        var cfg = new LiquidationConfiguration();
        ReflectionTestUtils.setField(cfg, "referenceScriptLmLiquidateAndConvertAction", coordinate);
        cfg.parseReferenceScripts();
        return cfg;
    }

    @Test
    void theConvertCoordinateBindsToItsOwnSlotAndNotToAnotherValidators() {
        LiquidateTransactionBuilder.ReferenceScripts refs =
                configured(PUBLISHED).getReferenceScripts();

        TransactionInput convert = refs.lmLiquidateAndConvertAction();
        assertNotNull(convert, "the convert coordinate did not bind at all");
        assertEquals("56840ffb07ca0ad4e1eb921695bad5d2719f838612008e13bfe7f775933a7def",
                convert.getTransactionId());
        assertEquals(0, convert.getIndex());

        // ⛔ The slot matters as much as the value: nine near-identical keys, and a coordinate landing
        // in a neighbour's slot would reference the WRONG script while looking configured.
        assertNull(refs.lmLiquidateAction(), "the plain path's slot must stay empty");
        assertNull(refs.lmLiquidateAndPayInAdvanceAction(), "pay-in-advance's slot must stay empty");
        assertNull(refs.loanClaimAction());
    }

    /** Empty means "not published, carry it inline" — the same contract as the other eight. */
    @Test
    void anAbsentCoordinateIsNullRatherThanAPlaceholder() {
        assertNull(configured("").getReferenceScripts().lmLiquidateAndConvertAction());
        assertNull(configured(null).getReferenceScripts().lmLiquidateAndConvertAction());
    }

    /** A malformed coordinate names its own key, because nine near-identical lines look alike. */
    @Test
    void aMalformedCoordinateNamesTheKeyItCameFrom() {
        var e = assertThrows(IllegalStateException.class,
                () -> configured("56840ffb07ca0ad4e1eb921695bad5d2719f838612008e13bfe7f775933a7def"));
        assertTrue(e.getMessage().contains("lm-liquidate-and-convert-action"),
                "the rejection must name the key: " + e.getMessage());
    }

    /** {@code none()} still means nothing published, with the new slot included. */
    @Test
    void noneLeavesEverySlotIncludingTheNewOneUnpublished() {
        var none = LiquidateTransactionBuilder.ReferenceScripts.none();
        assertNull(none.lmLiquidateAndConvertAction());
        assertEquals(9, LiquidateTransactionBuilder.ReferenceScripts.class
                .getRecordComponents().length,
                "nine validators have a slot; if this changes, the parse and none() must both follow");
    }
}
