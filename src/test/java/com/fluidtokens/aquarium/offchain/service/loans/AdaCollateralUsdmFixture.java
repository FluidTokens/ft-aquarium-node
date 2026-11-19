package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.LOANS_V4;

/**
 * FAB-226 — one recorded mainnet loan with ADA collateral and a USDM principal, wired for a pay-in-advance through
 * {@link LiquidatePayInAdvanceTransactionBuilder}'s production constructor and ONE {@link TestOracleRig} oracle,
 * the principal's. Test-only; no {@code @Test} here.
 *
 * <h2>What is recorded and what is not</h2>
 * The loan and bond datums and the deployed USDM v2 oracle script are recorded mainnet bytes, and so are the loan,
 * bond and config UTxOs' coordinates, addresses and values ({@code mainnet-ada-collateral-pay-in-advance.PROVENANCE.md}).
 * The registry, the configs, the six registry reference-script UTxOs and the wallet factories are
 * {@link StrikeUsdmFixture}'s, reused by reference. Synthetic: the bot's wallet, the reference-script UTxOs, and the
 * principal oracle leg, which comes from the rig.
 *
 * <h2>Why one leg</h2>
 * An ADA collateral has no oracle: {@code lib/fluidtokens/oracle.ak}'s {@code retrieve_oracle_data} returns the
 * synthesised 1:1 feed for the empty policy id without reading any reference input or redeemer. So the request's
 * {@code oracle} is {@code null}, as {@code principalOracle} is for an ADA principal, and only the USDM leg is
 * priced, referenced and withdrawn from.
 *
 * <h2>Why the price comes from the rig</h2>
 * No recorded signed USDM feed makes this loan liquidatable: at the recorded 400,610,982 / 10⁸ its LTV is about 0.27.
 * A signed feed cannot be edited, so the leg is the deployed USDM v2 oracle's code — control-hashed to
 * {@code df6f292e} from {@code loans-v4.plutus.json} — re-parameterised with runtime keys, at a price this class
 * chooses. The rig script reaches the transaction only as a reference script served by the script supplier.
 *
 * <h2>Instants</h2>
 * {@link #T0} is a whole second, so it converts to a mainnet slot exactly.
 */
final class AdaCollateralUsdmFixture {

    private AdaCollateralUsdmFixture() {
    }

    // ---- the recorded loan and bond (Koios, unspent 2026-11-13) ---------------------------------------

    /** 2026-10-05 12:41:47Z, block 14,029,073: output #1 is the loan, output #3 its bond. */
    static final String LOAN_TX = "2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c";
    static final String BOND_TX = LOAN_TX;
    static final int LOAN_INDEX = 1;
    static final int BOND_INDEX = 3;

    /** Payment credential {@code b5763e3c…}, the registry's loan spend. */
    static final String LOAN_ADDRESS =
            "addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfd3m9rcvrnrasmk5qw7wzn2u995ensxshafrmy666kdkqsmrd6hj";
    /** Payment credential {@code 6743f4b6…}, the registry's lender-manager spend. */
    static final String BOND_ADDRESS =
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczfg9cfhunxjkr3vjanr2tmgvkq0l0hlau7cggjmsm0knwws7g0e4k";
    /** The asset name of the loan's NFT and of its lender bond. */
    static final String LOAN_ID = "441301d34a34125e10f0ea9bd01c5f08dfe5b91d3c2c3101508430fd";

    /** The loan UTxO's whole lovelace, which IS the collateral: an ADA collateral has no separate min-ada. */
    static final long COLLATERAL_LOVELACE = 8_100_000_000L;
    static final long BOND_LOVELACE = 1_969_670L;
    /** The recorded principal: 551 USDM. */
    static final BigInteger PRINCIPAL = BigInteger.valueOf(551_000_000L);

    // ---- the principal's oracle --------------------------------------------------------------------

