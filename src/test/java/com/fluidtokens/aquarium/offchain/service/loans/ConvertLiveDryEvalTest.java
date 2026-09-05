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

    // ⇑ REPOINTED 2026-09-05 at the LIVE candidate. It was d832b78e…#1 (loan 1b6fda50…), which has
    // since been REPAID — its escrow is the 25 ada the compound collected in f2a598a9…. A rig aimed
    // at a settled loan reads a spent output, which Blockfrost answers happily (CCL trap 12:
    // getTxOutput is not an existence check), so it fails for a reason unrelated to the code.
    private static final String LOAN_TX = "4a95a00f8d0ba2d0ab0dca1cbbad9f4dd5aa9600dbcc2174f21f33c1fd12f80a";
    private static final int LOAN_IX = 1;
    private static final int BOND_IX = 3;
    /** The asset name shared by the loan NFT and both bonds. */
    private static final String LOAN_ID = "cae82d7d6cbe064249c49c685267fbe8185ddcb869f35ce9dc13495d";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    /** The main config NFT, minted here and never spent. */
    private static final String CONFIG_TX = "7b9f20dbadaebe1400915e4a63444a9eb7515c21c1114d4bc9c77f1455148cb0";

    /**
     * ⛔ <b>The LM config is NOT at {@code CONFIG_TX#1} any more, and reading it there is silent.</b>
     *
     * <p>Both NFTs were minted in one transaction, and this rig read {@code CONFIG_TX#0} and
     * {@code CONFIG_TX#1} accordingly. FluidTokens then updated the LMConfig <b>in place</b> —
     * {@code 7b9f20db…#1} was consumed by {@code 8296a2fe…}, which recreated it at output 0 with a new
     * field 5 (the convert action, {@code ed8d41e4…} → {@code dc715410…}).
     *
     * <p>⚠ <b>Nothing about reading the old coordinate fails.</b> CCL trap 12: {@code getTxOutput}
     * answers "this output existed" from the creating transaction, and that stays true forever after
     * it is spent. So the rig would have built against the SUPERSEDED datum and refused the convert
     * action the node correctly derives — reproducing, in the rig, the exact production failure this
     * rig exists to diagnose. <b>Same staleness class as the baked reference-script coordinate.</b>
     */
    private static final String LM_CONFIG_TX =
            "8296a2fea4124a23d48dab15b9930731f44174090717d4cbf39e4e1e37364916";
    private static final int LM_CONFIG_IX = 0;

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

    /**
     * ⛔ <b>The body the evaluator actually saw, decoded from the bytes it was handed.</b>
     *
     * <p>With {@code ignoreScriptCostEvaluationError(false)} the build aborts <em>inside</em>
     * {@code context.build()}, so the finished transaction never reaches the caller and a failing
     * redeemer index cannot be matched to a validator from the artefact. The fallback — re-deriving
     * the ledger's ordering by hand — is exactly the step that went wrong once already: sorting reward
     * addresses by their bech32 STRINGS instead of their raw bytes named the wrong validator and cost
     * an hour (findings §44.2).
     *
     * <p><b>The evaluator is handed the CBOR, so it is the one place that can see the real thing.</b>
     * Decoding it here turns "index 3 refused" into "this script at this address refused", with no
     * derivation in between.
     */
    /**
     * ⛔ <b>What the evaluator RESOLVES each reference input to</b> — the step between "the body names
     * this coordinate" and "the validator reads this datum", and the only place a resolution bug can
     * be seen. Printed beside the body because a coordinate that looks right and resolves to the wrong
     * UTxO is invisible in the body alone.
     */
    private static String resolved(java.util.Set<Utxo> inputs) {
        StringBuilder out = new StringBuilder("\n--- what the evaluator can resolve ---\n");
        for (Utxo u : inputs) {
            String datum = u.getInlineDatum() == null ? "(no inline datum)"
                    : u.getInlineDatum().substring(0, Math.min(72, u.getInlineDatum().length())) + "…";
            out.append("  ").append(u.getTxHash(), 0, 12).append('#').append(u.getOutputIndex())
                    .append("  ").append(u.getAddress(), 0, Math.min(20, u.getAddress().length()))
                    .append("…  ").append(datum).append('\n');
        }
        return out.toString();
    }

    private static String describe(byte[] cbor) {
        try {
            Transaction tx = Transaction.deserialize(cbor);
            StringBuilder out = new StringBuilder("\n--- the body the evaluator saw ---\n");
            int i = 0;
            out.append("reference inputs (body order):\n");
            for (var ri : tx.getBody().getReferenceInputs()) {
                out.append("  [").append(i++).append("] ").append(ri.getTransactionId())
                        .append('#').append(ri.getIndex()).append('\n');
            }
            i = 0;
            out.append("withdrawals (body order):\n");
            for (var w : tx.getBody().getWithdrawals()) {
                out.append("  [").append(i++).append("] ").append(w.getRewardAddress()).append('\n');
            }
            i = 0;
            out.append("outputs (body order, with serialised bytes):\n");
            for (var o : tx.getBody().getOutputs()) {
                String bytes;
                try {
                    bytes = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                            com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                                    o.serialize()));
                } catch (Exception e) {
                    bytes = "(unserialisable: " + e + ")";
                }
                out.append("  [").append(i++).append("] ").append(o.getAddress(), 0,
                        Math.min(24, o.getAddress().length())).append("… coin=")
                        .append(o.getValue().getCoin()).append('\n')
                        .append("        ").append(bytes).append('\n');
            }
            if (tx.getWitnessSet() != null && tx.getWitnessSet().getRedeemers() != null) {
                out.append("redeemers:\n");
                for (var r : tx.getWitnessSet().getRedeemers()) {
                    out.append("  ").append(r.getTag()).append(':').append(r.getIndex()).append('\n');
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "\n(could not decode the body the evaluator saw: " + e + ")";
        }
    }

    /** TRACE PROBE state: the deployed convert hash and the traced rebuild's hash. */
    private static String REAL_CONVERT_HASH;
    private static String TRACED_CONVERT_HASH;

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
        injectTracedConvertScript(registry);   // TEMPORARY TRACE PROBE
        CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

        Utxo loanUtxo = output(backend, LOAN_TX, LOAN_IX);
        Utxo bondUtxo = output(backend, LOAN_TX, BOND_IX);
        Utxo configUtxo = output(backend, CONFIG_TX, 0);
        Utxo lmConfigUtxo = output(backend, LM_CONFIG_TX, LM_CONFIG_IX);
        repointConfigDatum(configUtxo);
        repointConfigDatum(lmConfigUtxo);
        Utxo poolUtxo = pool(backend);

        // ⛔ INSTRUMENTATION, not reasoning. Every fixture the validator will read, printed as fetched,
        // BEFORE anything is built. A coordinate that looks right and resolves to the wrong bytes is
        // invisible downstream, and every wrong turn in this investigation came from deriving what the
        // machine could show.
        for (var pair : java.util.List.of(
                java.util.Map.entry("loan     ", loanUtxo), java.util.Map.entry("bond     ", bondUtxo),
                java.util.Map.entry("config   ", configUtxo), java.util.Map.entry("lmConfig ", lmConfigUtxo),
                java.util.Map.entry("POOL     ", poolUtxo))) {
            Utxo u = pair.getValue();
            String d = u.getInlineDatum();
            System.out.println("FIXTURE " + pair.getKey() + " " + u.getTxHash().substring(0, 12) + "#"
                    + u.getOutputIndex() + "  datum=" + (d == null ? "NULL" : d.length() / 2 + "B " + d.substring(0, Math.min(64, d.length())))
                    + "  assets=" + u.getAmount().size());
        }

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
                    lastEvaluatorMessage = chain + "\n" + describe(cbor) + resolved(inputs);
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

    /**
     * {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)} — delegated to
     * <b>production's</b> {@link ConvertLiquidationRouter#minswapOrderAddress}.
     *
     * <p>⛔ This used to build an <b>enterprise</b> address here, ignoring both its arguments while
     * its javadoc claimed the stake credential. The rig therefore could not reproduce the very
     * defect production had, and after production was fixed it held the rig red on its own.
     * <b>A rig that re-implements the thing it is testing is testing itself.</b>
     */
    private static String orderAddress(LoansContractRegistry registry, LenderManagerDatum bond) {
        return ConvertLiquidationRouter.minswapOrderAddress(
                MS_ORDER_SPEND, bond.lenderStakeCredential(), LOAN_ID, Networks.mainnet());
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
     *
     * <p>⚠ <b>MEASURED 2026-09-05: THIS TEST IS CURRENTLY A FALSE GREEN and proves nothing.</b> It
     * asserts only that the build throws. Both this case and the unperturbed one throw at the
     * <b>identical</b> ex-unit budget {@code mem 909363 / cpu 377022438}, so the rejection it
     * observes is the baseline's own unsolved failure, not the perturbation it introduced. A
     * negative test must reject for the RIGHT reason; this one cannot distinguish them while the
     * baseline is red.
     *
     * <p>⇒ <b>When the baseline goes green, re-arm this by asserting the budget DIFFERS from the
     * baseline's</b> — identical budgets are what exposed the defect and are the thing to assert on.
     */
    @Test
    void aMinimumReceiveThatDisagreesWithTheValidatorIsREJECTED() {
        // ignoreScriptCostEvaluationError(false) means a failed evaluation ABORTS the build, so the
        // rejection surfaces as a build failure rather than an outcome to inspect.
        lastEvaluatorMessage = null;
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> build(true),
                "a perturbed minimum_receive built CLEANLY, so this rig is not running the convert "
                        + "validator at all and its green case proves nothing");

        // ⛔ RE-ARMED 2026-09-05. "It threw" is NOT the assertion — that is what made this check
        // vacuous for as long as the unperturbed case was also red: both threw, at the identical
        // budget, and this test was observing the baseline's own failure rather than its own
        // perturbation. A negative test must reject for the RIGHT reason.
        assertNotNull(lastEvaluatorMessage,
                "the build failed before the evaluator ever ran, so this proves nothing about the "
                        + "validator; the rejection must come from EVALUATION, not from assembly");
        assertTrue(lastEvaluatorMessage.contains("EvaluationFailure"),
                "the perturbed build failed for a reason other than script evaluation, so it does "
                        + "not demonstrate that the validator rejects a wrong minimum_receive: "
                        + lastEvaluatorMessage);
    }

    /**
     * TEMPORARY DIAGNOSTIC PROBE — swaps the convert action's bytes for a trace-enabled rebuild of
     * the SAME source sha (bb4349c), applied to the SAME eleven parameters, injected under the REAL
     * hash so every address and index in the body stays correct. Diagnostic only: these bytes are
     * not the deployed script and must never be vendored or submitted.
     */
    @SuppressWarnings("unchecked")
    private static void injectTracedConvertScript(LoansContractRegistry registry) throws Exception {
        String path = System.getenv("TRACED_BLUEPRINT");
        if (path == null) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(new java.io.File(path));
        java.util.Map<String, String> unapplied = new java.util.LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode v : root.get("validators")) {
            unapplied.putIfAbsent(v.get("title").asText().replaceAll("\\.(withdraw|else|spend|mint)$", ""),
                    v.get("compiledCode").asText());
        }

        java.util.Map<String, String> codes = (java.util.Map<String, String>) field("appliedCompiledCode").get(registry);
        SWAPPED.clear();

        // loan_claim_action first: the convert action takes its credential as a PARAMETER, so a
        // traced claim action changes the convert action's hash too.
        String claim = apply(unapplied.get("loan/loan_claim_action.loan_claim_action"),
                bytes(CONFIG_POLICY), bytes(ASSET_NAME),
                bytes(registry.getAssetManagerSpendScriptHash()),
                bytes(registry.getAssetManagerWithdrawScriptHash()));
        String claimHash = hashOf(claim);
        record(registry, codes, "loanClaimActionScriptHash", claimHash, claim);

        String convert = apply(unapplied.get("lender_manager/lm_liquidate_and_convert_action.actionValidator"),
                bytes(CONFIG_POLICY), bytes(ASSET_NAME),
                bytes(registry.getLenderManagerSpendScriptHash()),
                bytes(registry.getAssetManagerSpendScriptHash()),
                bytes(registry.getAssetManagerWithdrawScriptHash()),
                bytes(MS_POOL_POLICY), bytes(MS_POOL_SPEND), bytes(""), bytes(MS_ORDER_SPEND), bytes(""),
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder().alternative(1)
                        .data(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(bytes(claimHash)))
                        .build());
        record(registry, codes, "lmLiquidateAndConvertActionScriptHash", hashOf(convert), convert);
    }

    /** real hash -> traced hash, for every script swapped; config datums are rewritten with these. */
    private static final java.util.Map<String, String> SWAPPED = new java.util.LinkedHashMap<>();

    private static void record(LoansContractRegistry registry, java.util.Map<String, String> codes,
                               String fieldName, String tracedHash, String applied) throws Exception {
        java.lang.reflect.Field f = field(fieldName);
        String real = (String) f.get(registry);
        codes.put(tracedHash, applied);
        f.set(registry, tracedHash);
        SWAPPED.put(real, tracedHash);
        System.out.println("TRACE PROBE: " + fieldName + " " + real + " -> " + tracedHash);
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field f = LoansContractRegistry.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static com.bloxbean.cardano.client.plutus.spec.PlutusData bytes(String hex) {
        return com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private static String apply(String unapplied,
                                com.bloxbean.cardano.client.plutus.spec.PlutusData... params) {
        assertNotNull(unapplied, "traced blueprint is missing a validator");
        var list = com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder().build();
        for (var p : params) {
            list.add(p);
        }
        return com.bloxbean.cardano.aiken.AikenScriptUtil.applyParamToScript(list, unapplied);
    }

    private static String hashOf(String applied) throws Exception {
        return HexUtil.encodeHexString(com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil
                .getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3).getScriptHash());
    }

    /** Config datums name the real hashes; the traced universe must name the traced ones. */
    private static void repointConfigDatum(Utxo utxo) {
        if (SWAPPED.isEmpty() || utxo.getInlineDatum() == null) {
            return;
        }
        String d = utxo.getInlineDatum();
        for (var e : SWAPPED.entrySet()) {
            d = d.replace(e.getKey(), e.getValue());
        }
        utxo.setInlineDatum(d);
    }
}
