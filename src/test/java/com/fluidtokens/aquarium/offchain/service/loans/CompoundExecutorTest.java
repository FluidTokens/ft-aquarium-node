package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound loop, driven end to end over the REAL recorded candidate {@code e833a769…} — the same
 * on-chain objects {@link CompoundDryEvalTest} evaluates. The builder is the real one, so these tests
 * exercise scan → build → price → submit rather than a mock of it.
 *
 * <p>The submitter is the only thing faked at the wire, and it records instead of transmitting.
 */
class CompoundExecutorTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);

    // ---- the recorded universe ---------------------------------------------------------------

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundExecutorTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("compound candidate fixture missing", e);
        }
    }

    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
    }

    private static List<Utxo> universe() {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                utxo("config"), utxo("lmConfig"), wallet());
    }

    private static CompoundCandidate ready(long feePerMille) {
        Utxo bondUtxo = utxo("bond");
        LenderBond bond = new LenderBond(bondUtxo.getTxHash(), bondUtxo.getOutputIndex(),
                bondUtxo.getAddress(), LOAN_ID, bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, bond, POOL_ID, utxo("pool"), utxo("poolManager"),
                feePerMille, true, null, "recorded preview candidate");
    }

    private static CompoundCandidate refused() {
        return new CompoundCandidate(LOAN_ID, utxo("escrow"), null, null, null, POOL_ID, null, null,
                0L, true, CompoundExclusion.POOL_NOT_LIVE, "the pool NFT is burned");
    }

    // ---- wiring ------------------------------------------------------------------------------

    private record Wiring(CompoundExecutor executor, List<byte[]> submitted) {
    }

    private static Wiring wiringWithWallet(List<CompoundCandidate> candidates, List<Utxo> walletUtxos) {
        return wiring(candidates, true, -2_000_000L, walletUtxos, "preview");
    }

    private static Wiring wiringOnNetwork(List<CompoundCandidate> candidates, String network) {
        return wiring(candidates, true, -2_000_000L, List.of(wallet()), network);
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor) {
        return wiring(candidates, armed, floor, List.of(wallet()), "preview");
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor,
                                 List<Utxo> walletUtxos, String networkName) {
        var configuration = new AppConfig.CompoundConfiguration(armed, 60L, BigInteger.valueOf(floor));
        var network = new AppConfig.Network();
        network.setNetworkForTest(networkName);

        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        List<byte[]> submitted = new ArrayList<>();
        CompoundExecutor.TransactionSubmitter submitter = bytes -> {
            submitted.add(bytes);
            return Result.success("deadbeefcafe").withValue("deadbeefcafe");
        };

        List<Utxo> universe = new java.util.ArrayList<>(universe());
        universe.addAll(walletUtxos);
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT,
                new FakeScanner(candidates), new CompoundEconomics(configuration, network),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), submitter);
        return new Wiring(executor, submitted);
    }

    // ---- the tests ---------------------------------------------------------------------------

    /**
     * ⛔ Disarmed is the default, and a READY candidate must still be reported. Silence here is the
     * failure mode an operator cannot distinguish from "nothing to do".
     */
    @Test
    void aReadyCandidateIsNotProcessedWhileDisarmed() {
        Wiring w = wiring(List.of(ready(0L)), false, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(), "a disarmed loop must transmit nothing");
    }

    /** ⛔ The zero-fee pool at the safe default: built, priced, and declined on the numbers. */
    @Test
    void aZeroFeePoolIsDeclinedAtTheDefaultFloor() {
        Wiring w = wiring(List.of(ready(0L)), true, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(),
                "expectedFee 0 minus a real tx fee is negative, and the default floor refuses it");
    }

    /**
     * ⛔ AND THE RULED CASE. The same candidate, the same zero fee, with the operator's stated
     * negative bound — this is the transaction that will actually fire on preview.
     */
    @Test
    void aStatedNegativeFloorProcessesTheZeroFeeCandidate() {
        Wiring w = wiring(List.of(ready(0L)), true, -2_000_000L);
        w.executor().cycle();
        assertEquals(1, w.submitted().size(),
                "with the loss stated and bounded, the armed loop compounds");
        assertTrue(w.submitted().getFirst().length > 0, "signed bytes reached the submitter");
    }

    /** A structurally refused candidate never reaches the builder. */
    @Test
    void aStructurallyRefusedCandidateIsReportedAndNotBuilt() {
        Wiring w = wiring(List.of(refused()), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** An empty scan is not an error and transmits nothing. */
    @Test
    void anEmptyScanIsQuietAndHarmless() {
        Wiring w = wiring(List.of(), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** Syncing is a hard skip: the index is not yet a view of the chain. */
    @Test
    void nothingRunsWhileTheIndexerIsSyncing() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(true);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a syncing node must not submit"); });

        executor.cycle();
        assertEquals(0, scans.get(), "a syncing node must not even scan");
    }

    /**
     * ⛔ THE PRODUCTION SHAPE, 2026-09-02. The wallet held 9,898 ada and could build nothing, because
     * its ONE utxo also carried a native token — CCL trap 17: a successful transaction's change
     * output disables the builder that made it. The publications that put the reference scripts on
     * chain consumed every ada-only utxo and swept the remainder, token included, into a single
     * change output.
     *
     * <p>The predicate is RIGHT — collateral must be pure ada (trap 16) — so this must SKIP, and the
     * log must say the wallet is the wrong SHAPE rather than empty.
     */
    @Test
    void aWalletWhoseOnlyUtxoCarriesATokenIsSkippedNotSpent() {
        Utxo tokenBearing = Utxo.builder().txHash("2e".repeat(32)).outputIndex(1)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(9_898_050_234L)),
                        Amount.builder().unit("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f620014df1074464c4454")
                                .quantity(BigInteger.valueOf(20_000_000L)).build()))
                .build();

        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tokenBearing));
        w.executor().cycle();

        assertTrue(w.submitted().isEmpty(),
                "9,898 ada on a token-bearing utxo is not spendable as collateral; it must skip");
    }

    /**
     * ⚠ And the LARGEST nominable is taken, not the first. A 1 ada utxo cannot cover the collateral
     * the ledger requires (150% of a ~0.84 ada fee), and nominating it produces a NEGATIVE collateral
     * return — trap 16, a transaction the node cannot even parse. Picking first-in-list would have
     * done exactly that here.
     */
    @Test
    void theLargestAdaOnlyUtxoIsNominatedNotTheFirst() {
        Utxo tiny = Utxo.builder().txHash("01".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_000_000L)))).build();
        Utxo ample = Utxo.builder().txHash("02".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();

        // tiny FIRST in the list — first-in-list selection would take it and fail on collateral.
        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tiny, ample));
        w.executor().cycle();

        assertEquals(1, w.submitted().size(),
                "the ample utxo must be nominated; taking the 1 ada one first would produce a "
                        + "negative collateral return and an unparseable transaction");
    }

    /**
     * ⛔ <b>THE INVERSE OF WHAT THESE THREE TESTS USED TO ASSERT.</b> Until 2026-09-04 this path read
     * {@code loans.submittable-network} and refused to submit unless it named the node's own network;
     * three tests here pinned that gate, its {@code preview} default, and the fact that stating
     * mainnet lifted it.
     *
     * <p>The key was removed on Giovanni's ruling — <i>"a barrier that silently blocks submission
     * even when everything else is armed is a bug, not a safeguard"</i> — so an armed compound node
     * now submits on whichever network it is pointed at. Kept as the regression guard: any
     * re-introduced network check inside {@code submit} turns this red.
     *
     * <p>⚠ <b>And it records what the removal costs on THIS path.</b> The liquidation executor still
     * has three switches ahead of a submission — mode {@code live}, {@code liquidation.enabled}, and
     * the market's effective mode — plus two profit floors. <b>Compound has {@code compound.enabled}
     * and one floor.</b> That asymmetry is exactly what the network value had been closing since
     * 2026-09-03, and removing it re-opens it. Not a reason to keep a gate found to be wrong; a
     * reason for compound to grow a second switch of its own.
     */
    @Test
    void anArmedNodeSubmitsOnWhicheverNetworkItIsPointedAt() {
        for (String networkName : List.of("mainnet", "preprod", "preview")) {
            Wiring w = wiringOnNetwork(List.of(ready(0L)), networkName);
            w.executor().cycle();
            assertEquals(1, w.submitted().size(),
                    "on " + networkName + ": armed, funded and profitable under the stated floor "
                            + "must submit — there is no second network value left to disagree with "
                            + "config.network");
        }
    }

    /**
     * ⚠ The one thing that DOES still hold this path back, asserted beside the removal so the pair
     * reads together: {@code loans.compound.enabled}. It is the whole list.
     */
    @Test
    void anUnarmedNodeStillSubmitsNothingOnAnyNetwork() {
        for (String networkName : List.of("mainnet", "preview")) {
            Wiring w = wiring(List.of(ready(0L)), false, -2_000_000L, List.of(wallet()), networkName);
            w.executor().cycle();
            assertTrue(w.submitted().isEmpty(),
                    "on " + networkName + ": loans.compound.enabled=false is now the ONLY policy "
                            + "switch on this path, so it had better hold");
        }
    }

    // ---- fakes -------------------------------------------------------------------------------

    private static class FakeScanner extends CompoundCandidateScanner {
        private final List<CompoundCandidate> candidates;

        FakeScanner(List<CompoundCandidate> candidates) {
            super(null, REGISTRY, null, null);
            this.candidates = candidates;
        }

        @Override
        public Scan scan() {
            return new Scan(candidates);
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        FakeResolver() {
            super(null, null, null);
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.of(utxo("bond"));
        }

        @Override
        public Optional<Utxo> resolveConfigUtxo() {
            return Optional.of(utxo("config"));
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(utxo("lmConfig"));
        }
    }

    private static final class FakeAppUtxoService extends AppUtxoService {
        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
        }
    }
}
