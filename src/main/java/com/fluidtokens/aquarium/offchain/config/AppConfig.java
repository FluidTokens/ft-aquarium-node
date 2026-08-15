package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Arrays;

@Configuration
@EnableScheduling
@Slf4j
public class AppConfig {

    @Component
    @Getter
    public static class Network {

        @Value("${network}")
        private String network;

        public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
            return switch (network) {
                case "preprod" -> Networks.preprod();
                case "preview" -> Networks.preview();
                default -> Networks.mainnet();
            };
        }

    }

    @Component
    @Getter
    public static class AquariumConfiguration {

        @Value("${aquarium.staking.token.policy}")
        private String stakingTokenPolicy;

        @Value("${aquarium.staking.token.name}")
        private String stakingTokenName;

        // Genesis one-shot UTxO that parameterises the `parameters` validator.
        // Together with the staking token (= FLDT policy/asset) this derives every
        // Aquarium script hash at startup (see ContractRegistry).
        @Value("${aquarium.genesis.tx-hash}")
        private String genesisTxHash;

        @Value("${aquarium.genesis.output-index}")
        private Integer genesisOutputIndex;

        @Value("${aquarium.tank.ref-input.txHash}")
        private String tankRefInputTxHash;

        @Value("${aquarium.tank.ref-input.outputIndex}")
        private Integer tankRefInputOutputIndex;

        public TransactionInput getTankRefInput() {
            return TransactionInput.builder().transactionId(tankRefInputTxHash).index(tankRefInputOutputIndex).build();
        }

    }


    /**
     * FluidTokens Lending v4 ("loans") derivation inputs. The whole v4 contract tree
     * hangs off two one-shot config NFT policy ids, so these three values are enough
     * to derive every script hash we need (see LoansContractRegistry).
     */
    @Component
    @Getter
    public static class LoansConfiguration {

        @Value("${loans.enabled:false}")
        private boolean enabled;

        /** Main config NFT policy id = hash of the applied {@code config(tx0, index0)} validator. */
        @Value("${loans.config.policy-id:}")
        private String configPolicyId;

        /** LenderManager config NFT policy id = hash of the applied {@code lm_config(tx0, index0)}. */
        @Value("${loans.lm-config.policy-id:}")
        private String lmConfigPolicyId;

        /**
         * Hex of "parameters". Hardcoded in lib/fluidtokens/constants.ak, so this is
         * effectively a constant — exposed only so a future contract revision does not
         * force a code change.
         */
        @Value("${loans.config.asset-name:706172616d6574657273}")
        private String configAssetName;

        /**
         * Not derivable from the blueprint (no smart_tokens validator is bundled); it is
         * published in the on-chain ConfigDatum. Optional: without it the pool-manager
         * branch of the derivation is skipped, which the liquidation path does not need.
         */
        @Value("${loans.smart-tokens-spend-script-hash:}")
        private String smartTokensSpendScriptHash;

        /** The tx that minted both config NFTs; the point history has to be indexed from. */
        @Value("${loans.config.ref-utxo-tx-hash:}")
        private String configRefUtxoTxHash;

    }


    /**
     * The auto-liquidation bot's own knobs. Split out of {@link LoansConfiguration} because they
     * govern <em>acting</em> on lending data rather than reading it: {@code loans.enabled} decides
     * whether v4 is indexed at all, these decide what the bot does with what it sees.
     * <p>
     * <b>Conditional on {@code loans.enabled}</b>, unlike its sibling configuration classes, and for
     * a reason that only applies to this one: {@link #parseMode()} <em>aborts startup</em> on an
     * unrecognised mode. On a mainnet node lending is off and every bean that reads these values is
     * absent, so binding them there would let a typo in {@code AQUARIUM_LIQUIDATION_MODE} refuse to
     * start production software over a knob that governs nothing on it. Where the fail-fast is
     * useful — a node that actually runs the bot — the condition is satisfied and it still fires.
     */
    @Component
    @Getter
    @NoArgsConstructor
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public static class LiquidationConfiguration {

        /**
         * What the liquidation loop is allowed to do.
         * <ul>
         *   <li>{@code DISABLED} — the loop returns before it scans anything.</li>
         *   <li>{@code SHADOW} — scan, build, price and record; never sign, never submit.</li>
         *   <li>{@code LIVE} — reserved for the arming slice. Nothing in this codebase submits a
         *       liquidation yet, so today it behaves exactly like {@code SHADOW}; only
         *       {@link #isArmed()} tells the two apart.</li>
         * </ul>
         */
        public enum Mode {
            DISABLED, SHADOW, LIVE
        }

        /**
         * The raw {@code loans.liquidation.mode} string. Bound as text rather than as the enum so an
         * unrecognised value can be reported with the legal set instead of Spring's generic
         * conversion failure.
         */
        @Value("${loans.liquidation.mode:disabled}")
        private String modeName;

        private Mode mode;

        /**
         * Package-private so the parse rules can be driven without a Spring context. {@code @Value}
         * owns this field in production; nothing outside this package may write it.
         */
        void setModeName(String modeName) {
            this.modeName = modeName;
        }

        /**
         * The arming flag, deliberately separate from {@link #mode}. Submitting requires BOTH
         * {@code mode == LIVE} and this — one switch is too easy to flip by accident.
         */
        @Value("${loans.liquidation.enabled:false}")
        private boolean enabled;

        @Value("${loans.liquidation.delay-seconds:60}")
        private long delaySeconds;

        /** How far past "now" the built transaction's validity interval extends. */
        @Value("${loans.liquidation.validity-window-seconds:120}")
        private long validityWindowSeconds;

        /** How much of each oracle feed's window must still be unused after the tx's {@code validTo}. */
        @Value("${loans.liquidation.oracle-window-margin-seconds:30}")
        private long oracleWindowMarginSeconds;

        /** The lovelace a liquidation must clear over its own tx fee before it is worth doing. */
        @Value("${loans.liquidation.profit-margin-lovelace:1500000}")
        private BigInteger profitMarginLovelace;

        @Value("${loans.liquidation.decision-log-size:200}")
        private int decisionLogSize;

        @Value("${loans.liquidation.quarantine-minutes:30}")
        private long quarantineMinutes;

        /**
         * A fully specified configuration, for callers that have no Spring context to bind from —
         * the liquidation tests build their collaborators by hand. The mode arrives already typed,
         * so {@link #parseMode()} has nothing to validate and is not run.
         */
        public LiquidationConfiguration(Mode mode, boolean enabled, long delaySeconds,
                                        long validityWindowSeconds, long oracleWindowMarginSeconds,
                                        BigInteger profitMarginLovelace, int decisionLogSize,
                                        long quarantineMinutes) {
            this.mode = mode;
            this.modeName = mode.name();
            this.enabled = enabled;
            this.delaySeconds = delaySeconds;
            this.validityWindowSeconds = validityWindowSeconds;
            this.oracleWindowMarginSeconds = oracleWindowMarginSeconds;
            this.profitMarginLovelace = profitMarginLovelace;
            this.decisionLogSize = decisionLogSize;
            this.quarantineMinutes = quarantineMinutes;
        }

        /**
         * Fails the context rather than defaulting: a typo in the mode must not silently leave the
         * bot in whatever state the author of the typo did not intend — in either direction.
         */
        @PostConstruct
        void parseMode() {
            String configured = modeName == null ? "" : modeName.trim();
            mode = Arrays.stream(Mode.values())
                    .filter(candidate -> candidate.name().equalsIgnoreCase(configured))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "loans.liquidation.mode is '%s'; the legal values are %s (case-insensitive)"
                                    .formatted(modeName, Arrays.toString(Mode.values()))));
            log.info("INIT - liquidation mode: {}, armed: {}", mode, isArmed());
        }

        /** Whether the bot may move value. False for the whole shadow workstream, by construction. */
        public boolean isArmed() {
            return mode == Mode.LIVE && enabled;
        }

    }


    @Bean
    public CardanoConverters cardanoConverters(@Value("${network}") String network) {
        var networkType = switch (network) {
            case "preprod" -> NetworkType.PREPROD;
            case "preview" -> NetworkType.PREVIEW;
            default -> NetworkType.MAINNET;
        };
        log.info("INIT Converters network: {}, network type: {}", network, networkType);
        return ClasspathConversionsFactory.createConverters(networkType);
    }

}
