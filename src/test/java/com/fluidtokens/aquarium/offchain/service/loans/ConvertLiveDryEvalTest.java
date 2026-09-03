package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The first thing in this project that runs the convert EMITTER end to end.</b>
 *
 * <p>Everything before it proved pieces and checks. {@code ConvertTransactionBuilderGuardTest} drives
 * {@code assertStructure} against hand-built bodies, so it tests the checker and says nothing about
 * the producer — a mutant proved that (findings §43.3). <b>This is where a real convert is assembled,
 * balanced, evaluated against the deployed validators, and its ex-units read off the built body.</b>
 *
 * <h2>Why it fetches instead of pinning</h2>
 * The FluidTokens oracle feed carries a <b>fifty-minute</b> validity window. {@code RealLoanDryEvalTest}
 * and {@code LiquidatePayInAdvanceDryEvalTest} pinned a captured payload and were correct for fifty
 * minutes; they have been red ever since (findings §40.3). <b>A pinned oracle payload is a test with a
 * fifty-minute shelf life.</b> So this fetches the feed, the pool and the UTxOs at run time and sets
 * the transaction's validity interval inside the feed's own window.
 *
 * <h2>⚠ Gated on its OWN key, deliberately</h2>
 * {@code BLOCKFROST_MAINNET_KEY}, not {@code BLOCKFROST_KEY}. Sourcing {@code .env.preview} and running
 * the suite must never point a preview key at mainnet — that produced an HTTP 403 read as a code
 * failure once already. One variable per network is the fix.
 *
 * <h2>What this proves, and what it does not</h2>
 * It proves the scripts pass: <b>phase 2</b>, the failure that forfeits collateral. It says nothing
 * about the ledger's phase 1 — fees, min-ada, witness sets, collateral adequacy (CCL trap 11). The
 * in-situ check against the chain's own parameters is the shadow deploy.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_MAINNET_KEY", matches = ".+")
class ConvertLiveDryEvalTest {

    private static final String URL = "https://cardano-mainnet.blockfrost.io/api/v0/";

    // ---- the live candidate (findings §32) --------------------------------------------------------

    private static final String LOAN_TX = "d832b78e3d4a9ff99dfa8f238ae378b37dbd36b30efd24d68e5786f99786cf99";
    private static final int LOAN_IX = 1;
    private static final int BOND_IX = 3;
    /** The asset name shared by the loan NFT and both bonds. */
    private static final String LOAN_ID = "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    /** Both config NFTs were minted in one transaction and have never been spent. */
    private static final String CONFIG_TX = "7b9f20dbadaebe1400915e4a63444a9eb7515c21c1114d4bc9c77f1455148cb0";

    // ---- mainnet deployment coordinates -----------------------------------------------------------

    private static final String CONFIG_POLICY = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    /** ⚠ Stable per network; the pool UTxO inside it is respent on every swap and resolved by NFT. */
    private static final String MS_POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    /** What the evaluator actually said, kept because CCL's wrapper throws it away. */
    private static String lastEvaluatorMessage;

    private static BFBackendService backend() {
        return new BFBackendService(URL, System.getenv("BLOCKFROST_MAINNET_KEY"));
    }

    private static LoansContractRegistry registry() {
        return new LoansContractRegistry(CONFIG_POLICY, LM_CONFIG_POLICY, ASSET_NAME, SMART_TOKENS,
                MS_POOL_POLICY, MS_POOL_SPEND, MS_ORDER_SPEND);
    }

    private static Utxo output(BFBackendService backend, String txHash, int index) throws Exception {
        Result<Utxo> r = backend.getUtxoService().getTxOutput(txHash, index);
        assertTrue(r.isSuccessful(), "could not read " + txHash + "#" + index + ": " + r.getResponse());
        return r.getValue();
    }

    /** ⛔ The pool, located BY ITS LP ASSET at run time. A pinned coordinate is stale within minutes. */
    private static Utxo pool(BFBackendService backend) throws Exception {
        String lpUnit = MS_POOL_POLICY + ConvertTxEncoder.computeLpAssetName(AssetType.ada(), FLDT);
        Result<List<Utxo>> r = backend.getUtxoService().getUtxos(MS_POOL_ADDRESS, lpUnit, 10, 1);
        assertTrue(r.isSuccessful(), "could not list the pool address: " + r.getResponse());
        assertEquals(1, r.getValue().size(),
                "expected exactly one UTxO at the Minswap pool address holding the ADA/FLDT LP asset");
        return r.getValue().get(0);
    }

