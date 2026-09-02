package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
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

        /**
         * Whether the profitability floors run at all. {@code false} is the operator's "liquidate
         * regardless" (SPEC §3 Q3.1): the absolute floor is not applied. Defaults to {@code true} —
         * profitability is checked — because a floor that silently does not floor is worse than an
         * absent one. The margin lever (below) is a separate control and is always applied.
         */
        @Value("${loans.liquidation.check-profitability:true}")
        private boolean checkProfitability;

        /**
         * The absolute lovelace floor a liquidation's <em>margin-excluded</em> profit
         * ({@code floorProfit = fee − txFee − minAdaFunded}) must reach, applied only when
         * {@link #checkProfitability} is true. Default 0: a floor that is active but permits any
         * non-negative floored profit. It is a floor on {@code floorProfit}, NOT on the
         * margin-adjusted number, so a negative margin can no longer inflate a loss past it.
         * <p>
         * SPEC §5 Finding 3 / O-2: the {@code fee} term inside {@code floorProfit} is a
         * mark-to-oracle token value, so on token collateral this floor still compares ADA against
         * tokens. Resolving that is O-2, out of scope here; this field carries today's fee term
         * unchanged.
         */
        @Value("${loans.liquidation.min-profit-absolute-lovelace:0}")
        private BigInteger minProfitAbsoluteLovelace;

        /**
         * The floor the <em>margin-adjusted</em> profit ({@code expectedProfit = floorProfit − margin})
         * must exceed. Default 0, which is exactly the behaviour this replaced: a liquidation must
         * clear the operator's margin strictly.
         *
         * <p><b>It may be negative, and that is the point.</b> Giovanni, 2026-08-27: <i>"we need to
         * allow operating at a loss on mainnet. not the default setting but it must be possible. or
         * the bot is unusable."</i> The intended operator is a team clearing bad debt to protect
         * their own platform, for whom a liquidation that loses ada is still worth making.
         *
         * <p>A negative value here is a number the operator <b>states</b>, not a protection they
         * <b>switch off</b>. That distinction is the whole design: {@code ignore-profit-check} is a
         * boolean that disables both gates and hard-fails on mainnet, and a switched-off protection
         * is indistinguishable from a misconfiguration. A stated negative bound is an intention
         * nobody reaches by copying a preview config, and it still bounds the loss — a liquidation
         * worse than the stated figure is refused.
         *
         * <p>⚠ To actually run at a loss, BOTH floors must be moved: this one and
         * {@link #minProfitAbsoluteLovelace}, which tests the margin-excluded number and refuses a
         * negative {@code floorProfit} independently. That is deliberate, not an oversight — see the
         * gate comments in {@code LiquidationExecutor}.
         */
        @Value("${loans.liquidation.min-expected-profit-lovelace:0}")
        private BigInteger minExpectedProfitLovelace;

        /**
         * ⛔ TEST-ONLY: bypass BOTH profitability gates and liquidate regardless of loss.
         * <p>
         * Not a third threshold — a boolean that skips the absolute floor
         * ({@link #minProfitAbsoluteLovelace}) <em>and</em> the margin
         * ({@link #profitMarginLovelace}) together. Reaching only one of the two would be worse than
         * useless: a loan whose floored profit is negative is refused by the floor no matter what the
         * margin says, so a flag that cleared only the margin would look enabled and change nothing.
         * <p>
         * It replaces the negative-margin idiom, which is a magic number that looks like it does this
         * and does not. A named boolean says what it means and can be grepped for.
         * <p>
         * <b>Refused on mainnet at startup</b>, exactly as a negative margin is: this disables loss
         * protection on someone else's collateral, and "copy the working preview config" is the
         * foreseeable operator action.
         */
        @Value("${loans.liquidation.ignore-profit-check:false}")
        private boolean ignoreProfitCheck;

        @Value("${loans.liquidation.decision-log-size:200}")
        private int decisionLogSize;

        @Value("${loans.liquidation.quarantine-minutes:30}")
        private long quarantineMinutes;

        // ---- published reference scripts -----------------------------------------------------
        //
        // One `txHash#index` per validator a Liquidate transaction invokes. Empty means "not
        // published": that validator travels in the witness set instead, which is a legal
        // transaction and simply a much larger one. With none of them published the six applied
        // validators (18_584 bytes, measured) blow straight past maxTxSize, so on any network where
        // the bot is meant to actually submit, these have to be filled in.

        @Value("${loans.liquidation.reference-scripts.loan:}")
        private String referenceScriptLoan;

        @Value("${loans.liquidation.reference-scripts.loan-spend:}")
        private String referenceScriptLoanSpend;

        @Value("${loans.liquidation.reference-scripts.lender-manager:}")
        private String referenceScriptLenderManager;

        @Value("${loans.liquidation.reference-scripts.lender-manager-spend:}")
        private String referenceScriptLenderManagerSpend;

        @Value("${loans.liquidation.reference-scripts.loan-claim-action:}")
        private String referenceScriptLoanClaimAction;

        @Value("${loans.liquidation.reference-scripts.lm-liquidate-action:}")
        private String referenceScriptLmLiquidateAction;

        @Value("${loans.liquidation.reference-scripts.asset-manager:}")
        private String referenceScriptAssetManager;

        /**
         * The convert path's own action validator. Distinct from {@code lm-liquidate-action}, which
         * is the PLAIN path's: measured on the fourth deployment this one is 7,051 bytes against that
         * one's 4,227, and it is the largest script left inline on a convert liquidation. Until this
         * key existed it could not be referenced at all, which is why a convert transaction sat at
         * 20,548 bytes against a 16,384 limit with everything else already published.
         */
        @Value("${loans.liquidation.reference-scripts.lm-liquidate-and-pay-in-advance-action:}")
        private String referenceScriptLmLiquidateAndPayInAdvanceAction;

        /**
         * The parsed form of the eight keys above, as {@link LiquidateTransactionBuilder} wants
         * them: a {@code null} field per validator that is not published.
         */
        private LiquidateTransactionBuilder.ReferenceScripts referenceScripts =
                LiquidateTransactionBuilder.ReferenceScripts.none();

        /**
         * A fully specified configuration, for callers that have no Spring context to bind from —
         * the liquidation tests build their collaborators by hand. The mode arrives already typed,
         * so {@link #parseMode()} has nothing to validate and is not run, and no reference script is
         * published (which is what {@code ReferenceScripts.none()} means).
         */
        public LiquidationConfiguration(Mode mode, boolean enabled, long delaySeconds,
                                        long validityWindowSeconds, long oracleWindowMarginSeconds,
                                        BigInteger profitMarginLovelace, int decisionLogSize,
                                        long quarantineMinutes) {
            this(mode, enabled, delaySeconds, validityWindowSeconds, oracleWindowMarginSeconds,
                    profitMarginLovelace, decisionLogSize, quarantineMinutes,
                    LiquidateTransactionBuilder.ReferenceScripts.none());
        }

        /** As above, with the published reference scripts stated. */
        public LiquidationConfiguration(Mode mode, boolean enabled, long delaySeconds,
                                        long validityWindowSeconds, long oracleWindowMarginSeconds,
                                        BigInteger profitMarginLovelace, int decisionLogSize,
                                        long quarantineMinutes,
                                        LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
            // The profitability floors default to the safe pair: checking on, absolute floor at 0.
            this(mode, enabled, delaySeconds, validityWindowSeconds, oracleWindowMarginSeconds,
                    profitMarginLovelace, decisionLogSize, quarantineMinutes, true, BigInteger.ZERO,
                    referenceScripts);
        }

        /** As above, with the profitability floors stated. */
        public LiquidationConfiguration(Mode mode, boolean enabled, long delaySeconds,
                                        long validityWindowSeconds, long oracleWindowMarginSeconds,
                                        BigInteger profitMarginLovelace, int decisionLogSize,
                                        long quarantineMinutes, boolean checkProfitability,
                                        BigInteger minProfitAbsoluteLovelace,
                                        LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
            this(mode, enabled, delaySeconds, validityWindowSeconds, oracleWindowMarginSeconds,
                    profitMarginLovelace, decisionLogSize, quarantineMinutes, checkProfitability,
                    minProfitAbsoluteLovelace, BigInteger.ZERO, referenceScripts);
        }

        /**
         * As above, with the margin-adjusted floor stated too. The only constructor that can express
         * a loss-tolerant configuration; every other overload pins {@code minExpectedProfitLovelace}
         * to zero, so a test that does not ask for loss tolerance cannot acquire it by accident.
         */
        public LiquidationConfiguration(Mode mode, boolean enabled, long delaySeconds,
                                        long validityWindowSeconds, long oracleWindowMarginSeconds,
                                        BigInteger profitMarginLovelace, int decisionLogSize,
                                        long quarantineMinutes, boolean checkProfitability,
                                        BigInteger minProfitAbsoluteLovelace,
                                        BigInteger minExpectedProfitLovelace,
                                        LiquidateTransactionBuilder.ReferenceScripts referenceScripts) {
            this.minExpectedProfitLovelace = minExpectedProfitLovelace;
            this.mode = mode;
            this.modeName = mode.name();
            this.enabled = enabled;
            this.delaySeconds = delaySeconds;
            this.validityWindowSeconds = validityWindowSeconds;
            this.oracleWindowMarginSeconds = oracleWindowMarginSeconds;
            this.profitMarginLovelace = profitMarginLovelace;
            this.decisionLogSize = decisionLogSize;
            this.quarantineMinutes = quarantineMinutes;
            this.checkProfitability = checkProfitability;
            this.minProfitAbsoluteLovelace = minProfitAbsoluteLovelace;
            this.referenceScripts = referenceScripts;
        }

        /**
         * The single startup hook. Both halves fail the context rather than defaulting, and both are
         * separately invokable so their rules can be driven from a test; this method exists only so
         * there is exactly one {@code @PostConstruct} on the bean.
         */
        @PostConstruct
        void init() {
            parseMode();
            parseReferenceScripts();
        }

        /**
         * Fails the context rather than defaulting: a typo in the mode must not silently leave the
         * bot in whatever state the author of the typo did not intend — in either direction.
         */
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

        // ---- reference-script parsing --------------------------------------------------------

        /**
         * Package-private so the parse rules can be driven without a Spring context, exactly like
         * {@link #setModeName(String)}. {@code @Value} owns these fields in production.
         */
        void setReferenceScriptCoordinates(String loan, String loanSpend, String lenderManager,
                                           String lenderManagerSpend, String loanClaimAction,
                                           String lmLiquidateAction, String assetManager) {
            this.referenceScriptLoan = loan;
            this.referenceScriptLoanSpend = loanSpend;
            this.referenceScriptLenderManager = lenderManager;
            this.referenceScriptLenderManagerSpend = lenderManagerSpend;
            this.referenceScriptLoanClaimAction = loanClaimAction;
            this.referenceScriptLmLiquidateAction = lmLiquidateAction;
            this.referenceScriptAssetManager = assetManager;
        }

        /**
         * Fails the context on a malformed coordinate rather than dropping it, and for the same
         * reason {@link #parseMode()} does: a typo that silently became "not published" would move
         * the validator back into the witness set, and the only symptom would be every candidate
         * refusing on a size the operator believed they had already fixed.
         */
        void parseReferenceScripts() {
            referenceScripts = new LiquidateTransactionBuilder.ReferenceScripts(
                    referenceInput("loans.liquidation.reference-scripts.loan", referenceScriptLoan),
                    referenceInput("loans.liquidation.reference-scripts.loan-spend", referenceScriptLoanSpend),
                    referenceInput("loans.liquidation.reference-scripts.lender-manager",
                            referenceScriptLenderManager),
                    referenceInput("loans.liquidation.reference-scripts.lender-manager-spend",
                            referenceScriptLenderManagerSpend),
                    referenceInput("loans.liquidation.reference-scripts.loan-claim-action",
                            referenceScriptLoanClaimAction),
                    referenceInput("loans.liquidation.reference-scripts.lm-liquidate-action",
                            referenceScriptLmLiquidateAction),
                    referenceInput("loans.liquidation.reference-scripts.asset-manager",
                            referenceScriptAssetManager),
                    referenceInput("loans.liquidation.reference-scripts.lm-liquidate-and-pay-in-advance-action",
                            referenceScriptLmLiquidateAndPayInAdvanceAction));
            log.info("INIT - liquidation reference scripts: {}", referenceScripts);
        }

        /**
         * {@code txHash#index}, or empty for "not published". Every rejection names the key, because
         * the value alone does not tell an operator which of seven near-identical lines to fix.
         */
        static TransactionInput referenceInput(String key, String value) {
            String coordinate = value == null ? "" : value.trim();
            if (coordinate.isEmpty()) {
                return null;
            }
            String[] parts = coordinate.split("#", -1);
            if (parts.length != 2) {
                throw new IllegalStateException("%s is '%s'; the expected form is txHash#index"
                        .formatted(key, value));
            }
            if (parts[0].length() != 64 || !parts[0].chars().allMatch(AppConfig::isHexDigit)) {
                throw new IllegalStateException(
                        "%s is '%s'; '%s' is not a 64-character hex transaction hash"
                                .formatted(key, value, parts[0]));
            }
            int index;
            try {
                index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("%s is '%s'; '%s' is not an output index"
                        .formatted(key, value, parts[1]));
            }
            if (index < 0) {
                throw new IllegalStateException("%s is '%s'; an output index cannot be negative"
                        .formatted(key, value));
            }
            return TransactionInput.builder().transactionId(parts[0]).index(index).build();
        }

    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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

    /**
     * The repayment-escrow <b>compound</b> path — {@code lm_compound_action}, which collects a repaid
     * loan's principal from the asset manager and delivers it into the lender's pool, keeping the
     * pool owner's stated compounding fee (findings §20).
     *
     * <p>Deliberately a separate block from {@link LiquidationConfiguration} rather than more keys on
     * it. The two actions share a word and almost nothing else: liquidation advances the collateral's
     * value and is priced against an oracle; compound advances nothing and its entire outlay is the
     * transaction fee (findings §20.3). Sharing a margin key would let a number reasoned about for one
     * silently govern the other.
     */
    @Component
    @Getter
    @NoArgsConstructor
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public static class CompoundConfiguration {

        /**
         * The arming flag. Default {@code false}: the node indexes and assesses compound candidates
         * but builds nothing until an operator turns this on, exactly as the liquidation path does.
         */
        @Value("${loans.compound.enabled:false}")
        private boolean enabled;

        @Value("${loans.compound.delay-seconds:60}")
        private long delaySeconds;

        /**
         * What {@code expectedFee - txFee} must reach for a compound to be built, in lovelace.
         *
         * <p><b>Default {@code 0} refuses every net loss</b> while allowing exact break-even. That is
         * the safe default and it is what makes a zero-fee pool refused out of the box: such a pool
         * pays nothing, so the net is exactly minus the transaction fee and the floor rejects it.
         *
         * <p>⚠ <b>Why the default is 0 here and 1_500_000 on the liquidation path.</b> That margin
         * demands a premium over cost because a liquidation moves someone else's collateral against an
         * oracle price and carries real risk. A compound moves already-repaid principal into the pool
         * that is owed it; the bot advances nothing and risks only its own fee, so requiring a premium
         * would refuse sound work for no reason. The floors differ because the actions differ.
         *
         * <p><b>A negative value is a number the operator STATES, not a protection they switch off</b>
         * — the same distinction the liquidation margin draws, and the reason there is no
         * {@code ignore-profit-check} twin here. Giovanni's ruling, 2026-09-02: <i>"there should be a
         * check compoudingFeePerMille in the bot to accept to process if zero … as long as operator
         * checks this himself and owns it."</i> Setting this negative IS that act of owning it: it
         * arms compounding for pools that pay nothing, and it still bounds the loss, because a
         * candidate worse than the stated figure is refused.
         *
         * <p>The fee is set by the <b>pool owner</b> in the live {@code PoolManagerDatum} — not by
         * this node, not by Giovanni, and not fixed by the protocol. Measured 2026-09-02: the only
         * live preview pool published {@code compoudingFeePerMille = 0}.
         *
         * <p>Refused on mainnet at startup when negative, exactly as the liquidation margin is.
         */
        @Value("${loans.compound.profit-margin-lovelace:0}")
        private BigInteger profitMarginLovelace;

        /** Test seam: {@code @Value} owns these in production. */
        public CompoundConfiguration(boolean enabled, long delaySeconds, BigInteger profitMarginLovelace) {
            this.enabled = enabled;
            this.delaySeconds = delaySeconds;
            this.profitMarginLovelace = profitMarginLovelace;
        }
    }

}
