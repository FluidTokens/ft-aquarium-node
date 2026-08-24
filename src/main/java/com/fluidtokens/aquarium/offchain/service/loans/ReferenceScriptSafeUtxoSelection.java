package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.coinselection.UtxoSelectionStrategy;
import com.bloxbean.cardano.client.coinselection.UtxoSelector;
import com.bloxbean.cardano.client.coinselection.impl.DefaultUtxoSelector;
import com.bloxbean.cardano.client.coinselection.impl.DefaultUtxoSelectionStrategyImpl;
import com.bloxbean.cardano.client.coinselection.impl.LargestFirstUtxoSelectionStrategy;

/**
 * Coin selection that will <b>never spend a UTxO carrying a reference script</b>.
 *
 * <h2>Why this exists</h2>
 * The bot publishes validators as reference scripts so the convert liquidation fits under
 * {@code maxTxSize} (23,462 bytes all-inline against a 16,384 limit — E1-BOT A4). On preview the
 * published {@code loan_claim_action} script sits in a UTxO <b>at the bot's own operational
 * address</b>, which means cardano-client-lib's balancer can see it as an ordinary source of ada.
 * <p>
 * Verified against the pinned cardano-client-lib <b>v0.7.2</b> source, not its documentation:
 * {@code DefaultUtxoSelectionStrategyImpl.select} filters candidates on {@code accept(utxo)},
 * {@code utxosToExclude}, datum hash and inline datum — and {@code accept} is
 * {@code return true}. <b>There is no reference-script exclusion anywhere in coin selection.</b>
 * So a balancing input can consume the published script, after which every convert liquidation is
 * refused {@code TX_TOO_LARGE} and the bot has silently and irreversibly disabled a capability it
 * still reports itself as having.
 * <p>
 * {@link LiquidationExecutor#adaOnlyWalletUtxo()} already refuses to <em>nominate</em> such a UTxO as
 * the wallet input. This closes the other door: the inputs cardano-client-lib adds on its own.
 *
 * <h2>The fallback trap — why overriding {@code accept} alone is not enough</h2>
 * {@code DefaultUtxoSelectionStrategyImpl.fallback()} returns a <b>plain</b>
 * {@link LargestFirstUtxoSelectionStrategy}, freshly constructed, whose own {@code accept} is again
 * {@code return true}. That fallback is taken whenever selection exceeds the input limit. Overriding
 * {@code accept} on the primary strategy and stopping there would leave a guard that holds in the
 * common case and silently lapses in the awkward one — the shape of defect this codebase has paid for
 * repeatedly. Both halves of the chain are therefore overridden below, and a test drives the fallback
 * path specifically.
 *
 * <h2>Two selectors, not one — the strategy alone is NOT enough</h2>
 * {@code ChangeOutputAdjustments.adjustChangeOutput} ({@code :218-244}) reaches for a
 * <b>{@link UtxoSelector}</b> FIRST — {@code context.getUtxoSelector().findFirst(addr, predicate)}
 * with a predicate that tests only "not already an input", "ada only" and "big enough" — and only
 * falls through to the {@code UtxoSelectionStrategy} when that finds nothing. Guarding the strategy
 * alone therefore guards the path that is tried SECOND. Measured, not reasoned: with the strategy
 * guarded and the selector not, a build still spent {@code 48c102c0…#0}.
 * <p>
 * {@code QuickTxBuilder.TxContext} exposes {@code withUtxoSelectionStrategy} but has <b>no</b>
 * {@code withUtxoSelector}, so the selector is installed through {@code preBalanceTx}, which hands
 * over the {@code TxBuilderContext} itself and runs before balancing.
 *
 * <h2>What this does NOT cover</h2>
 * <b>Collateral selection is not governed by this strategy.</b> {@code QuickTxBuilder}'s
 * {@code buildCollateralOutput} constructs its <em>own</em> {@code DefaultUtxoSelectionStrategyImpl}
 * rather than reading the one on the builder context, so {@code withUtxoSelectionStrategy} cannot
 * reach it; the only lever there is {@code withCollateralInputs}. That is a separate fix with its own
 * design question (the pinned collateral input is then excluded from ordinary coin selection, so it
 * cannot also be the UTxO fronting the principal) and is deliberately not attempted here.
 */
final class ReferenceScriptSafeUtxoSelection {

    private ReferenceScriptSafeUtxoSelection() {
    }

    /** A UTxO is spendable by the bot only if it is not carrying a published reference script. */
    static boolean spendable(Utxo utxo) {
        return utxo.getReferenceScriptHash() == null;
    }

    /**
     * The selector to install on the {@code TxBuilderContext} via {@code preBalanceTx}. It composes
     * the reference-script guard into whatever predicate the library passes, so it cannot be bypassed
     * by a caller-supplied predicate that does not know about reference scripts — which is exactly
     * what {@code ChangeOutputAdjustments} supplies.
     */
    static UtxoSelector selector(UtxoSupplier utxoSupplier) {
        DefaultUtxoSelector delegate = new DefaultUtxoSelector(utxoSupplier);
        return new UtxoSelector() {
            @Override
            public java.util.Optional<Utxo> findFirst(String address,
                                                      java.util.function.Predicate<Utxo> predicate)
                    throws ApiException {
                return delegate.findFirst(address,
                        predicate.and(ReferenceScriptSafeUtxoSelection::spendable));
            }

            @Override
            public java.util.Optional<Utxo> findFirst(String address,
                                                      java.util.function.Predicate<Utxo> predicate,
                                                      java.util.Set<Utxo> excludeUtxos)
                    throws ApiException {
                return delegate.findFirst(address,
                        predicate.and(ReferenceScriptSafeUtxoSelection::spendable), excludeUtxos);
            }

            @Override
            public java.util.List<Utxo> findAll(String address,
                                                java.util.function.Predicate<Utxo> predicate)
                    throws ApiException {
                return delegate.findAll(address,
                        predicate.and(ReferenceScriptSafeUtxoSelection::spendable));
            }

            @Override
            public java.util.List<Utxo> findAll(String address,
                                                java.util.function.Predicate<Utxo> predicate,
                                                java.util.Set<Utxo> excludeUtxos)
                    throws ApiException {
                return delegate.findAll(address,
                        predicate.and(ReferenceScriptSafeUtxoSelection::spendable), excludeUtxos);
            }
        };
    }

    /** The strategy to hand to {@code QuickTxBuilder.TxContext.withUtxoSelectionStrategy}. */
    static UtxoSelectionStrategy strategy(UtxoSupplier utxoSupplier) {
        return new DefaultUtxoSelectionStrategyImpl(utxoSupplier) {
            @Override
            protected boolean accept(Utxo utxo) {
                return spendable(utxo);
            }

            @Override
            public UtxoSelectionStrategy fallback() {
                // NOT super.fallback(): that hands back a plain LargestFirst whose accept() admits
                // everything, which would drop the guard exactly when selection is under pressure.
                return new LargestFirstUtxoSelectionStrategy(utxoSupplier) {
                    @Override
                    protected boolean accept(Utxo utxo) {
                        return spendable(utxo);
                    }
                };
            }
        };
    }
}
