package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The TOKEN-PRINCIPAL sibling of {@link ConvertLiveDryEvalTest}</b> — same shape, same
 * discipline, driving {@link LiquidatePayInAdvanceTransactionBuilder} for a loan whose principal is
 * USDM (not ada), so that {@code request.principalOracle()} (the token-principals slice, WALLS 1-4)
 * is exercised against the real deployed PlutusV3 machine with <b>two real oracle legs</b>.
 *
 * <h2>Why this had to be a LIVE rig</h2>
 * Findings §59.6 (docs/lending-v4-findings.md:5558-5601): the offline rigs cannot synthesise a second
 * real oracle asset. {@code charlie_specs}/{@code verification_keys} are closed validator parameters
 * and each priced asset is its own compiled oracle script ({@code retrieve_oracle_data} resolves its
 * feed with {@code pairs.get_first(self.redeemers, Withdraw(oraclePaymentCredential))} — one redeemer
 * per credential). This repo only ever had real chain coordinates for ONE deployed oracle asset
 * offline. The live mainnet chain has both FLDT's and USDM's oracles deployed for real, so only a
 * live rig can drive both legs through the real machine at once.
 *
 * <h2>⚠ Gated on its OWN key, deliberately</h2>
 * {@code BLOCKFROST_MAINNET_KEY}, not {@code BLOCKFROST_KEY} — same reasoning as
 * {@link ConvertLiveDryEvalTest}: a preview key pointed at mainnet reads as a code failure (HTTP 403),
 * not a config problem.
 *
 * <h2>⛔ READ-ONLY. NOTHING IS SUBMITTED.</h2>
 * Every {@link BFBackendService} here is constructed from a Blockfrost key with read scope only. The
 * production constructor of {@link LiquidatePayInAdvanceTransactionBuilder} used below returns an
 * <b>unsigned</b> {@link Transaction} — there is no signer, no key, no {@code complete()}/submit call
 * anywhere in this file.
 *
 * <h2>What this proves, and what it does not</h2>
 * It proves the scripts pass — phase 2, the failure that forfeits collateral — for the token-principal
 * shape, for real, against both real oracles. It says nothing about the ledger's phase 1 (fees,
 * min-ada, witness sets, collateral adequacy — CCL trap 11).
 *
 * <h2>⛔ PARKED — FAB-86, ruling of 2026-09-10. The failures are the FIXTURE'S ABSENCE.</h2>
 * <ol>
 *   <li>The candidate pinned below, loan {@code 279499ff77d9c8efacda5940a06659f091e1aaa15ce929f779d8d3fd},
 *       <b>was liquidated by this very bot</b> as {@code fa16e8eb0376…} in block 13,921,999. The loan
 *       reference input {@code 0d080eb2…#1} is no longer in the live UTxO set, and {@link #BOT_WALLET}
 *       no longer carries the USDM because the bot spent it in that same liquidation.</li>
 *   <li>So with {@code BLOCKFROST_MAINNET_KEY} set this class was 3 tests / 3 failures — a <b>stale
 *       fixture, not a code fault</b>. Mainnet is healthy and the bot is live and armed. <b>Do not
 *       "repair" these failures</b>: there is nothing here to fix, and re-pinning to whatever loan
 *       happens to be liquidatable today is the wrong reflex (see below).</li>
 *   <li>Re-enabling it takes a live liquidatable loan <em>of a scale that actually exercises</em>
 *       wallet sizing, the balance check and {@code POOL_TOO_THIN}. The only other USDM loan on chain
 *       today is <b>10 USDM at 71% LTV</b> and exercises none of the three — a green rig that proves
 *       nothing is worse than a disabled one. The durable answer is the offline synthetic-oracle rig,
 *       which is FAB-86's own future work and deliberately not this change.</li>
 * </ol>
 * The coordinates below are left pinned to the dead loan on purpose: they are the record of what was
 * last proven on mainnet, so re-enabling means replacing them consciously rather than inheriting them.
 *
 * <h3>Why a second gate rather than {@code @Disabled}</h3>
 * The CI run summary lists, for every class that did not run, the environment variables it was waiting
 * for. Under {@code @Disabled} this class would still scan as <em>"waiting on BLOCKFROST_MAINNET_KEY"</em>
 * — a deliberate park reported as a rig waiting for a credential, which is exactly the
 * skip-that-reads-as-a-pass this repo has already been bitten by. A second gate named
 * {@code AQUARIUM_ANTICIPATE_RIG_CANDIDATE} makes the summary line read
 * <em>"waiting on AQUARIUM_ANTICIPATE_RIG_CANDIDATE, BLOCKFROST_MAINNET_KEY"</em>: self-describing, and
 * credential-independent by construction. The variable is deliberately <b>not</b> defined in
 * {@code .env.mainnet} — defining it would re-enable the rig and undo the ruling — which is why
 * {@code EnvGatedRigReachabilityTest} carries a narrow, named exemption for this one class.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_MAINNET_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_ANTICIPATE_RIG_CANDIDATE", matches = ".+",
        disabledReason = "FAB-86, parked 2026-09-10: the pinned candidate 279499ff was liquidated by "
                + "this bot as fa16e8eb in block 13,921,999, so the loan reference input and the "
                + "wallet's USDM are both gone. The 3 failures are a STALE FIXTURE, not a code fault "
                + "— do not repair them. Re-enable only with a live liquidatable loan large enough to "
                + "exercise wallet sizing, the balance check and POOL_TOO_THIN; the only other USDM "
                + "loan today (10 USDM at 71% LTV) exercises none of them, and a green rig that "
                + "proves nothing is worse than a disabled one.")
