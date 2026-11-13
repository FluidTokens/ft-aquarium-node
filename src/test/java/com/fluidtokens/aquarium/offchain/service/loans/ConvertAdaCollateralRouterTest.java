package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.Recording;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.BOND_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_TX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.USDM_FEED;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.spec;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.BOT_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.COLLATERAL_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONVERTERS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_ORDER_SPEND;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_POOL_POLICY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.MS_POOL_SPEND;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.POOL_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.USDM_V2_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.WALLET_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.withdrawalIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-224: the convert ROUTER serves an ADA collateral — the recorded mainnet ADA→USDM loan {@code 2d067dae…#1}
 * ({@link ConvertAdaCollateralFixture}), routed through {@code YaciConfig.convertLiquidationRouter} over the production
 * builder and the real Aiken evaluator, as {@code ConvertTokenToTokenRigTest} (j) wires a token convert.
 * <p>
 * Until FAB-224 the router refused an ada collateral by name, and unrefused it would have looked up a collateral
 * oracle ada does not have and asked the wallet selector for the order's whole lovelace (the swappable ada plus the
 * order overhead). Here, per Giovanni's ruling of 2026-11-12, the convert is paid from the collateral: no wallet input
 * when its liquidation fee covers the order overhead, the bot's own outputs and the transaction fee
 * ({@code ConvertLiquidationRouter.adaCollateralWalletShortfall}), otherwise one wallet utxo sized to the shortfall
 * alone; the economics are priced with the unit feed, and a small equity's min-ada top-up is charged to them.
 * <p>
 * Every figure the economics are checked against is read off the BUILT body the builder spy captured, never predicted.
 */
@Slf4j
class ConvertAdaCollateralRouterTest {

    private static final BigInteger ORDER_OVERHEAD = BigInteger.valueOf(4_000_000L);
    private static final BigInteger DEX_FLOOR = BigInteger.valueOf(5_000_000L);
    private static final BigInteger U = BigInteger.valueOf(50_000_000L);

    /** One router, wired as production wires it, over a spy of the production builder. */
    private static final class Wired {
        final ConvertAdaCollateralFixture f;
        final Recording evaluator;
        final ConvertTransactionBuilder builder;
        final ConvertEconomics economics;
        final ConvertLiquidationRouter router;
        final AtomicReference<Transaction> captured = new AtomicReference<>();
        final List<BigInteger> asked = new CopyOnWriteArrayList<>();

        Wired(ConvertAdaCollateralFixture f, long marginLovelace) throws Exception {
            this.f = f;
            Map<String, TransactionInput> refs = f.referenceScripts;
            var references = new LiquidateTransactionBuilder.ReferenceScripts(refs.get(REGISTRY.getLoanPolicyId()),
                    refs.get(REGISTRY.getLoanSpendScriptHash()), refs.get(REGISTRY.getLenderManagerWithdrawScriptHash()),
                    refs.get(REGISTRY.getLenderManagerSpendScriptHash()), refs.get(REGISTRY.getLoanClaimActionScriptHash()),
                    null, null, null, refs.get(REGISTRY.getLmLiquidateAndConvertActionScriptHash()));
            var liq = new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30,
                    BigInteger.valueOf(marginLovelace), 200, references);
            AppConfig.Network mainnet = new AppConfig.Network();
            mainnet.setNetworkForTest("mainnet");
            this.economics = new ConvertEconomics(new AppConfig.ConvertConfiguration(true), liq, mainnet);
            UtxoRepository poolIndex = mock(UtxoRepository.class);
            when(poolIndex.findUnspentByOwnerPaymentCredential(eq(MS_POOL_SPEND), any()))
                    .thenReturn(Optional.of(List.of(ConvertAdaCollateralFixture.poolRow())));
            this.evaluator = new Recording(f.aiken());
            this.builder = spy(f.production(evaluator));
            doAnswer(invocation -> {
                Transaction built = (Transaction) invocation.callRealMethod();
                captured.set(built);
                return built;
            }).when(builder).build(any());
            this.router = new YaciConfig().convertLiquidationRouter(REGISTRY, minswapConfigured(),
                    new MinswapPoolResolver(poolIndex, MS_POOL_SPEND, MS_POOL_POLICY), economics, builder, liq,
                    CONVERTERS, mainnet);
        }