    /** The principal oracle NFT the loan datum names: v2 "oracleUSDMC3". */
    static final AssetType USDM_V2_ORACLE_NFT = new AssetType("26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26",
            "6f7261636c655553444d4333");
    /** The deployed USDM v2 oracle (registry entry 21) the rig is control-hashed to. */
    static final String USDM_V2_ORACLE_HASH = "df6f292ece90837f738ef156f8940e5f98db2027b3afa8d2041c492d";
    /** Entry 21's recorded multisig price, 400,610,982 / 10⁸ — not liquidatable. */
    static final BigInteger RECORDED_PRICE = BigInteger.valueOf(400_610_982L);

    // ---- the instant and the rig's prices -----------------------------------------------------------

    /** 2026-11-13T00:33:20Z; mainnet slot 202,963,709. */
    static final long T0 = 1_794_530_000_000L;
    /** USDM at 12.5 ADA: the loan is liquidatable with positive equity. */
    static final BigInteger PRICE = BigInteger.valueOf(1_250_000_000L);
    /** USDM at 15 ADA: the loan is underwater, equity 0. */
    static final BigInteger EQUITY_ZERO_PRICE = BigInteger.valueOf(1_500_000_000L);

    // ---- at T0 and the rig price (measured, then frozen) -------------------------------------------

    static final BigInteger REMAINING_DEBT = BigInteger.valueOf(554_825_382L);
    /** The borrower's equity, in lovelace (the collateral currency). */
    static final BigInteger EQUITY = BigInteger.valueOf(471_150_997L);
    /** floor(8,100,000,000 × 40 / 1000). */
    static final BigInteger LIQUIDATION_FEE = BigInteger.valueOf(324_000_000L);
    static final BigInteger LENDER_SHOULD_RECEIVE = BigInteger.valueOf(7_304_849_003L);
    /** ceil(7,304,849,003 / 12.5) USDM. */
    static final BigInteger PAYOUT = BigInteger.valueOf(584_387_921L);
    /** Collateral − equity: the lovelace the bot acquires, the fee slice included. */
    static final BigInteger ACQUIRED = BigInteger.valueOf(7_628_849_003L);
    /** At {@link #EQUITY_ZERO_PRICE}: collateral − fee, converted at 15 ADA per USDM. */
    static final BigInteger EQUITY_ZERO_PAYOUT = BigInteger.valueOf(518_400_000L);

    // ---- the loan, the bond --------------------------------------------------------------------------

    static String loanDatumHex() {
        return LoanFixtures.fixture("mainnet-loan-datum-2d067dae.hex");
    }

    static String bondDatumHex() {
        return LoanFixtures.fixture("mainnet-lender-bond-datum-2d067dae.hex");
    }

    static LoanDatum loanDatum() {
        return new LoanDatumConverter().deserialize(loanDatumHex());
    }

    static LenderManagerDatum bondDatum() {
        return new LenderManagerDatumConverter().deserialize(bondDatumHex());
    }

    static Loan loan() {
        return new Loan(LOAN_TX, LOAN_INDEX, LOAN_ADDRESS, LOAN_ID, BigInteger.valueOf(COLLATERAL_LOVELACE),
                BigInteger.valueOf(COLLATERAL_LOVELACE), loanDatum());
    }

    static LenderBond bond() {
        return new LenderBond(BOND_TX, BOND_INDEX, BOND_ADDRESS, LOAN_ID, bondDatumHex(), bondDatum());
    }

    static Utxo loanUtxo() {
        return LoanFixtures.utxo(LOAN_TX, LOAN_INDEX, LOAN_ADDRESS, List.of(
                        Amount.lovelace(BigInteger.valueOf(COLLATERAL_LOVELACE)),
                        Amount.asset(StrikeUsdmFixture.REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)),
                loanDatumHex());
    }

    static Utxo bondUtxo() {
        return LoanFixtures.utxo(BOND_TX, BOND_INDEX, BOND_ADDRESS, List.of(
                        Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                        Amount.asset(StrikeUsdmFixture.REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)),
                bondDatumHex());
    }

