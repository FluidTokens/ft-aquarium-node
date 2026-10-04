package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The reference inputs that are STATIC configuration (FAB-134 B5b) — the only out-refs
 * {@link com.fluidtokens.aquarium.offchain.storage.IndexFirstUtxoSupplier} may hold by out-ref:
 * <ul>
 *   <li>every published liquidation reference script, {@code loans.liquidation.reference-scripts.*}
 *       (every non-null slot, the pay-in-advance and convert actions included);</li>
 *   <li>the compound reference scripts, {@code loans.compound.reference-scripts} — parsed as
 *       {@code CompoundExecutor.referenceScripts()} parses them, a malformed coordinate skipped;</li>
 *   <li>the tank reference input, {@code aquarium.tank.ref-input.*};</li>
 *   <li>for every oracle registry entry, its feed UTxO, its script UTxO and its Charli3 provider UTxO.</li>
 * </ul>
 * <p>
 * <b>Recomputed on every call; no copy is kept.</b> The oracle registry refreshes on its own schedule, so a
 * feed that moves arrives as a NEW out-ref and the old one simply stops being in this set — which is
 * what evicts it from the hold. Re-resolving a static coordinate means re-reading its SOURCE (the
 * registry, the operator's config), never re-reading the same out-ref: an output's content at an out-ref
 * never changes, only its liveness does, and liveness is the ledger's to judge.
 * <p>
 * Every source is optional ({@link ObjectProvider}): {@link FluidOracleClient} is
 * {@code @ConditionalOnProperty(loans.oracle.enabled)}, and a hard dependency on it would fail the node's
 * startup wherever the oracle is off (CCL trap 9b). <b>Never throws</b>: a source that cannot be read
 * contributes nothing, which only means its out-refs are read from the provider on each miss — the safe
 * direction.
 */
@Component
@Slf4j
public class StaticReferenceInputs {

    private final ObjectProvider<AppConfig.LiquidationConfiguration> liquidation;

    private final ObjectProvider<AppConfig.CompoundConfiguration> compound;

    private final ObjectProvider<AppConfig.AquariumConfiguration> aquarium;

    private final ObjectProvider<FluidOracleClient> oracle;

    /** Malformed compound coordinates already warned about — once per distinct value, not once per miss. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public StaticReferenceInputs(ObjectProvider<AppConfig.LiquidationConfiguration> liquidation,
                                 ObjectProvider<AppConfig.CompoundConfiguration> compound,
                                 ObjectProvider<AppConfig.AquariumConfiguration> aquarium,
                                 ObjectProvider<FluidOracleClient> oracle) {
        this.liquidation = liquidation;
        this.compound = compound;
        this.aquarium = aquarium;
        this.oracle = oracle;
    }

    /** The current static set, computed now from its four sources. Never null, never throws. */
    public Set<TransactionInput> current() {
        Set<TransactionInput> inputs = new HashSet<>();
        Consumer<TransactionInput> add = input -> {
            if (input != null && input.getTransactionId() != null && !input.getTransactionId().isBlank()) {
                inputs.add(input);
            }
        };
        collect("liquidation reference scripts", () -> liquidationSlots(add));
        collect("compound reference scripts", () -> compoundCoordinates(add));
        collect("tank reference input", () -> {
            AppConfig.AquariumConfiguration configuration = aquarium.getIfAvailable();
            if (configuration != null && configuration.getTankRefInputTxHash() != null
                    && configuration.getTankRefInputOutputIndex() != null) {
                add.accept(configuration.getTankRefInput());
            }
        });
        collect("oracle registry", () -> {
            FluidOracleClient client = oracle.getIfAvailable();
            Collection<OracleEntry> entries = client == null ? null : client.entries();
            if (entries == null) {
                return;
            }
            for (OracleEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                add.accept(entry.referenceInput());
                add.accept(entry.referenceScript());
                add.accept(entry.charlieProviderReferenceInput());
            }
        });
        return inputs;
    }

    /** Every slot of the record, so a slot added to {@code ReferenceScripts} is enumerated without an edit here. */
    private void liquidationSlots(Consumer<TransactionInput> add) throws ReflectiveOperationException {
        AppConfig.LiquidationConfiguration configuration = liquidation.getIfAvailable();
        LiquidateTransactionBuilder.ReferenceScripts scripts =
                configuration == null ? null : configuration.getReferenceScripts();
        if (scripts == null) {
            return;
        }
        for (RecordComponent slot : LiquidateTransactionBuilder.ReferenceScripts.class.getRecordComponents()) {
            if (slot.getType() == TransactionInput.class) {
                add.accept((TransactionInput) slot.getAccessor().invoke(scripts));
            }
        }
    }

    /** {@code txHash#index}, comma-separated — skipped where malformed, as {@code CompoundExecutor} skips it. */
    private void compoundCoordinates(Consumer<TransactionInput> add) {
        AppConfig.CompoundConfiguration configuration = compound.getIfAvailable();
        String configured = configuration == null ? null : configuration.getReferenceScripts();
        if (configured == null || configured.isBlank()) {
            return;
        }
        for (String raw : configured.split(",")) {
            String coordinate = raw.trim();
            if (coordinate.isEmpty()) {
                continue;
            }
            String[] parts = coordinate.split("#");
            Integer index = null;
            if (parts.length == 2) {
                try {
                    index = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    index = null;
                }
            }
            if (index == null) {
                if (warned.add(coordinate)) {
                    log.warn("compound: reference-script coordinate '{}' is not txHash#index; it is not a "
                            + "static reference input", coordinate);
                }
                continue;
            }
            add.accept(TransactionInput.builder().transactionId(parts[0].trim()).index(index).build());
        }
    }

    private interface Source {
        void read() throws Exception;
    }

    private void collect(String name, Source source) {
        try {
            source.read();
        } catch (Exception e) {
            // Never thrown to the supplier: this source's out-refs are then plain provider reads.
            log.warn("static reference inputs: could not read the {} ({}); its out-refs are not held",
                    name, e.toString());
        }
    }
}