class LiquidatePayInAdvanceLiveDryEvalTest {

    private static final String URL = "https://cardano-mainnet.blockfrost.io/api/v0/";
    private static final String ORACLE_REGISTRY_URL = "https://api.fluidtokens.com/get-oracle-tokens";

    // ---- the pinned live candidate, verified on chain 2026-09-09 ----------------------------------

    private static final String LOAN_TX =
            "0d080eb21bf4951af926acdab34a5b12b0aaf4c62c52bc6b999fc849967f6c4a";
    private static final int LOAN_IX = 1;
    private static final int BOND_IX = 3;
    private static final String LOAN_ID = "279499ff77d9c8efacda5940a06659f091e1aaa15ce929f779d8d3fd";

    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final AssetType USDM =
            new AssetType("c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad", "0014df105553444d");

    /**
     * The bot wallet, public. Holds USDM in a multi-asset UTxO — set {@code REAL_BOT_WALLET} to a
     * different address to exercise a different wallet's shape; this is the default because the
     * whole point of this rig is the token-aware selection from slice 3 against the REAL shape.
     */
    private static final String BOT_WALLET =
            "addr1q8kfqpcpm3c77sstcf9a5mzgfa0eya5rd2h8838hpd75fymg9lkpepnud2jejx80dujud0wn3sw86q7hrs95lg3utwkqvd5zcd";

    // ---- the main/LM config — the SAME global config ConvertLiveDryEvalTest pins and verifies ------

    private static final String CONFIG_TX =
            "7b9f20dbadaebe1400915e4a63444a9eb7515c21c1114d4bc9c77f1455148cb0";
    private static final int CONFIG_IX = 0;
    private static final String LM_CONFIG_TX =
            "78d4a273b15382a671bb04fe647a9b621665427f1405e3903817beecfde35bfa";
    private static final int LM_CONFIG_IX = 0;

    // ---- mainnet deployment coordinates (same registry ConvertLiveDryEvalTest derives) -------------

    private static final String CONFIG_POLICY = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    // ---- the two oracle deployments, for the TICKET's cross-check (test 2) ------------------------
    // ⚠ Fetched live via FluidOracleClient by TOKEN for the actual build — these constants are only
    // a cross-check that the live registry still publishes what the ticket pinned 2026-09-09.

    private static final String ORACLE_POLICY = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b";
    private static final AssetType FLDT_ORACLE = new AssetType(ORACLE_POLICY, "6f7261636c65464c44544333");
    private static final AssetType USDM_ORACLE = new AssetType(ORACLE_POLICY, "6f7261636c655553444d4333");
    private static final TransactionInput FLDT_ORACLE_REF_INPUT = new TransactionInput(
            "e874273fd5a4765920837d3ec0d0a3bdcb6e689911335de971a38b5ca4a881f5", 0);
    private static final TransactionInput FLDT_ORACLE_REF_SCRIPT = new TransactionInput(
            "e5943f9241ceaaae437c35d8d5e769cbae60e2eebb0f5077ac9ebd54aa23a3fb", 0);
    private static final TransactionInput USDM_ORACLE_REF_INPUT = new TransactionInput(
            "5f6bbacd3da81e917812049192fffba44e0c6b2bbd397f8c2a296d7afbee9d6b", 0);
    private static final TransactionInput USDM_ORACLE_REF_SCRIPT = new TransactionInput(
            "2d557048a3750f00549641048cd82081a8f033479c5d0454886b224675d2c975", 1);

    // ---- operator defaults, mirrored from application.yaml's mainnet document ----------------------

    /** {@code loans.liquidation.oracle-window-margin-seconds}, mainnet default 30. */
    private static final long ORACLE_WINDOW_MARGIN_MILLIS = 30_000L;
    /** {@code loans.liquidation.validity-window-seconds}, mainnet default 120. */
    private static final long VALIDITY_WINDOW_MILLIS = 120_000L;
    /** {@code LiquidationExecutor.TOKEN_PRINCIPAL_MIN_ADA_CEILING} — mirrored, not imported (private there). */
    private static final BigInteger TOKEN_PRINCIPAL_MIN_ADA_CEILING = BigInteger.valueOf(2_000_000L);

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