    /** A synthetic bot wallet — ours, and not a property of the candidate. Every sibling rig does this. */
    private static Utxo wallet(String address) {
        Utxo u = new Utxo();
        u.setTxHash("00".repeat(32));
        u.setOutputIndex(0);
        u.setAddress(address);
        u.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(50_000_000L))));
        return u;
    }

    // ---- the build --------------------------------------------------------------------------------

    private record Built(Transaction transaction, List<Utxo> universe, PlutusScript oracleScript,
                         ConvertOrderPlan plan, BigInteger equity) {
    }

    private Built build(boolean adversarial) throws Exception {
        BFBackendService backend = backend();
        LoansContractRegistry registry = registry();
        CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

        Utxo loanUtxo = output(backend, LOAN_TX, LOAN_IX);
        Utxo bondUtxo = output(backend, LOAN_TX, BOND_IX);
        Utxo configUtxo = output(backend, CONFIG_TX, 0);
        Utxo lmConfigUtxo = output(backend, CONFIG_TX, 1);
        Utxo poolUtxo = pool(backend);

        LoanDatum loan = new LoanDatumConverter().deserialize(loanUtxo.getInlineDatum());
        LenderManagerDatum bond = new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum());
        MinswapPoolDatum poolDatum = new MinswapPoolDatumConverter().deserialize(poolUtxo.getInlineDatum());

        // ⛔ The feed, FETCHED. Its window is fifty minutes and the transaction must sit inside it.
        FluidOracleClient oracles = new FluidOracleClient("https://api.fluidtokens.com/get-oracle-tokens");
        oracles.refresh();
        OracleEntry entry = oracles.findEntry(FLDT)
                .orElseThrow(() -> new IllegalStateException("no mainnet oracle entry for FLDT"));
        assertTrue(entry.usableForLiquidation(),
                "the live FLDT feed is not usable: signatures=" + entry.signatures().size()
                        + " threshold=" + entry.threshold());
        OraclePriceFeed feed = entry.feed();

        // The window: strictly inside the feed's, so retrieve_oracle_data's containment holds.
        long validFromMillis = feed.validFrom() + 1_000L;
        long validToMillis = Math.min(feed.validTo() - 1_000L, validFromMillis + 120_000L);
        assertTrue(validToMillis > validFromMillis,
                "the fetched feed's window has already closed — re-run; it is fifty minutes wide");
        long validFromSlot = converters.time().toSlot(LocalDateTime.ofEpochSecond(
                validFromMillis / 1000L, 0, ZoneOffset.UTC));
        long validToSlot = converters.time().toSlot(LocalDateTime.ofEpochSecond(
                validToMillis / 1000L, 0, ZoneOffset.UTC));

        BigInteger collateralAmount = BigInteger.valueOf(loanUtxo.getAmount().stream()
                .filter(a -> a.getUnit().equalsIgnoreCase(FLDT.toUnit()))
                .findFirst().orElseThrow().getQuantity().longValueExact());
        BigInteger remainingDebt = LoanFinance.remainingDebt(loan, validFromMillis);
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.liquidationMode();
        BigInteger equity = LoanFinance.redeemerEquity(liquidation,
                Rational.fromInt(collateralAmount), Rational.fromInt(remainingDebt),
                OraclePriceFeed.unit(), feed);

        AssetType lenderBond = new AssetType(registry.getLenderBondPolicyId(), LOAN_ID);
        ConvertOrderPlan plan = ConvertOrderPlan.plan(FLDT, AssetType.ada(), collateralAmount, equity,
                adversarial ? remainingDebt.add(BigInteger.ONE) : remainingDebt,
                bond.liquidationFeePerMille().longValueExact(),
                bond.shouldLiquidationConvertToPrincipal(), liquidation.equityInPrincipalCurrency(),
                poolDatum, MS_POOL_POLICY, lenderBond, bond.lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(registry.getAssetManagerSpendScriptHash()),
                LOAN_TX, LOAN_IX);

        String botAddress = AddressProvider.getEntAddress(
                Credential.fromKey(HexUtil.decodeHexString("11".repeat(28))), Networks.mainnet())
                .getAddress();

        ClaimData claim = new ClaimData(liquidation, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                bond.lenderAuth(), equity, LOAN_ID, remainingDebt);

        PlutusScript oracleScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                oracleScriptCbor(backend, entry), PlutusVersion.v3);

        // ⛔ THE UNIVERSE FIRST, then the evaluator over it, then the build. The evaluator has to
        // resolve every input and reference input the builder will name, so it cannot be created from
        // a universe the build produces. And it is the BUILDER's evaluator, not a separate pass: the
        // ex-units asserted later must be the ones that ended up in the body (CCL trap 8).
        List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo,
                poolUtxo, wallet(botAddress)));
        entryReferenceInputs(backend, entry, universe);

        var aiken = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe),
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(
                        backend.getEpochService()),
                EvalFixtures.scriptSupplier(registry, List.of(oracleScript)),
                SlotConfigs.mainnet());

        // ⚑ CCL wraps a failed evaluation as TxBuildException("Error while evaluating script cost")
        // and DROPS the evaluator's message — which names the failing redeemer and the reason. This
        // repo has already paid once for a build failure whose cause never reached the log, so the
        // evaluator is decorated to keep it.
        var evaluator = new com.bloxbean.cardano.client.api.TransactionEvaluator() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, java.util.Set<Utxo> inputs)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                try {
                    Result<List<EvaluationResult>> result = aiken.evaluateTx(cbor, inputs);
                    if (!result.isSuccessful()) {
                        lastEvaluatorMessage = String.valueOf(result.getResponse());
                    }
                    return result;
                } catch (RuntimeException | com.bloxbean.cardano.client.api.exception.ApiException e) {
                    // ⚠ It THROWS as often as it returns unsuccessfully, and CCL's wrapper hides both.
                    StringBuilder chain = new StringBuilder();
                    for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
                        chain.append(t.getClass().getSimpleName()).append(": ")
                             .append(t.getMessage()).append(" | ");
                    }
                    lastEvaluatorMessage = chain.toString();
                    throw e;
                }
            }
        };

        var builder = new ConvertTransactionBuilder(registry, Networks.mainnet(),
                LoanFixtures.utxoSupplier(universe),
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(
                        backend.getEpochService()),
                evaluator);

        var request = new ConvertTransactionBuilder.Request(loanUtxo, bondUtxo, poolUtxo, entry,
                configUtxo, lmConfigUtxo, wallet(botAddress), Map.of(), plan, claim, FLDT, lenderBond,
                loan.repaymentReceipts(), orderAddress(registry, bond), botAddress,
                validFromSlot, validToSlot);

        return new Built(builder.build(request), universe, oracleScript, plan, equity);
    }

    private static String oracleScriptCbor(BFBackendService backend, OracleEntry entry) throws Exception {
        Utxo published = output(backend, entry.referenceScript().getTransactionId(),
                entry.referenceScript().getIndex());
        Result<String> cbor = backend.getScriptService()
                .getPlutusScriptCbor(published.getReferenceScriptHash());
        assertTrue(cbor.isSuccessful(), "could not fetch the oracle script: " + cbor.getResponse());
        return cbor.getValue();
    }

    private static void entryReferenceInputs(BFBackendService backend, OracleEntry entry,
                                             List<Utxo> universe) throws Exception {
        universe.add(output(backend, entry.referenceInput().getTransactionId(),
                entry.referenceInput().getIndex()));
        universe.add(output(backend, entry.referenceScript().getTransactionId(),
                entry.referenceScript().getIndex()));
        if (entry.charlieProviderReferenceInput() != null) {
            universe.add(output(backend, entry.charlieProviderReferenceInput().getTransactionId(),
                    entry.charlieProviderReferenceInput().getIndex()));
        }
    }

    /** {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)}. */
    private static String orderAddress(LoansContractRegistry registry, LenderManagerDatum bond) {
        return AddressProvider.getEntAddress(
                Credential.fromScript(HexUtil.decodeHexString(MS_ORDER_SPEND)),
                Networks.mainnet()).getAddress();
    }

    // ---- the assertions ---------------------------------------------------------------------------

    /** ⛔ THE ONE THIS RIG EXISTS FOR: a real convert, assembled and evaluated. */
    @Test
    void theRealCandidateBuildsAndEveryScriptEvaluates() throws Exception {
        Built built;
        try {
            built = build(false);
        } catch (Exception e) {
            throw new AssertionError("the convert build failed. What the evaluator said: "
                    + lastEvaluatorMessage, e);
        }

        // ⛔ Ex-units off the BUILT, DESERIALISED transaction — never off an evaluator's report. A
        // rig-supplied evaluator makes the report look right while production has none (CCL trap 8).
        Transaction rebuilt = Transaction.deserialize(built.transaction().serialize());
        assertNotNull(rebuilt.getWitnessSet(), "the built transaction has no witness set");
        assertNotNull(rebuilt.getWitnessSet().getRedeemers(), "the built transaction has no redeemers");
        assertFalse(rebuilt.getWitnessSet().getRedeemers().isEmpty());

        for (var r : rebuilt.getWitnessSet().getRedeemers()) {
            assertNotNull(r.getExUnits(), "redeemer " + r.getTag() + ":" + r.getIndex() + " is uncosted");
            assertTrue(r.getExUnits().getSteps().longValue() > 1_000_000L,
                    "redeemer " + r.getTag() + ":" + r.getIndex() + " carries "
                            + r.getExUnits().getMem() + "/" + r.getExUnits().getSteps()
                            + " — cardano-client-lib's placeholder budget, so no evaluator ran");
        }
    }

    /**
     * ⛔ THE ADVERSARIAL CASE. A {@code minimum_receive} one lovelace above {@code remainingDebt} is a
     * value the validator computes for itself, so it MUST fail — and if it does not, the rig is not
     * evaluating what it claims to and every green above is worthless.
     */
    @Test
    void aMinimumReceiveThatDisagreesWithTheValidatorIsREJECTED() {
        // ignoreScriptCostEvaluationError(false) means a failed evaluation ABORTS the build, so the
        // rejection surfaces as a build failure rather than an outcome to inspect.
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> build(true),
                "a perturbed minimum_receive built CLEANLY, so this rig is not running the convert "
                        + "validator at all and its green case proves nothing");
        assertNotNull(e.getMessage());
    }
}
