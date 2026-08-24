package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.*;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * <b>SUBMITS REAL TRANSACTIONS ON PREVIEW.</b> Giovanni's first-hand instruction, 2026-08-24:
 * "allow tx to be actually submitted and not in dry run… liquidate for real in the tests."
 * Confirmed again in-session before running.
 * <p>
 * Double-gated: needs {@code WALLET_MNEMONIC} + {@code BLOCKFROST_KEY}, and needs
 * {@code AQUARIUM_LIQUIDATE_FOR_REAL=true} — having a mnemonic must never on its own put a wallet
 * on the path of a real liquidation. Preview only.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_LIQUIDATE_FOR_REAL", matches = "true")
public class LiquidateForRealTest {

    static final String URL = "https://cardano-preview.blockfrost.io/api/v0/";
    static final String CFG = "c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9";
    static final String LMCFG = "de1b8b40536f96c1084d73f838ebac6b228d891902d6234afc731484";
    static final String NAME = "706172616d6574657273";
    static final String LOAN_ADDR = "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0n"
            + "qjduhey4swhxfp7h42thjhhvnjkmcgaps3ahx5qxanp9j";
    static final String BOND_ADDR = "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5"
            + "lhaegyrm8per6p48mpr26tegngjg7zrdk23hps7h96kk";
    static final String REF_LOAN_CLAIM = "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd";
    static final long MARGIN = 30_000L, WINDOW = 120_000L, BACKDATE = 60_000L;
    static final AssetType COLL = new AssetType(
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    /** Wallet A index 1 — 9,961 ADA, ada-only. NOT index 0, which carries the published ref script. */
    static final int WALLET_INDEX = Integer.parseInt(
            System.getenv().getOrDefault("AQUARIUM_WALLET_INDEX", "1"));

    record Target(String tx, String loanId, String label) {}

    @Test
    public void liquidateBothForReal() throws Exception {
        var b = new BFBackendService(URL, System.getenv("BLOCKFROST_KEY"));
        var reg = new LoansContractRegistry(CFG, LMCFG, NAME,
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa");
        CardanoConverters cv = ClasspathConversionsFactory.createConverters(NetworkType.PREVIEW);
        Account signer = new Account(Networks.preview(), System.getenv("WALLET_MNEMONIC"), WALLET_INDEX);
        System.out.println("LQ signer = " + signer.baseAddress());

        PlutusScript oracleScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                LoanFixtures.fixture("preview-oracle-script.hex"), PlutusVersion.v3);
        Map<String, PlutusScript> byHash = new HashMap<>();
        for (PlutusScript ps : List.of(oracleScript, reg.getLoanClaimActionScript(), reg.getLoanScript(),
                reg.getLoanSpendScript(), reg.getLenderManagerScript(),
                reg.getLenderManagerSpendScript(), reg.getLmLiquidateAndPayInAdvanceActionScript()))
            byHash.put(HexUtil.encodeHexString(hash(ps)), ps);

        var oc = new FluidOracleClient("https://testapi.fluidtokens.com/get-oracle-tokens");

        for (Target t : List.of(
                new Target("f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1",
                        "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22", "LOAN 1"),
                new Target("287dd41e681a183d6450a614a98d50cf248fa7ed188f140f911b954e4ce9499f",
                        "c58e25f459554b923b0e37665883d533689bf5d5945b21b434890323", "LOAN 2"))) {

            System.out.println("\nLQ ================ " + t.label() + " " + t.tx().substring(0, 16) + " ================");
            Utxo lu = at(b, LOAN_ADDR, t.tx(), 1), bu = at(b, BOND_ADDR, t.tx(), 3);
            if (lu == null || bu == null) { System.out.println("LQ already spent — skipping"); continue; }

            // 1. DECODE FROM CHAIN, fresh.
            LoanDatum ld = new LoanDatumConverter().deserialize(lu.getInlineDatum());
            LenderManagerDatum bd = new LenderManagerDatumConverter().deserialize(bu.getInlineDatum());
            BigInteger collAmt = q(lu, false);
            System.out.println("LQ decoded: collateral=" + collAmt + " lovelaceInLoan=" + q(lu, true)
                    + " convertToPrincipal=" + bd.shouldLiquidationConvertToPrincipal()
                    + " feePerMille=" + bd.liquidationFeePerMille());

            // 2. WAIT for a feed that covers the window, then compute the maths at that instant.
            OracleEntry entry = null;
            for (int i = 0; i < 40; i++) {
                oc.refresh();
                entry = oc.findEntry(COLL).orElse(null);
                long rem = entry == null ? -1
                        : entry.feed().validTo() - (System.currentTimeMillis() + WINDOW);
                if (entry != null && rem >= MARGIN) { System.out.println("LQ feed ok, " + rem + "ms spare"); break; }
                System.out.println("LQ waiting for feed, spare=" + rem + "ms");
                Thread.sleep(15_000L);
            }

            long now = System.currentTimeMillis();
            long[] sl = slots(cv, now - BACKDATE, now + WINDOW);
            long fromMs = ms(cv, sl[0]), toMs = ms(cv, sl[1]);
            BigInteger debt = LoanFinance.remainingDebt(ld, fromMs);
            System.out.println("LQ maths: remainingDebt=" + debt
                    + " price=" + entry.feed().priceInLovelaces() + "/" + entry.feed().priceDenominator());

            Utxo wallet = b.getUtxoService().getUtxos(signer.baseAddress(), 100, 1).getValue().stream()
                    .filter(u -> u.getAmount().size() == 1 && u.getReferenceScriptHash() == null
                            && u.getInlineDatum() == null && u.getDataHash() == null)
                    .max(Comparator.comparing(u -> u.getAmount().getFirst().getQuantity())).orElseThrow();
            System.out.println("LQ wallet utxo " + wallet.getTxHash().substring(0, 12) + "#"
                    + wallet.getOutputIndex() + " " + wallet.getAmount().getFirst().getQuantity());

            Loan loan = new Loan(t.tx(), 1, lu.getAddress(), t.loanId(), collAmt, q(lu, true), ld);
            LenderBond bond = new LenderBond(t.tx(), 3, bu.getAddress(), t.loanId(), bu.getInlineDatum(), bd);

            var req = new LiquidatePayInAdvanceTransactionBuilder.Request(loan, lu, bond, bu, wallet,
                    cfg(b, CFG), cfg(b, LMCFG), entry, fromMs, toMs, sl[0], sl[1],
                    signer.baseAddress(),
                    new LiquidateTransactionBuilder.ReferenceScripts(null, null, null, null,
                            new TransactionInput(REF_LOAN_CLAIM, 0), null, null), MARGIN);

            // 3+4+5. Build through the PRODUCTION constructor: backend script supplier, real
            // Blockfrost evaluator, guarded coin selection and pinned collateral.
            Transaction tx = new LiquidatePayInAdvanceTransactionBuilder(reg, Networks.preview(), b,
                    (cbor, in) -> b.getTransactionService().evaluateTx(cbor)).build(req);
            System.out.println("LQ BUILT size=" + tx.serialize().length + " fee=" + tx.getBody().getFee());
            tx.getWitnessSet().getRedeemers().forEach(r ->
                    System.out.println("LQ   " + r.getTag() + "#" + r.getIndex()
                            + " mem=" + r.getExUnits().getMem() + " steps=" + r.getExUnits().getSteps()));

            // 6. CROSS-CHECK with the second evaluator over the same bytes.
            crossCheck(b, tx, byHash);

            // FEE UPLIFT. Iteration 1 was rejected in phase 1 with
            //   FeeTooSmallUTxO {supplied: 1213011, expected: 1275007}  -- short by 61,996.
            // That is CCL trap 9: the ledger charges the Conway reference-script fee for EVERY script
            // reachable by reference input, and cardano-client-lib can only charge for bytes it holds.
            // We hand it loan_claim_action via withReferenceScripts, but the ORACLE's script
            // (ba34f9e5...#0) is a third party's and is not in our registry, so CCL prices it at zero.
            // Note this only became visible after e11ccca: before that, the witness copy of
            // loan_claim_action was still in the body at fee time and its accidental OVER-charge was
            // masking this under-charge.
            // Overpaying a fee is always legal; underpaying is a phase-1 rejection. The uplift comes
            // out of the change output so value stays conserved, and ex-units are untouched.
            BigInteger uplift = BigInteger.valueOf(300_000L);
            var outs = tx.getBody().getOutputs();
            var change = outs.stream()
                    .filter(x -> x.getAddress().equals(signer.baseAddress()))
                    .max(Comparator.comparing(x -> x.getValue().getCoin())).orElseThrow();
            change.getValue().setCoin(change.getValue().getCoin().subtract(uplift));
            tx.getBody().setFee(tx.getBody().getFee().add(uplift));
            System.out.println("LQ fee uplifted to " + tx.getBody().getFee()
                    + ", change now " + change.getValue().getCoin());

            // COLLATERAL follows the fee. Iteration 2 was rejected with
            //   InsufficientCollateral (DeltaCoin 1819517) (Coin 2269517)
            // because the ledger requires collateral >= collateralPercent% of the FEE, and raising
            // the fee without raising totalCollateral left the old 150%-of-the-old-fee figure behind.
            // Recomputed from the live protocol parameter rather than assuming 150.
            var pp = b.getEpochService().getProtocolParameters().getValue();
            BigInteger pct = BigInteger.valueOf(pp.getCollateralPercent().longValue());
            BigInteger needed = tx.getBody().getFee().multiply(pct)
                    .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100))
                    .add(BigInteger.valueOf(100_000L));   // headroom, overpaying is legal
            BigInteger collIn = q(wallet, true);
            tx.getBody().setTotalCollateral(needed);
            if (tx.getBody().getCollateralReturn() != null) {
                tx.getBody().getCollateralReturn().getValue().setCoin(collIn.subtract(needed));
            }
            System.out.println("LQ collateralPercent=" + pct + " totalCollateral=" + needed
                    + " collateralReturn=" + collIn.subtract(needed)
                    + " (collateral input " + collIn + ")");

            // 7. SIGN AND SUBMIT — real, on preview.
            Transaction signed = signer.sign(tx);
            var res = b.getTransactionService().submitTransaction(signed.serialize());
            if (res.isSuccessful()) {
                System.out.println("LQ *** SUBMITTED *** txId=" + res.getValue());
            } else {
                System.out.println("LQ SUBMIT REJECTED code=" + res.code() + " -> " + res.getResponse());
                System.out.println("LQ stopping — not retrying blind");
                return;
            }
            for (int i = 0; i < 20; i++) {
                Thread.sleep(15_000L);
                var conf = b.getTransactionService().getTransaction(String.valueOf(res.getValue()));
                if (conf.isSuccessful() && conf.getValue() != null) {
                    System.out.println("LQ *** CONFIRMED ON CHAIN *** block=" + conf.getValue().getBlockHeight());
                    break;
                }
                System.out.println("LQ awaiting confirmation…");
            }
        }
    }

    private void crossCheck(BFBackendService b, Transaction tx, Map<String, PlutusScript> byHash) {
        try {
            byte[] cbor = tx.serialize();
            LinkedHashSet<Utxo> res = new LinkedHashSet<>();
            var all = new ArrayList<>(tx.getBody().getInputs());
            all.addAll(tx.getBody().getReferenceInputs());
            for (TransactionInput ti : all) {
                var r = b.getUtxoService().getTxOutput(ti.getTransactionId(), ti.getIndex());
                if (r.isSuccessful() && r.getValue() != null) res.add(r.getValue());
            }
            UtxoSupplier sup = new UtxoSupplier() {
                public List<Utxo> getPage(String a, Integer n, Integer p, OrderEnum o) {
                    return p != null && p > 0 ? List.of()
                            : res.stream().filter(u -> u.getAddress().equals(a)).toList(); }
                public Optional<Utxo> getTxOutput(String h, int i) {
                    return res.stream().filter(u -> u.getTxHash().equals(h)
                            && u.getOutputIndex() == i).findFirst(); }
            };
            var ar = new AikenTransactionEvaluator(sup, EvalFixtures.protocolParams(),
                    h -> Optional.ofNullable(byHash.get(h)), SlotConfigs.preview()).evaluateTx(cbor, res);
            System.out.println("LQ AIKEN cross-check successful=" + ar.isSuccessful()
                    + " redeemers=" + (ar.getValue() == null ? 0 : ar.getValue().size()));
        } catch (Exception e) {
            System.out.println("LQ AIKEN cross-check threw: " + e.getMessage());
        }
    }

    static long[] slots(CardanoConverters cv, long from, long to) {
        long f = cv.time().toSlot(LocalDateTime.ofInstant(Instant.ofEpochMilli(from), ZoneOffset.UTC));
        if (ms(cv, f) < from) f += 1;
        long t = cv.time().toSlot(LocalDateTime.ofInstant(Instant.ofEpochMilli(to), ZoneOffset.UTC));
        if (ms(cv, t) > to) t -= 1;
        return new long[]{f, t};
    }
    static long ms(CardanoConverters cv, long slot) {
        return cv.slot().slotToTime(slot).toInstant(ZoneOffset.UTC).toEpochMilli(); }
    static byte[] hash(PlutusScript s) { try { return s.getScriptHash(); } catch (Exception e) { return new byte[0]; } }
    static BigInteger q(Utxo u, boolean lov) {
        return u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace") == lov)
                .findFirst().map(a -> a.getQuantity()).orElse(BigInteger.ZERO); }
    static Utxo at(BFBackendService b, String a, String tx, int i) throws Exception {
        return b.getUtxoService().getUtxos(a, 100, 1).getValue().stream()
                .filter(u -> u.getTxHash().equals(tx) && u.getOutputIndex() == i).findFirst().orElse(null); }
    static Utxo cfg(BFBackendService b, String pol) throws Exception {
        String h = b.getAssetService().getAllAssetAddresses(pol + NAME).getValue().getFirst().getAddress();
        return b.getUtxoService().getUtxos(h, 100, 1).getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(x -> x.getUnit().equals(pol + NAME)))
                .findFirst().orElseThrow(); }
}
