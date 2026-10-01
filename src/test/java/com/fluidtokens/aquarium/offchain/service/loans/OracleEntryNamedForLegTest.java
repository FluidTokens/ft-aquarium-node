package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ⛔ <b>The one rule every NFT-keyed lookup on a loan's behalf goes through.</b> A loan's datum names an
 * oracle NFT for each leg; the entry under that NFT is that leg's oracle only if it prices the leg's
 * token — {@code is_feed_token_correct} refuses anything else on chain (oracle re-slice, cross-provider
 * finding 2). Used by both routers, the executor's recorded economics, and the readiness figures.
 */
class OracleEntryNamedForLegTest {

    private static final AssetType TOKEN = new AssetType("a".repeat(56), "544f4b");
    private static final AssetType OTHER = new AssetType("e".repeat(56), "544f4b");
    private static final AssetType NFT = new AssetType("c".repeat(56), "6f7261636c65");

    private static OracleEntry pricing(AssetType token) {
        return new OracleEntry(token, NFT, null, null, null, null, List.of(), 0, OraclePriceFeed.unit(),
                List.of(), null);
    }

    @Test
    void theNamedOracleIsReturnedWhenItPricesTheLegsToken() {
        OracleEntry entry = pricing(TOKEN);
        assertSame(entry, OracleEntry.namedForLeg(Map.of(NFT.toUnit(), entry), TOKEN, NFT));
    }

    @Test
    void theNamedOracleIsRefusedWhenItPricesAnotherToken() {
        assertNull(OracleEntry.namedForLeg(Map.of(NFT.toUnit(), pricing(OTHER)), TOKEN, NFT),
                "an oracle for another token is no oracle for this leg");
    }

    @Test
    void anAbsentOrUnnamedOracleIsNull() {
        assertNull(OracleEntry.namedForLeg(Map.of(), TOKEN, NFT));
        assertNull(OracleEntry.namedForLeg(null, TOKEN, NFT));
        assertNull(OracleEntry.namedForLeg(Map.of(NFT.toUnit(), pricing(TOKEN)), TOKEN, null));
    }
}