    // ---- the feed and the leg ------------------------------------------------------------------------

    /** USDM at {@code price} over [T0 − 200 s, T0 + 600 s]. */
    static TestOracleRig.Feed feed(BigInteger price) {
        return new TestOracleRig.Feed(price, StrikeUsdmFixture.PRICE_DENOMINATOR, T0 - 200_000L, T0 + 600_000L);
    }

    /** The one oracle leg and the rig that made it. */
    record Oracle(TestOracleRig rig, TestOracleRig.Leg leg) {
    }

    /**
     * One rig on USDM v2's code, with three runtime keys at the deployed threshold (1), signed by the key at
     * position 0. The leg rewrites the recorded registry entry for the NFT the loan datum names for the principal.
     */
    static Oracle oracle(TestOracleRig.Feed feed) {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("df6f292e", LOANS_V4), 3);
        return new Oracle(rig, rig.leg(StrikeUsdmFixture.REGISTRY_PAYLOAD, loanDatum().principalOracleAsset(), feed,
                List.of(new TestOracleRig.Signer(0, false)), StrikeUsdmFixture.NETWORK));
    }

    // ---- the universe, the scripts, the evaluator -------------------------------------------------------

    /** The loan, its bond, both configs, the leg's two UTxOs, the six reference-script UTxOs and {@code wallet}. */
    static List<Utxo> universe(Oracle oracle, List<Utxo> wallet) {
        Map<String, Utxo> byRef = new LinkedHashMap<>();
        List<Utxo> all = new ArrayList<>(List.of(loanUtxo(), bondUtxo(), StrikeUsdmFixture.configUtxo(),
                StrikeUsdmFixture.lmConfigUtxo()));
        all.addAll(oracle.leg().utxos());
        all.addAll(StrikeUsdmFixture.referenceScriptUtxos());
        all.addAll(wallet);
        for (Utxo utxo : all) {
            byRef.putIfAbsent(utxo.getTxHash() + "#" + utxo.getOutputIndex(), utxo);
        }
        return List.copyOf(byRef.values());
    }

    /**
     * The provider leaf: the registry's applied scripts, the rig script and the pay-in-advance action (which
     * {@link EvalFixtures#scriptSupplier} does not carry), served by hash.
     */
    static ScriptSupplier rawScripts(Oracle oracle) {
        return EvalFixtures.scriptSupplier(StrikeUsdmFixture.REGISTRY, List.of(oracle.rig().script(),
                StrikeUsdmFixture.REGISTRY.getLmLiquidateAndPayInAdvanceActionScript()));
    }

    /** The real PlutusV3 machine, mainnet slot config, over {@code universe}. */
    static AikenTransactionEvaluator aiken(List<Utxo> universe, Oracle oracle) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(),
                rawScripts(oracle), SlotConfigs.mainnet());
    }

    /**
     * The request with {@code wallet} as the designated input, {@code collateral} as C and {@code changeAddress} as
     * fee payer and change; the window in mainnet slots. {@code oracle} is {@code null} — ADA collateral has no
     * oracle leg — and {@code principalOracle} is the leg's entry.
     */
    static LiquidatePayInAdvanceTransactionBuilder.Request request(Oracle oracle, Utxo wallet, Utxo collateral,
                                                                   String changeAddress, long validFromMillis,
                                                                   long validToMillis) {
        return new LiquidatePayInAdvanceTransactionBuilder.Request(loan(), loanUtxo(), bond(), bondUtxo(), wallet,
                StrikeUsdmFixture.configUtxo(), StrikeUsdmFixture.lmConfigUtxo(), null, oracle.leg().entry(),
                validFromMillis, validToMillis, StrikeUsdmFixture.slot(validFromMillis),
                StrikeUsdmFixture.slot(validToMillis), changeAddress, StrikeUsdmFixture.REFERENCE_SCRIPTS,
                StrikeUsdmFixture.ORACLE_WINDOW_MARGIN_MILLIS, collateral);
    }
}
