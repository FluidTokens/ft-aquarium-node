package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⛔ <b>The token→ADA pay-in-advance build is byte-identical — pinned by SHA-256.</b>
 * <p>
 * FAB-225 changes {@link LiquidatePayInAdvanceTransactionBuilder} only on the TOKEN-principal path: two
 * oracle legs sharing one withdrawal credential are refused by name, and the principal leg's withdraw-0 is
 * emitted whenever a principal oracle is present. The ruling for this slice is that the ADA-principal path
 * (a token collateral paid out in ADA, {@code principalOracle == null}) stays byte-identical, and until
 * this class nothing pinned its bytes — {@code LiquidatePayInAdvanceDryEvalTest} and
 * {@code PayInAdvanceLiquidationRouterTest} pin its arithmetic and its shape, which a reordered body or a
 * moved redeemer index can survive.
 * <p>
 * Two builds of {@link LiquidatePayInAdvanceDryEvalTest#fixture()} are pinned: the offline build (no
 * evaluator, placeholder ex-units) and the priced build (the real PlutusV3 machine through the
 * offline-with-evaluator constructor), each by the SHA-256 and length of its serialised bytes. Both are
 * deterministic: the fixture is fully recorded or fixed, with no random key anywhere.
 * <p>
 * <b>Either hash changes only if the ADA-principal path's bytes change.</b> A failure here is not a
 * flaky fixture; it is the token→ADA transaction moving. If that move is intended, re-take the pin in
 * the same commit and record the old value with the reason it moved.
 */
class PayInAdvanceAdaPrincipalByteIdentityTest {

    /** Measured at the FAB-225 base: the offline (no-evaluator) build. */
    private static final String OFFLINE_SHA256 = "f6fd7f5057aa7ae2bd4364b59ccd5abec7e7be6a4a7882b2225580668fada910";
    private static final int OFFLINE_BYTES = 24_144;

    /** Measured at the FAB-225 base: the priced build, every redeemer carrying the evaluator's ex-units. */
    private static final String PRICED_SHA256 = "71451d8dc6f0b7d0ec436bc0a0388b47b505efa8ca4b940122cb8600f0c2d529";
    private static final int PRICED_BYTES = 24_172;

    @Test
    void theTokenToAdaPayInAdvanceOfflineBuildIsByteIdentical() throws Exception {
        byte[] bytes = LiquidatePayInAdvanceDryEvalTest.build(LiquidatePayInAdvanceDryEvalTest.fixture()).serialize();

        assertEquals(OFFLINE_BYTES, bytes.length, "the offline token→ADA pay-in-advance build changed length");
        assertEquals(OFFLINE_SHA256, sha256(bytes), "the offline token→ADA pay-in-advance build changed bytes");
    }

    @Test
    void theTokenToAdaPayInAdvancePricedBuildIsByteIdentical() throws Exception {
        LiquidatePayInAdvanceDryEvalTest.Fixture fixture = LiquidatePayInAdvanceDryEvalTest.fixture();
        List<Utxo> universe = LiquidatePayInAdvanceDryEvalTest.universe(fixture);
        AikenTransactionEvaluator aiken = new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(LoanFixtures.registry(),
                        List.of(LiquidatePayInAdvanceDryEvalTest.oracleScript())),
                SlotConfigs.preview());

        byte[] bytes = new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), aiken)
                .build(fixture.request()).serialize();

        assertEquals(PRICED_BYTES, bytes.length, "the priced token→ADA pay-in-advance build changed length");
        assertEquals(PRICED_SHA256, sha256(bytes), "the priced token→ADA pay-in-advance build changed bytes");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