    private static List<Utxo> liveWalletUtxos(BFBackendService backend, String address) throws Exception {
        Result<List<Utxo>> r = backend.getUtxoService().getUtxos(address, 100, 1);
        assertTrue(r.isSuccessful(), "could not read the wallet's UTxOs: " + r.getResponse());
        List<Utxo> all = new ArrayList<>(r.getValue());
        assertFalse(all.isEmpty(), "the wallet at " + address + " holds no UTxOs");
        System.out.println("REAL WALLET: " + all.size() + " UTxOs at " + address);
        return all;
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
     * The six shipped reference-script coordinates THIS builder honours (mirrors {@code attachValidators}
     * / {@code publishedScripts} in {@link LiquidatePayInAdvanceTransactionBuilder}): loan, loan-spend,
     * lender-manager, lender-manager-spend, loan-claim-action, and — unlike the convert rig —
     * {@code lm-liquidate-and-pay-in-advance-action} rather than {@code lm-liquidate-and-convert-action}.
     * Read from {@code application.yaml}, not copied from it (same reasoning {@link ConvertLiveDryEvalTest}
     * documents): a coordinate move in config cannot leave this rig behind.
     */
    private static LiquidateTransactionBuilder.ReferenceScripts referenceScripts() {
        return new LiquidateTransactionBuilder.ReferenceScripts(
                shippedRef("loan"), shippedRef("loan-spend"), shippedRef("lender-manager"),
                shippedRef("lender-manager-spend"), shippedRef("loan-claim-action"),
                null, null, shippedRef("lm-liquidate-and-pay-in-advance-action"), null);
    }

    private static List<TransactionInput> referenceScriptCoordinates(
            LiquidateTransactionBuilder.ReferenceScripts scripts) {
        return Stream.of(scripts.loan(), scripts.loanSpend(), scripts.lenderManager(),
                        scripts.lenderManagerSpend(), scripts.loanClaimAction(),
                        scripts.lmLiquidateAndPayInAdvanceAction())
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The {@code txHash#index} default the MAINNET document ships for one liquidation reference
     * script. Only the mainnet document is consulted — the preview one deliberately blanks these.
     * Mirrors {@link ConvertLiveDryEvalTest#shippedRef}.
     */
    private static TransactionInput shippedRef(String key) {
        String yaml;
        try {
            yaml = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/application.yaml"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read application.yaml", e);
        }
        int preview = yaml.indexOf("on-profile: preview");
        String mainnet = preview > 0 ? yaml.substring(0, preview) : yaml;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key)
                        + ":\\s*\\$\\{[A-Z_]+:([0-9a-f]{64})#(\\d+)}\\s*$")
                .matcher(mainnet);
        assertTrue(m.find(), "application.yaml's mainnet document ships no reference-script "
                + "coordinate for '" + key + "'; the rig reads what production reads, so a missing "
                + "key here is a real configuration gap rather than a test problem");
        return TransactionInput.builder()
                .transactionId(m.group(1)).index(Integer.parseInt(m.group(2))).build();
    }

    /** Mirrors {@code PayInAdvanceLiquidationRouter.validitySlots} — inward clamp, same rule. */
    private static long[] validitySlots(CardanoConverters converters, long validFromMillis, long validToMillis) {
        long slotFrom = converters.time().toSlot(utc(validFromMillis));
        if (millisOf(converters.slot().slotToTime(slotFrom)) < validFromMillis) {
            slotFrom += 1;
        }
        long slotTo = converters.time().toSlot(utc(validToMillis));
        if (millisOf(converters.slot().slotToTime(slotTo)) > validToMillis) {
            slotTo -= 1;
        }
        return new long[]{slotFrom, slotTo};
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---- the build ----------------------------------------------------------------------------------

    private record Built(Transaction transaction, List<Utxo> universe, List<PlutusScript> oracleScripts,
                         LiquidatePayInAdvanceTransactionBuilder.Request request,
                         LiquidatePayInAdvanceTransactionBuilder.Numbers numbers,
                         LoansContractRegistry registry, TransactionEvaluator evaluator) {
    }

    private Built build() throws Exception {
        BFBackendService backend = backend();
        LoansContractRegistry registry = registry();
        CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

        Utxo loanUtxo = output(backend, LOAN_TX, LOAN_IX);
        Utxo bondUtxo = output(backend, LOAN_TX, BOND_IX);
        Utxo configUtxo = output(backend, CONFIG_TX, CONFIG_IX);
        Utxo lmConfigUtxo = output(backend, LM_CONFIG_TX, LM_CONFIG_IX);

        LoanDatum loanDatum = new LoanDatumConverter().deserialize(loanUtxo.getInlineDatum());
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum());
        assertEquals(USDM, loanDatum.principalAsset(), "the pinned loan's principal is not USDM");
        assertEquals(FLDT, loanDatum.collateral().assetType(), "the pinned loan's collateral is not FLDT");
        assertTrue(bondDatum.shouldLiquidationConvertToPrincipal(),
                "the pinned bond is not a pay-in-advance (convert) bond");
        var liquidation = (com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode.Liquidation)
                loanDatum.liquidationMode();

        BigInteger collateralAmount = BigInteger.valueOf(loanUtxo.getAmount().stream()
                .filter(a -> a.getUnit().equalsIgnoreCase(FLDT.toUnit()))
                .findFirst().orElseThrow(() -> new IllegalStateException("loan utxo carries no FLDT"))
                .getQuantity().longValueExact());
        BigInteger loanLovelace = loanUtxo.getAmount().stream()
                .filter(a -> a.getUnit().equalsIgnoreCase("lovelace"))
                .findFirst().map(Amount::getQuantity).orElse(BigInteger.ZERO);

        Loan loan = new Loan(LOAN_TX, LOAN_IX, loanUtxo.getAddress(), LOAN_ID, collateralAmount, loanLovelace,
                loanDatum);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_IX, bondUtxo.getAddress(), LOAN_ID,
                bondUtxo.getInlineDatum(), bondDatum);

        // ⛔ The two real oracle legs, FETCHED by TOKEN — the same FluidOracleClient the convert rig
        // uses. Both are `preferredOracle: multisig` on mainnet (not c3, unlike preview); the client
        // resolves whatever variant the registry publishes and the builder does what it does for it.
        FluidOracleClient oracles = new FluidOracleClient(ORACLE_REGISTRY_URL);
        oracles.refresh();
        OracleEntry fldtEntry = oracles.findEntry(FLDT)
                .orElseThrow(() -> new IllegalStateException("no mainnet oracle entry for FLDT"));
        OracleEntry usdmEntry = oracles.findEntry(USDM)
                .orElseThrow(() -> new IllegalStateException("no mainnet oracle entry for USDM"));
        assertTrue(fldtEntry.usableForLiquidation(),
                "the live FLDT feed is not usable: signatures=" + fldtEntry.signatures().size()
                        + " threshold=" + fldtEntry.threshold());
        assertTrue(usdmEntry.usableForLiquidation(),
                "the live USDM feed is not usable: signatures=" + usdmEntry.signatures().size()
                        + " threshold=" + usdmEntry.threshold());
        // WALL 3's precondition: a SECOND withdraw-0 only fires when the two oracle credentials
        // differ. If they ever collided this rig would silently stop exercising the thing it exists
        // for, so refuse loudly rather than build a single-oracle transaction under a two-oracle name.
        assertFalse(fldtEntry.rewardAddress().equals(usdmEntry.rewardAddress()),
                "FLDT and USDM share one oracle credential on mainnet today — WALL 3's second "
                        + "withdraw-0 would be skipped and this rig would prove nothing about it");

        // The window: strictly inside BOTH feeds' windows, with the operator's margin left over both,
        // then clamped inward to whole slots exactly as PayInAdvanceLiquidationRouter does — the
        // builder's V3 check reads request.validToMillis(), which must be the POSIX time
        // validToSlot converts back to, so the on-chain recomputation matches.
        long validFromRaw = Math.max(fldtEntry.feed().validFrom(), usdmEntry.feed().validFrom()) + 2_000L;
        long feedValidToMin = Math.min(fldtEntry.feed().validTo(), usdmEntry.feed().validTo());
        long validToRaw = Math.min(feedValidToMin - ORACLE_WINDOW_MARGIN_MILLIS - 5_000L,
                validFromRaw + VALIDITY_WINDOW_MILLIS);
        assertTrue(validToRaw > validFromRaw,
                "the fetched feeds' windows do not overlap enough to build a candidate right now — re-run. "
                        + "FLDT [" + fldtEntry.feed().validFrom() + "," + fldtEntry.feed().validTo() + "] "
                        + "USDM [" + usdmEntry.feed().validFrom() + "," + usdmEntry.feed().validTo() + "]");

        long[] slots = validitySlots(converters, validFromRaw, validToRaw);
        long validFromMillis = millisOf(converters.slot().slotToTime(slots[0]));
        long validToMillis = millisOf(converters.slot().slotToTime(slots[1]));

        // ⛔ The wallet: the REAL live UTxO set, so the token-aware selection from slice 3
        // (WalletInputSelection.smallestSufficientToken) is exercised against the REAL wallet shape,
        // not a synthetic one — this is the whole point of pinning the candidate to this wallet.
        String realWallet = System.getenv().getOrDefault("REAL_BOT_WALLET", BOT_WALLET);
        List<Utxo> walletUtxos = liveWalletUtxos(backend, realWallet);

        LiquidateTransactionBuilder.ReferenceScripts scripts = referenceScripts();
        List<TransactionInput> refScriptCoords = referenceScriptCoordinates(scripts);

        // ⛔ BOTH oracle scripts, fetched by their OWN reference-script hashes — findings §59.6's wall.
        // Plus lm_liquidate_and_pay_in_advance_action itself: mainnet publishes it as a reference
        // script (application.yaml), so it travels by REFERENCE here rather than witness-attached —
        // unlike every existing offline fixture of this builder, which leaves it inline. EvalFixtures'
        // base scriptSupplier list does not carry it (no offline fixture ever needed it referenced),
        // so it is handed in here as an extra rather than by editing that shared test helper.
        PlutusScript fldtOracleScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                oracleScriptCbor(backend, fldtEntry), PlutusVersion.v3);
        PlutusScript usdmOracleScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                oracleScriptCbor(backend, usdmEntry), PlutusVersion.v3);
        List<PlutusScript> extraScripts = List.of(fldtOracleScript, usdmOracleScript,
                registry.getLmLiquidateAndPayInAdvanceActionScript());

        // ⛔ THE UNIVERSE FIRST — every reference-script UTxO this builder can name, both oracle legs'
        // referenceInput/referenceScript(/provider), the loan/bond/config pair and the FULL wallet
        // UTxO set (not just the one that ends up nominated — balancing may need another one).
        List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo));
        universe.addAll(walletUtxos);
        for (TransactionInput in : refScriptCoords) {
            universe.add(output(backend, in.getTransactionId(), in.getIndex()));
        }
        entryReferenceInputs(backend, fldtEntry, universe);
        entryReferenceInputs(backend, usdmEntry, universe);

        var aiken = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe),
                new DefaultProtocolParamsSupplier(backend.getEpochService()),
                EvalFixtures.scriptSupplier(registry, extraScripts),
                SlotConfigs.mainnet());

        // ⚑ CCL wraps a failed evaluation as TxBuildException and DROPS the evaluator's own message —
        // decorated so the real reason (which redeemer, why) survives to the report.
        TransactionEvaluator evaluator = (cbor, inputUtxos) -> {
            try {
                Result<List<EvaluationResult>> result = aiken.evaluateTx(cbor, inputUtxos);
                if (!result.isSuccessful()) {
                    lastEvaluatorMessage = String.valueOf(result.getResponse());
                }
                return result;
            } catch (RuntimeException | com.bloxbean.cardano.client.api.exception.ApiException e) {
                StringBuilder chain = new StringBuilder();
                for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
                    chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
                }
                lastEvaluatorMessage = chain + describe(cbor) + resolved(inputUtxos);
                throw e;
            }
        };

        var builder = new LiquidatePayInAdvanceTransactionBuilder(registry, Networks.mainnet(), backend, evaluator);

        // The five numbers, off the SAME builder that will build — never re-derived independently, so
        // there is nothing here that could silently drift from what build() itself recomputes.
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers =
                builder.numbers(loan, bond, fldtEntry, usdmEntry, validFromMillis);
        String candidateState = "collateral=" + collateralAmount + " " + FLDT.toUnit()
                + " remainingDebt=" + numbers.remainingDebt() + " " + USDM.toUnit()
                + " partialLiquidationPenaltyPerMille=" + liquidation.partialLiquidationPenaltyPerMille()
                + " equity=" + numbers.equity() + " " + FLDT.toUnit()
                + " at validFromMillis=" + validFromMillis
                + " | FLDT price=" + fldtEntry.feed().priceInLovelaces() + "/"
                + fldtEntry.feed().priceDenominator() + " lovelace"
                + " | USDM price=" + usdmEntry.feed().priceInLovelaces() + "/"
                + usdmEntry.feed().priceDenominator() + " lovelace";
        System.out.println("candidate state: " + candidateState);
        // F0 (round 2) — EQUITY 0 IS BUILDABLE, NOT A REFUSAL. This used to mirror the OLD precondition
        // PayInAdvanceLiquidationRouter and the builder's own build() checked (equity > 0), refusing a
        // non-positive-equity candidate before ever reaching the builder. That precondition was ours,
        // not the validator's: loan_claim_action.ak:240-259 accepts equity == 0 outright — the
        // underwater loan, the common liquidation, and (measured against this run) the live candidate's
        // actual state today. Only a genuinely NEGATIVE equity is still refused — unreachable here,
        // since LoanFinance.redeemerEquity floors it to zero, exactly as production's own builder does.
        // This is a LIVE rig: the pinned candidate's equity is a fact about the CHAIN at query time,
        // not something this test controls, and a thin-margin position can cross zero between the
        // ticket's pin and this run (interest accrual + oracle drift) — which is exactly what happened:
        // the candidate crossed from positive to zero, and F0 is what keeps this rig buildable through it.
        assertTrue(numbers.equity().signum() >= 0,
                "the pinned candidate has gone NEGATIVE equity — unreachable through LoanFinance's own "
                        + "floor, so this would mean that floor itself broke — " + candidateState);
        BigInteger lenderPayout = numbers.convertedLoanCollateralToPrincipalAmount();

        // T-052 / MarketGate's Part 2: the smallest wallet utxo carrying enough USDM plus enough ada
        // for the fee ceiling and the min-ada rider — mirrors LiquidationExecutor.nominate()'s
        // token-principal branch exactly, against the REAL live protocol params.
        ProtocolParams liveParams = new DefaultProtocolParamsSupplier(backend.getEpochService()).getProtocolParams();
        BigInteger requiredAda = LedgerCeilings.maxPossibleFee(liveParams).add(TOKEN_PRINCIPAL_MIN_ADA_CEILING);
        Utxo walletUtxo = WalletInputSelection.smallestSufficientToken(walletUtxos, USDM, lenderPayout, requiredAda)
                .orElseThrow(() -> new AssertionError(
                        "no utxo at " + realWallet + " carries >= " + lenderPayout + " " + USDM.toUnit()
                                + " alongside >= " + requiredAda + " lovelace (fee ceiling + min-ada rider) — "
                                + "this is a wallet-funding gap, not a rig or validator defect"));

        var request = new LiquidatePayInAdvanceTransactionBuilder.Request(loan, loanUtxo, bond, bondUtxo,
                walletUtxo, configUtxo, lmConfigUtxo, fldtEntry, usdmEntry,
                validFromMillis, validToMillis, slots[0], slots[1],
                walletUtxo.getAddress(), scripts, ORACLE_WINDOW_MARGIN_MILLIS);

        Transaction transaction = builder.build(request);
        return new Built(transaction, universe, List.of(fldtOracleScript, usdmOracleScript), request, numbers,
                registry, evaluator);
    }

    // ---- diagnostics, kept for a failing build ------------------------------------------------------

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
            if (tx.getWitnessSet() != null && tx.getWitnessSet().getRedeemers() != null) {
                out.append("redeemers:\n");
                for (var r : tx.getWitnessSet().getRedeemers()) {
                    out.append("  ").append(r.getTag()).append(':').append(r.getIndex()).append('\n');
                }
            }
            out.append("cbor: ").append(HexUtil.encodeHexString(cbor)).append('\n');
            return out.toString();
        } catch (Exception e) {
            return "\n(could not decode the body the evaluator saw: " + e + ")";
        }
    }

    private static String resolved(Set<Utxo> inputs) {
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

    // ---- reading the built body ----------------------------------------------------------------------

    private static String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), Networks.mainnet()).getAddress();
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static List<TransactionOutput> assetManagerOutputs(Transaction tx, LoansContractRegistry registry) {
        String assetManagerSpend = registry.getAssetManagerSpendScriptHash();
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size()).sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }

    private static int withdrawalIndexOf(Transaction tx, String rewardAddress) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        for (int i = 0; i < withdrawals.size(); i++) {
            if (withdrawals.get(i).getRewardAddress().equals(rewardAddress)) {
                return i;
            }
        }
        throw new AssertionError("no withdrawal at " + rewardAddress);
    }

    /**
     * {@code ClaimData.principalOracleRefInputIndex} (field 3, 0-indexed) out of the
     * {@code loan_claim_action} withdraw redeemer — {@code LiquidationTxEncoder.claimData}'s layout:
     * {@code Constr 0 [ liquidationMode, lenderBondOutputIndex, collateralOracleRefInputIndex,
     * principalOracleRefInputIndex, lenderAuth, equity, loanId, remainingDebt ]}, itself field 1 of
     * the redeemer's {@code Constr 0 [ configRefInputIndex, [claimData, ...] ]}.
     */
    private static int principalOracleRefInputIndex(Transaction tx, LoansContractRegistry registry) {
        String rewardAddr = rewardAddress(registry.getLoanClaimActionScriptHash());
        int widx = withdrawalIndexOf(tx, rewardAddr);
        Redeemer redeemer = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward && r.getIndex().intValue() == widx)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reward redeemer for loan_claim_action"));
        ConstrPlutusData top = (ConstrPlutusData) redeemer.getData();
        ListPlutusData claims = (ListPlutusData) top.getData().getPlutusDataList().get(1);
        ConstrPlutusData claim = (ConstrPlutusData) claims.getPlutusDataList().get(0);
        BigIntPlutusData idx = (BigIntPlutusData) claim.getData().getPlutusDataList().get(3);
        return idx.getValue().intValueExact();
    }

    /** Decrements the lender's paid-in-advance USDM quantity by exactly one base unit, in place. */
    private static Transaction decrementLenderPayoutByOne(Transaction source,
                                                           LoansContractRegistry registry) throws Exception {
        Transaction mutated = Transaction.deserialize(source.serialize());
        TransactionOutput lenderOutput = assetManagerOutputs(mutated, registry).stream()
                .filter(o -> quantityOf(o, USDM).signum() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no asset-manager output carries USDM — cannot perturb the payout"));
        boolean decremented = false;
        for (MultiAsset ma : lenderOutput.getValue().getMultiAssets()) {
            if (!ma.getPolicyId().equalsIgnoreCase(USDM.policyId())) {
                continue;
            }
            for (Asset asset : ma.getAssets()) {
                if (HexUtil.encodeHexString(asset.getNameAsBytes()).equalsIgnoreCase(USDM.assetName())) {
                    asset.setValue(asset.getValue().subtract(BigInteger.ONE));
                    decremented = true;
                }
            }
        }
        if (!decremented) {
            throw new IllegalStateException("found the lender's asset-manager output but not its USDM entry");
        }
        return mutated;
    }

    // ---- the assertions -------------------------------------------------------------------------------

    /**
     * ⛔ THE ONE THIS RIG EXISTS FOR: a real token-principal pay-in-advance liquidation, assembled and
     * evaluated against the deployed validators, with BOTH real oracle legs.
     */
    @Test
    void theRealTokenPrincipalCandidateBuildsAndEveryScriptEvaluates() throws Exception {
        Built built;
        try {
            built = build();
        } catch (Exception e) {
            throw new AssertionError("the token-principal pay-in-advance build failed. What the "
                    + "evaluator said: " + lastEvaluatorMessage, e);
        }

        Transaction rebuilt = Transaction.deserialize(built.transaction().serialize());
        assertNotNull(rebuilt.getWitnessSet(), "the built transaction has no witness set");
        List<Redeemer> redeemers = rebuilt.getWitnessSet().getRedeemers();
        assertNotNull(redeemers, "the built transaction has no redeemers");
        assertFalse(redeemers.isEmpty());

        // ⛔ Every redeemer this shape must carry, identified STRUCTURALLY (by reward address / tag),
        // never by an assumed total — CCL trap 14: two redeemers can be byte-indistinguishable, and a
        // total alone cannot tell "the right six" from "some six".
        long spends = redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Spend).count();
        long mints = redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Mint).count();
        assertEquals(2, spends, "the loan and bond spends");
        assertEquals(1, mints, "the loan-NFT burn");

        Set<String> withdrawalAddrs = new LinkedHashSet<>();
        rebuilt.getBody().getWithdrawals().forEach(w -> withdrawalAddrs.add(w.getRewardAddress()));
        LoansContractRegistry registry = built.registry();
        assertTrue(withdrawalAddrs.contains(rewardAddress(registry.getLoanPolicyId())), "loan withdraw-0 missing");
        assertTrue(withdrawalAddrs.contains(rewardAddress(registry.getLoanClaimActionScriptHash())),
                "loan_claim_action withdraw-0 missing");
        assertTrue(withdrawalAddrs.contains(rewardAddress(registry.getLenderManagerWithdrawScriptHash())),
                "lenderManager withdraw-0 missing");
        assertTrue(withdrawalAddrs.contains(rewardAddress(registry.getLmLiquidateAndPayInAdvanceActionScriptHash())),
                "lm_liquidate_and_pay_in_advance_action withdraw-0 missing");
        assertTrue(withdrawalAddrs.contains(built.request().oracle().rewardAddress()),
                "collateral (FLDT) oracle withdraw-0 missing");
        assertTrue(withdrawalAddrs.contains(built.request().principalOracle().rewardAddress()),
                "principal (USDM) oracle withdraw-0 missing — WALL 3's second withdraw-0 did not happen");
        System.out.println("withdrawals (" + withdrawalAddrs.size() + "): " + withdrawalAddrs);

        // ⛔ Ex-units off the BUILT, DESERIALISED transaction — never off an evaluator's report. A
        // rig-supplied evaluator makes the report look right while production has none (CCL trap 8).
        for (var r : redeemers) {
            assertNotNull(r.getExUnits(), "redeemer " + r.getTag() + ":" + r.getIndex() + " is uncosted");
            assertTrue(r.getExUnits().getSteps().longValue() > 1_000_000L,
                    "redeemer " + r.getTag() + ":" + r.getIndex() + " carries "
                            + r.getExUnits().getMem() + "/" + r.getExUnits().getSteps()
                            + " — cardano-client-lib's placeholder budget, so no evaluator ran");
        }

        // WALL 4 — the lender's paid-in-advance output holds >= the payout, in USDM, flatten == 2.
        TransactionOutput lenderOutput = assetManagerOutputs(rebuilt, registry).stream()
                .filter(o -> quantityOf(o, USDM).signum() > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no asset-manager output carries USDM"));
        assertTrue(quantityOf(lenderOutput, USDM)
                        .compareTo(built.numbers().convertedLoanCollateralToPrincipalAmount()) >= 0,
                "the lender's output holds less USDM than the required payout");
        assertEquals(2, flattenedCount(lenderOutput),
                "the lender's output must be the USDM token plus its min-ada rider (flatten == 2)");

        // The redeemer's principalOracleRefInputIndex points at the USDM oracle's referenceInput, in
        // the body's own (canonically sorted) reference-input list.
        int principalIdx = principalOracleRefInputIndex(rebuilt, registry);
        TransactionInput atIndex = rebuilt.getBody().getReferenceInputs().get(principalIdx);
        assertEquals(built.request().principalOracle().referenceInput(), atIndex,
                "principalOracleRefInputIndex does not point at the USDM oracle's reference input in "
                        + "the body's sorted reference inputs");

        // The exact arithmetic, cross-checked against LoanFinance directly rather than hardcoded —
        // the ticket's own instruction, because the number moves with the live oracle ratio and equity.
        BigInteger recomputed = LoanFinance.convertFromAToBWithOracles(
                built.request().oracle().feed(), built.request().principalOracle().feed(),
                Rational.fromInt(built.numbers().collateralLenderShouldReceive()));
        assertEquals(recomputed, built.numbers().convertedLoanCollateralToPrincipalAmount(),
                "the builder's payout disagrees with LoanFinance.convertFromAToBWithOracles on the "
                        + "same live feeds and instant");

        System.out.println("equity " + built.numbers().equity() + " USDM payout "
                + built.numbers().convertedLoanCollateralToPrincipalAmount()
                + " fee " + rebuilt.getBody().getFee()
                + " size " + rebuilt.serialize().length + " bytes");
    }

    /**
     * ⛔ The coordinates the pinned candidate needs to STILL BE TRUE on chain — CCL trap 12: an
     * existence check ({@code getTxOutput}) proves an output once existed, never that it is still
     * unspent, so every coordinate here is re-verified against the LIVE UTxO set.
     */
    @Test
    void thePinnedCoordinatesAreStillTheLiveOnes() throws Exception {
        BFBackendService backend = backend();
        LoansContractRegistry registry = registry();

        assertLiveAndCarries(backend, "loan", new TransactionInput(LOAN_TX, LOAN_IX),
                new AssetType(registry.getLoanPolicyId(), LOAN_ID));

        FluidOracleClient oracles = new FluidOracleClient(ORACLE_REGISTRY_URL);
        oracles.refresh();
        OracleEntry fldt = oracles.findEntry(FLDT)
                .orElseThrow(() -> new IllegalStateException("no mainnet oracle entry for FLDT"));
        OracleEntry usdm = oracles.findEntry(USDM)
                .orElseThrow(() -> new IllegalStateException("no mainnet oracle entry for USDM"));

        // Cross-check against the coordinates the ticket named, read from the registry today.
        assertEquals(FLDT_ORACLE, fldt.oracleToken(), "the live FLDT oracle NFT moved from what was pinned");
        assertEquals(FLDT_ORACLE_REF_INPUT, fldt.referenceInput(), "the live FLDT oracle referenceInput moved");
        assertEquals(FLDT_ORACLE_REF_SCRIPT, fldt.referenceScript(), "the live FLDT oracle referenceScript moved");
        assertEquals(USDM_ORACLE, usdm.oracleToken(), "the live USDM oracle NFT moved from what was pinned");
        assertEquals(USDM_ORACLE_REF_INPUT, usdm.referenceInput(), "the live USDM oracle referenceInput moved");
        assertEquals(USDM_ORACLE_REF_SCRIPT, usdm.referenceScript(), "the live USDM oracle referenceScript moved");

        assertLiveAndCarries(backend, "FLDT oracle", fldt.referenceInput(), fldt.oracleToken());
        assertLiveAndCarries(backend, "USDM oracle", usdm.referenceInput(), usdm.oracleToken());

        // The main/LM config — the SAME global config ConvertLiveDryEvalTest pins and verifies.
        Utxo lmConfig = output(backend, LM_CONFIG_TX, LM_CONFIG_IX);
        String lmNft = LM_CONFIG_POLICY + ASSET_NAME;
        assertTrue(lmConfig.getAmount().stream().anyMatch(a -> lmNft.equals(a.getUnit())),
                "the pinned LM config UTxO no longer carries the LM config NFT " + lmNft);
        String derived = registry.getLmLiquidateAndPayInAdvanceActionScriptHash();
        assertNotNull(lmConfig.getInlineDatum(), "the LM config UTxO carries no inline datum");
        assertTrue(lmConfig.getInlineDatum().contains(derived),
                "the live LM config does not name the lm_liquidate_and_pay_in_advance_action hash this "
                        + "node derives (" + derived + ") — either the vendored blueprint or the pinned "
                        + "LM config is stale");
    }

    /** CCL trap 12: query the LIVE UTxO set at the output's own address, never just its existence. */
    private static void assertLiveAndCarries(BFBackendService backend, String what, TransactionInput ref,
                                             AssetType nft) throws Exception {
        Utxo out = output(backend, ref.getTransactionId(), ref.getIndex());
        String unit = nft.toUnit();
        assertTrue(out.getAmount().stream().anyMatch(a -> unit.equalsIgnoreCase(a.getUnit())),
                what + " reference input " + ref + " does not carry " + unit);
        Result<List<Utxo>> live = backend.getUtxoService().getUtxos(out.getAddress(), unit, 20, 1);
        assertTrue(live.isSuccessful(), "could not list live utxos at " + out.getAddress() + ": "
                + live.getResponse());
        boolean stillThere = live.getValue().stream().anyMatch(u ->
                u.getTxHash().equals(ref.getTransactionId()) && u.getOutputIndex() == ref.getIndex());
        assertTrue(stillThere, what + " reference input " + ref + " no longer appears in the live UTxO "
                + "set at " + out.getAddress() + " — it has been spent (CCL trap 12)");
    }

    /**
     * ⛔ THE ADVERSARIAL CASE — the whole point of this rig. A lender payout one base unit short of
     * {@code convertedLoanCollateralToPrincipalAmount} is a value the validator computes and checks
     * for itself ({@code validate_repayment_output}: {@code quantity_of(output, principalAsset) >=
     * convertedAmount}), so it MUST be refused by the VALIDATOR — not by this builder's own
     * {@code assertStructure}, which would refuse it first if the wrong number were fed INTO a normal
     * build. So this takes the REAL, successfully built (and already structurally-proven) transaction
     * and perturbs the FINISHED body by byte-surgery — {@code assertStructure} never sees the mutated
     * bytes, because it already ran and passed on the correct ones. Only the real UPLC machine gets to
     * say no.
     */
    @Test
    void aLenderPayoutOneUnitShortIsREJECTEDByTheValidator() throws Exception {
        Built built;
        try {
            built = build();
        } catch (Exception e) {
            throw new AssertionError("the baseline build failed, so no adversarial case can be run. What "
                    + "the evaluator said: " + lastEvaluatorMessage, e);
        }

        Transaction mutated = decrementLenderPayoutByOne(built.transaction(), built.registry());
        byte[] mutatedCbor = mutated.serialize();
        Set<Utxo> inputs = new LinkedHashSet<>(built.universe());

        lastEvaluatorMessage = null;
        Result<List<EvaluationResult>> result = null;
        Exception thrown = null;
        try {
            result = built.evaluator().evaluateTx(mutatedCbor, inputs);
        } catch (Exception e) {
            thrown = e;
        }

        if (thrown == null) {
            assertFalse(result.isSuccessful(),
                    "a lender payout one unit short of "
                            + built.numbers().convertedLoanCollateralToPrincipalAmount()
                            + " USDM evaluated SUCCESSFULLY — the validator's own repayment check "
                            + "(validate_repayment_output) did not run, or this rig perturbed the wrong "
                            + "output");
        }

        // ⛔ Must come from EVALUATION, not from assembly or an empty, unattributed failure — an empty
        // ScriptFailures-shaped result has meant "you are short" (i.e. an input-resolution problem, not
        // a script denial) in this repo before (2026-08-24).
        assertNotNull(lastEvaluatorMessage,
                "the mutated transaction failed before the evaluator ever reported anything, so this "
                        + "proves nothing about the validator");
        assertFalse(lastEvaluatorMessage.isBlank(), "the evaluator reported an empty message");
        assertTrue(lastEvaluatorMessage.contains("EvaluationFailure")
                        || lastEvaluatorMessage.contains("RedeemerError"),
                "the perturbed transaction failed for a reason other than script evaluation, so it does "
                        + "not demonstrate that the validator rejects a short payout: " + lastEvaluatorMessage);
    }
}