        /** The router's build at the default window, the selector handing over {@code WALLET_UTXO}. */
        Transaction build() {
            return build(Optional.of(WALLET_UTXO), T0, T0 + 120_000L);
        }

        Transaction build(Optional<Utxo> selected, long fromMillis, long toMillis) {
            return router.buildConvertLiquidation(
                    LoanFixtures.assess(f.bond(), f.loan(), f.principalEntry.feed(), OraclePriceFeed.unit(), T0),
                    f.loanUtxo, f.bondUtxo, f.request.configUtxo(), f.request.lmConfigUtxo(), lovelace -> {
                        asked.add(lovelace);
                        return selected;
                    }, COLLATERAL_UTXO, Map.of(USDM_V2_NFT.toUnit(), f.principalEntry), BOT_ADDRESS,
                    fromMillis, toMillis);
        }

        /** The body the builder returned, deserialised — the BUILT transaction, whatever the router did after. */
        Transaction capturedBody() throws Exception {
            assertNotNull(captured.get(), "the builder was never asked to build");
            return Transaction.deserialize(captured.get().serialize());
        }
    }

    // ======================================================================================

    /**
     * (a) 40‰: the fee (324 ADA) covers the order overhead, the bot's outputs and the fee ceiling, so the selector is
     * never asked and the body spends exactly {loan, bond}; the build clears {@code ConvertEconomics}, whose figures
     * are pinned independently at the unit feed.
     */
    @Test
    void anAdaCollateralThatPaysForItselfIsBuiltWithNoWalletInput() throws Exception {
        Wired w = new Wired(spec().build(), 5_000_000L);

        Transaction returned = w.build();

        assertEquals(List.of(), w.asked, "the selector must not be asked: the collateral's fee pays for everything");
        assertSame(w.captured.get(), returned, "the router returns the body the builder built");
        Transaction body = w.capturedBody();
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX)),
                Set.copyOf(body.getBody().getInputs()), "the inputs are the loan and the bond only");
        assertEquals(2, body.getBody().getInputs().size(), "two inputs");
        withdrawalIndex(body, w.f.rig.scriptHash());
        assertEquals(1, w.evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");

        BigInteger fee = body.getBody().getFee();
        ConvertAssessment verdict = w.economics.assess(true, BigInteger.valueOf(8_100_000_000L), 40, true,
                OraclePriceFeed.unit(), fee, BigInteger.ZERO);
        BigInteger outlay = fee.add(ORDER_OVERHEAD).max(DEX_FLOOR);
        log.info("FAB-224 router-built ADA→USDM convert (no wallet input): fee {}, verdict {}", fee, verdict);
        assertTrue(verdict.approved(), verdict.toString());
        assertEquals(BigInteger.valueOf(324_000_000L), verdict.liquidationFee(), "40‰ of 8,100 ADA");
        assertEquals(BigInteger.valueOf(324_000_000L), verdict.feeValueLovelace(), "ada at the unit feed");
        assertEquals(fee.add(ORDER_OVERHEAD), verdict.measuredOutlay(), "measured = F + 4,000,000");
        assertEquals(outlay, verdict.outlay(), "outlay = max(F + 4,000,000, 5,000,000)");
        assertEquals(BigInteger.valueOf(324_000_000L).subtract(outlay), verdict.net(), "net = 324,000,000 − outlay");
        assertEquals(DEX_FLOOR, verdict.floor(), "the stated margin");
    }

    /**
     * (b) A SYNTHETIC 1‰ bond (fee 8,100,000): the fee leaves the bot 3,900,000 short of the order overhead and its
     * four outputs' min-ada ceilings, so the selector is asked for exactly that — never for the order's lovelace — and
     * the body spends exactly {loan, bond, U}.
     */
    @Test
    void aShortfallSpendsOneWalletUtxoSizedToTheShortfallNeverToTheSwap() throws Exception {
        Wired w = new Wired(spec().bondFeePerMille(1).build(), 0L);
        assertEquals(BigInteger.valueOf(7_624_969_525L), w.f.plan.orderLovelace(), "the order's lovelace");

        w.build();

        assertEquals(List.of(BigInteger.valueOf(3_900_000L)), w.asked, "asked for the shortfall alone");
        assertFalse(w.asked.contains(w.f.plan.orderLovelace()), "never for the order's lovelace");
        Transaction body = w.capturedBody();
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX),
                        new TransactionInput(WALLET_UTXO.getTxHash(), WALLET_UTXO.getOutputIndex())),
                Set.copyOf(body.getBody().getInputs()), "the loan, the bond and U, nothing else");
        assertEquals(3, body.getBody().getInputs().size(), "three inputs");
        BigInteger fee = body.getBody().getFee();
        assertEquals(BigInteger.valueOf(8_100_000L).subtract(ORDER_OVERHEAD).subtract(fee),
                coinAtBot(body.getBody().getOutputs()).subtract(U),
                "Σ coin(bot outputs) − 50,000,000 == 8,100,000 − 4,000,000 − F");
        assertEquals(1, w.evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
    }

    /** (c) The same 1‰ convert under a 5 ADA margin: refused {@code NET_BELOW_FLOOR}, every figure pinned. */
    @Test
    void aShortfallUnderTheMarginIsRefusedByConvertEconomicsWithItsFiguresPinned() throws Exception {
        Wired w = new Wired(spec().bondFeePerMille(1).build(), 5_000_000L);

        var refused = assertThrows(ConvertLiquidationRouter.UnprofitableException.class, w::build);

        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, refused.assessment().exclusion(), refused.getMessage());
        BigInteger fee = w.capturedBody().getBody().getFee();
        BigInteger measured = fee.add(ORDER_OVERHEAD);
        BigInteger outlay = measured.max(DEX_FLOOR);
        assertEquals("convert refused as NET_BELOW_FLOOR: fee 8100000 of collateral is worth 8100000 lovelace, outlay "
                        + outlay + " (measured " + measured + ", dex floor 5000000), net "
                        + BigInteger.valueOf(8_100_000L).subtract(outlay) + " against a floor of 5000000",
                refused.getMessage());
        assertEquals(List.of(BigInteger.valueOf(3_900_000L)), w.asked, "asked for the shortfall alone");
    }

    /**
     * (d) A SYNTHETIC 0‰ bond: the bot earns nothing, so at margin 0 it is refused with a net of minus its outlay, and
     * only an explicitly negative margin builds it — the wallet asked for 12,000,000 either way.
     */
    @Test
    void aLossMakingConvertIsBuiltOnlyUnderANegativeMargin() throws Exception {
        Wired atZero = new Wired(spec().bondFeePerMille(0).build(), 0L);
        var refused = assertThrows(ConvertLiquidationRouter.UnprofitableException.class, atZero::build);
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, refused.assessment().exclusion(), refused.getMessage());
        BigInteger zeroFee = atZero.capturedBody().getBody().getFee();
        BigInteger outlay = zeroFee.add(ORDER_OVERHEAD).max(DEX_FLOOR);
        assertEquals(outlay.negate(), refused.assessment().net(), "net == −outlay");
        assertEquals(BigInteger.ZERO, refused.assessment().floor(), "margin 0");
        assertEquals(List.of(BigInteger.valueOf(12_000_000L)), atZero.asked, "asked for the whole shortfall");

        Wired atLoss = new Wired(spec().bondFeePerMille(0).build(), -6_000_000L);
        atLoss.build();
        assertEquals(List.of(BigInteger.valueOf(12_000_000L)), atLoss.asked, "asked for the whole shortfall");
        Transaction body = atLoss.capturedBody();
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX),
                        new TransactionInput(WALLET_UTXO.getTxHash(), WALLET_UTXO.getOutputIndex())),
                Set.copyOf(body.getBody().getInputs()), "the loan, the bond and U, nothing else");
        BigInteger fee = body.getBody().getFee();
        assertEquals(ORDER_OVERHEAD.negate().subtract(fee), coinAtBot(body.getBody().getOutputs()).subtract(U),
                "Σ coin(bot outputs) − 50,000,000 == −4,000,000 − F");
    }

    /**
     * (e) USDM at 1,327,000,000 / 10⁸ leaves an equity of 979,792, raised to the ledger minimum by 468,368 out of the
     * bot's change: the router measures that top-up off the built body and charges it to {@code ConvertEconomics}.
     */
    @Test
    void theEquityTopUpIsChargedToConvertEconomics() throws Exception {
        Feed thin = new Feed(BigInteger.valueOf(1_327_000_000L), DENOMINATOR, USDM_FEED.validFrom(), USDM_FEED.validTo());
        Wired w = new Wired(spec().usdmFeed(thin).build(), 400_000_000L);
        assertEquals(BigInteger.valueOf(979_792L), w.f.equity, "a small equity");

        var refused = assertThrows(ConvertLiquidationRouter.UnprofitableException.class, w::build);

        assertEquals(List.of(), w.asked, "the fee covers the top-up ceiling too: no wallet input");
        BigInteger fee = w.capturedBody().getBody().getFee();
        BigInteger measured = fee.add(ORDER_OVERHEAD).add(BigInteger.valueOf(468_368L));
        assertTrue(refused.getMessage().contains("(measured " + measured + ", dex floor 5000000)"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("net " + BigInteger.valueOf(324_000_000L)
                .subtract(fee.add(BigInteger.valueOf(4_468_368L)))), refused.getMessage());
        assertEquals(BigInteger.valueOf(468_368L), refused.assessment().measuredOutlay().subtract(fee)
                .subtract(ORDER_OVERHEAD), "the top-up is inside the measured outlay");
    }

    /** (f) The 1‰ shortfall and a wallet with nothing that covers it: refused naming the shortfall, nothing built. */
    @Test
    void aWalletThatCannotCoverTheShortfallIsRefusedNamingTheShortfall() throws Exception {
        Wired w = new Wired(spec().bondFeePerMille(1).build(), 0L);

        var refused = assertThrows(ConvertLiquidationRouter.WalletInputTooSmallException.class,
                () -> w.build(Optional.empty(), T0, T0 + 120_000L));

        assertTrue(refused.getMessage().contains("3900000 lovelace short"), refused.getMessage());
        assertFalse(refused.getMessage().contains("the collateral is a token"), refused.getMessage());
        assertEquals(List.of(BigInteger.valueOf(3_900_000L)), w.asked, "asked once, for the shortfall");
        verify(w.builder, never()).build(any());
        assertNull(w.captured.get(), "nothing was built");
    }

    /**
     * (g) An ada collateral has no oracle feed to window-check: a transaction window past the USDM feed's valid_to is
     * refused naming the PRINCIPAL feed, before the selector is asked and before anything is built.
     */
    @Test
    void anAdaCollateralChecksOnlyThePrincipalFeedWindow() throws Exception {
        Wired w = new Wired(spec().build(), 5_000_000L);
        assertEquals(T0 + 1_500_000L, w.f.principalEntry.feed().validTo(), "the USDM feed closes at T0 + 1,500,000");

        var refused = assertThrows(ConvertLiquidationRouter.FeedWindowException.class,
                () -> w.build(Optional.of(WALLET_UTXO), T0, T0 + 1_600_000L));

        assertTrue(refused.getMessage().contains("the principal feed window"), refused.getMessage());
        assertFalse(refused.getMessage().contains("collateral"), refused.getMessage());
        assertEquals(List.of(), w.asked, "the selector must not be asked");
        verify(w.builder, never()).build(any());
    }

    // ======================================================================================
    // The leaves
    // ======================================================================================

    private static BigInteger coinAtBot(List<TransactionOutput> outs) {
        return outs.stream().filter(o -> BOT_ADDRESS.equals(o.getAddress()))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static AppConfig.LoansConfiguration minswapConfigured() {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return POOL_ADDRESS;
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return MS_POOL_POLICY;
            }

            @Override
            public String getMinswapPoolSpendScriptHash() {
                return MS_POOL_SPEND;
            }

            @Override
            public String getMinswapOrderSpendScriptHash() {
                return MS_ORDER_SPEND;
            }
        };
    }
}
