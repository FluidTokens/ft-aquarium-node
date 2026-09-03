package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableScheduling
@Slf4j
public class AppConfig {

    @Component
    @Getter
    public static class Network {

        @Value("${network}")
        private String network;

        /**
         * The one network this node is permitted to SUBMIT on. Ordinary configuration — Giovanni's
         * ruling, 2026-09-03: <i>"if someone is operating this bot they KNOW they're doing financial
         * stuff on a network … putting too many gates is only annoying and won't really protect
         * anyone. No different image pls. Configuration configuration configuration. The defaults
         * must be defensive; if an operator copy-pastes from preview it's their problem."</i>
         *
         * <p>⛔ <b>THE DEFAULT IS THE PROTECTION.</b> It is {@code preview}, so a node that says
         * nothing about this cannot submit on mainnet — and since 1d17e9a mainnet is the DEFAULT
         * PROFILE, which is exactly why this default has to lean the other way. Submitting on mainnet
         * is a thing an operator writes down on purpose.
         *
         * <p>This replaced a hard-coded {@code "preview"} constant in {@code LiquidationExecutor}.
         * The constant was safer in one narrow sense and unusable for its actual purpose: a bot
         * meant to run on mainnet could never do so without a code change and a bespoke artefact.
         *
         * <p><b>Both executors read this one value</b> — the liquidation path and the compound path.
         * That is not a new gate; it is the same gate, finally applied to both. Before this,
         * {@code CompoundExecutor} had NO network check at all.
         *
         * <p>⚠ <b>The default is on the FIELD as well as in the annotation, deliberately.</b> A
         * {@code @Value} default only fires when Spring binds the bean; an instance built any other
         * way — reflectively in a test, or by a future {@code new Network()} — would otherwise hold
         * {@code null} and silently become non-submittable. Measured immediately: 23 liquidation
         * veto tests went red on exactly that, and they were right to. <b>A default that lives only
         * in the framework is not a default of the class</b>, and the gap shows up as behaviour that
         * differs between production and every other construction path.
         */
        @Value("${loans.submittable-network:preview}")
        private String submittableNetwork = "preview";

        /**
         * Whether this node may submit on the network it is configured for.
         *
         * <p>⚠ Compares against {@link #getNetwork()}, not the field. The accessor is overridden by
         * test doubles — an anonymous subclass returning a network name while the private field
         * stays {@code null} — and reading the field there yields "not submittable" for a node that
         * plainly is. <b>When a class exposes an accessor, its own logic should go through it</b>,
         * or the override becomes a half-truth that behaves differently inside than out.
         */
        public boolean isSubmittable() {
            return submittableNetwork != null && submittableNetwork.equalsIgnoreCase(getNetwork());
        }

        /** Test seam; {@code @Value} owns these in production. */
        public void setNetworkForTest(String network, String submittableNetwork) {
            this.network = network;
            this.submittableNetwork = submittableNetwork;
        }

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

        /**
         * ⛔ The three Minswap V2 coordinates that parameterise
         * {@code lm_liquidate_and_convert_action} — the LAST underivable field in the deployment
         * (findings §25.1). Defaults are FluidTokens' <b>mainnet</b> parameterisation, quoted
         * first-hand and verified: applied to the vendored blueprint they derive
         * {@code ed8d41e4…}, exactly what the mainnet LMConfigDatum publishes at field 5.
         *
         * <p>⚠ <b>They are network-specific and preview's are NOT these.</b> Substituting preview's
         * loans coordinates while keeping these yields {@code 52b778c8…} against a published
         * {@code aa3628d8…}, so preview is parameterised for a Minswap deployment we cannot identify
         * — and none exists on preview anyway (§28.1). A node whose derived hash does not match what
         * its own LMConfigDatum publishes simply <b>cannot convert</b>; that is reported, not fatal,
         * because every other path on that node is unaffected.
         *
         * <p>The two {@code *WithdrawScriptHash} parameters the validator also takes are the empty
         * string on both networks — Minswap V2's pool and order validators are plain PlutusV2 with no
         * withdraw half, so empty selects the non-CIP-113 branch (§25.4). They are constants here
         * rather than keys: "unset" and "this credential family has no withdraw script" are different
         * statements, and only the second is true.
         */
        @Value("${loans.minswap.pool-policy-id:f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c}")
        private String minswapPoolPolicyId = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

        @Value("${loans.minswap.pool-spend-script-hash:ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b}")
        private String minswapPoolSpendScriptHash = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";

        @Value("${loans.minswap.order-spend-script-hash:c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c}")
        private String minswapOrderSpendScriptHash = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

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
    @ConfigurationProperties(prefix = "loans.liquidation")
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

        /**
         * ⛔ <b>Per-market policy: which markets the bot acts in, how, and under what execution
         * state.</b> An object list, not a delimited string — every attribute is NAMED, so nothing is
         * positional and nothing is inferred from another field's presence.
         *
         * <pre>
         * loans:
         *   liquidation:
         *     markets:
         *       - unit: lovelace          # "lovelace", or policyIdHex + assetNameHex
         *         mode: SHADOW            # DISABLED | SHADOW | LIVE; omitted = inherit the global mode
         *         action: CONVERT         # CONVERT | ANTICIPATE; default CONVERT
         *         cap: 1000000000         # MANDATORY iff action: ANTICIPATE, meaningless otherwise
         * </pre>
         *
         * <p><b>An UNLISTED market is {@code action: CONVERT} at the global mode.</b> An absent or
         * empty list therefore means "convert everywhere, at whatever posture the node is in", and
         * listing a market is how an operator <em>deviates</em>. That is Giovanni's ruling of
         * 2026-09-03: convert fronts no capital and its failure mode is a no-op (findings §28.3), so it
         * is the safe default; the capital-hungry pay-in-advance path stays opt-in and still needs an
         * explicit entry naming both {@code ANTICIPATE} and a cap.
         *
         * <p>⚠ <b>A YAML list is not one environment variable.</b> Operators driving this through
         * {@code docker/.env} need indexed names — {@code LOANS_LIQUIDATION_MARKETS_0_UNIT},
         * {@code LOANS_LIQUIDATION_MARKETS_0_MODE}, … — or a mounted YAML fragment. That is the cost of
         * named fields and it is worth it; see {@code docs/} for the operator-facing form.
         *
         * <p>Bound by {@code @ConfigurationProperties} rather than {@code @Value}, which cannot bind a
         * list of objects at all. {@link #validateMarkets()} aborts startup on a malformed entry.
         */
        private List<Market> markets = new ArrayList<>();

        /** Setter for {@code @ConfigurationProperties} binding, and the test seam. */
        public void setMarkets(List<Market> markets) {
            this.markets = markets == null ? new ArrayList<>() : markets;
        }

        /**
         * One market's policy. Deliberately a mutable bean rather than a record: Spring Boot's relaxed
         * binder fills it by setter, which is what makes {@code LOANS_LIQUIDATION_MARKETS_0_UNIT} work.
         */
        @Getter
        @Setter
        @NoArgsConstructor
        public static class Market {

            /** {@code "lovelace"}, or {@code policyIdHex + assetNameHex}. */
            private String unit;

            /**
             * The market's EXECUTION STATE, not its strategy. {@code null} means "inherit the node's
             * {@code loans.liquidation.mode}", which is why an unlisted market needs no entry to
             * shadow: putting the node in {@code shadow} shadows every market at once.
             *
             * <p>⛔ <b>The global mode is a CEILING.</b> The effective state is
             * {@code min(globalMode, this)} over the declared order {@code DISABLED < SHADOW < LIVE},
             * so a market may be MORE restrictive than the node but never less. Without that rule a
             * per-market {@code LIVE} would let a node whose posture is {@code shadow} submit, which
             * would make the node-level dial a lie.
             */
            private Mode mode;

            /** The strategy. Never inferred from {@link #cap} — that would be positional guessing. */
            private Action action = Action.CONVERT;

            /**
             * The most principal the bot may front in this market, in the principal asset's own unit.
             * Mandatory when {@link #action} is {@link Action#ANTICIPATE}, meaningless otherwise.
             */
            private BigInteger cap;
        }

        /**
         * Which transaction this market's liquidations build. It partitions only the loans whose lender
         * bond carries {@code shouldLiquidationConvertToPrincipal == True}: a bond that forbids
         * conversion is plain {@code Liquidate} whatever the market says (findings §27).
         */
        public enum Action {
            /**
             * Route the collateral through Minswap. The bot fronts nothing. The default, and the right
             * one wherever a pool can reliably deliver {@code remainingDebt}.
             */
            CONVERT,
            /**
             * Front the principal from the bot's own wallet. FluidTokens' guidance (2026-09-03): this is
             * for <b>tokens with no reliable Minswap pool</b>, because the order must always deliver at
             * least the minimum the lender expects. Pool reliability is the operator's judgement — the
             * bot does not detect it.
             */
            ANTICIPATE
        }

        /**
         * ⛔ Abort startup on a market entry that cannot mean anything, rather than resolving it to a
         * safe-looking default.
         *
         * <p>This is only defensible because the fields are NAMED. Under the previous delimited-string
         * form a half-parsed token could not be told from a typo, so the only safe response was to
         * disable that market quietly. Here every failure is unambiguous — a missing cap on an
         * {@code ANTICIPATE} market, a unit that is neither {@code lovelace} nor a well-formed hex unit,
         * a duplicate — and the same fail-fast that {@link #parseMode()} applies is the honest response.
         *
         * <p>Gated behind {@code loans.enabled} with the rest of this class, so a typo can never refuse
         * to start a production node that does not run the bot.
         */
        public void validateMarkets() {
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < markets.size(); i++) {
                Market m = markets.get(i);
                String where = "loans.liquidation.markets[" + i + "]";
                if (m == null || m.getUnit() == null || m.getUnit().isBlank()) {
                    throw new IllegalStateException(where + " has no unit");
                }
                String unit = m.getUnit().trim();
                if (!isWellFormedUnit(unit)) {
                    throw new IllegalStateException(where + " unit '" + unit + "' is neither "
                            + "\"lovelace\" nor a policy id (56 hex chars) followed by a hex asset name");
                }
                if (!seen.add(unit)) {
                    throw new IllegalStateException(where + " repeats unit '" + unit + "'; two entries "
                            + "for one market cannot both apply and the later one would win silently");
                }
                if (m.getAction() == null) {
                    throw new IllegalStateException(where + " has an empty action; legal values are "
                            + Arrays.toString(Action.values()));
                }
                if (m.getAction() == Action.ANTICIPATE) {
                    if (m.getCap() == null) {
                        throw new IllegalStateException(where + " is action: ANTICIPATE and has no cap. "
                                + "The cap bounds the principal the bot fronts from its own wallet; "
                                + "anticipating without one is unbounded exposure");
                    }
                    if (m.getCap().signum() < 0) {
                        throw new IllegalStateException(where + " has a negative cap (" + m.getCap() + ")");
                    }
                }
                if (m.getAction() == Action.CONVERT && m.getCap() != null) {
                    log.warn("{} is action: CONVERT and also sets cap {} — convert fronts no principal, "
                            + "so the cap governs nothing here and is IGNORED", where, m.getCap());
                }
            }
            log.info("INIT - liquidation markets: {} listed; every unlisted market is action: CONVERT at "
                    + "the node mode ({})", markets.size(), mode);
            for (Market m : markets) {
                log.info("INIT - market {}: mode={} (effective {}), action={}, cap={}", m.getUnit(),
                        m.getMode() == null ? "inherit" : m.getMode(), effectiveMode(m), m.getAction(),
                        m.getCap());
                if (m.getMode() == Mode.LIVE && effectiveMode(m) != Mode.LIVE) {
                    log.warn("⛔ market {} asks for LIVE but the node mode is {} — it will run as {}. "
                            + "The node mode is a CEILING: no market can be less restrictive than the "
                            + "node.", m.getUnit(), mode, effectiveMode(m));
                }
            }
        }

        /**
         * {@code min(globalMode, market.mode)} over {@code DISABLED < SHADOW < LIVE} — the declared
         * order of {@link Mode}, which is why this is an ordinal comparison rather than a table.
         */
        public Mode effectiveMode(Market market) {
            Mode global = mode == null ? Mode.DISABLED : mode;
            Mode wanted = market == null || market.getMode() == null ? global : market.getMode();
            return wanted.ordinal() < global.ordinal() ? wanted : global;
        }

        /** {@code "lovelace"}, or 56 hex chars of policy id plus an even number of hex asset-name chars. */
        private static boolean isWellFormedUnit(String unit) {
            if (AssetType.LOVELACE.equalsIgnoreCase(unit)) {
                return true;
            }
            return unit.length() >= 56 && unit.length() % 2 == 0
                    && unit.chars().allMatch(c -> Character.digit(c, 16) >= 0);
        }

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
            validateMarkets();
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
         * <p>⛔ <b>Honoured on every network, mainnet included</b> — Giovanni's ruling of 2026-09-03:
         * <i>"operating at a loss MUST be implemented even on mainnet."</i> The protection is this
         * default of 0, which refuses every loss and cannot be made negative by copy-paste; a node
         * that does state a negative announces it loudly at boot.
         */
        @Value("${loans.compound.profit-margin-lovelace:0}")
        private BigInteger profitMarginLovelace;

        /**
         * Published reference scripts, as a comma-separated list of {@code txHash#index} coordinates.
         *
         * <p>⚑ <b>Deliberately NOT one named key per validator.</b> The liquidation path has eight
         * such keys, and its own Argo comment records the confusion they cause: a key named for a
         * validator holds a <em>coordinate</em>, not a contract, and nothing checks that the two
         * agree. Here the operator lists coordinates and <b>the chain says which script each one
         * publishes</b> — the node reads {@code referenceScriptHash} off the UTxO. A mislabelled
         * coordinate is therefore not expressible.
         *
         * <p>Empty (the default) means every validator travels inline in the witness set. That is
         * correct and it is also <b>too big to submit</b>: eleven inline validators make a
         * 24,878-byte transaction against a 16,384 {@code max_tx_size} (findings §22.9). Referencing
         * the four largest brings it to ~10,400.
         */
        @Value("${loans.compound.reference-scripts:}")
        private String referenceScripts;

        /** Test seam: {@code @Value} owns these in production. */
        public CompoundConfiguration(boolean enabled, long delaySeconds, BigInteger profitMarginLovelace) {
            this.enabled = enabled;
            this.delaySeconds = delaySeconds;
            this.profitMarginLovelace = profitMarginLovelace;
        }
    }

    /**
     * The <b>LiquidateAndConvert</b> path: liquidate a convert-eligible loan by building a Minswap V2
     * swap order inside the liquidation transaction, so the collateral is converted to the principal
     * and lands in the lender's asset manager (findings §25).
     *
     * <p>⚑ <b>Its own block, and its own margin key, for the reason {@link CompoundConfiguration}
     * gives:</b> convert and pay-in-advance share the word "liquidation" and almost nothing else.
     * Pay-in-advance fronts the whole principal and is bounded by {@code MarketGate}'s per-market cap;
     * convert fronts nothing and its outlay is a transaction fee plus, for a token collateral, the
     * 2.8 ada the validator makes the order output carry. A shared margin would let a number reasoned
     * about for one silently govern the other.
     */
    @Component
    @Getter
    @NoArgsConstructor
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public static class ConvertConfiguration {

        /**
         * ⚑ <b>Default {@code true} — the one arming flag in this codebase that defaults ON</b>, on
         * Giovanni's first-hand ruling (2026-09-03): <i>"with the convert path, liquidation by
         * minswap/conversion should always be enabled by default, additional configuration can be
         * provided to disable a market OR to force anticipate instead of convert."</i>
         *
         * <p>That does not weaken the defensive-defaults rule, it applies it. The bot fronts no
         * capital on this path and holds nothing: FluidTokens confirmed that an order which does not
         * fill returns the original collateral to the asset manager for the lender to reclaim, and the
         * bot's fee is taken before the swap either way. <b>Its failure mode is a no-op, not a
         * loss</b> — unlike pay-in-advance, which is the capital-hungry path and stays opt-in behind a
         * per-market cap.
         *
         * <p>And "on by default" is on only for a node whose operator has already armed liquidation:
         * {@code loans.enabled}, {@code loans.liquidation.mode}, {@code loans.liquidation.enabled} and
         * {@code loans.submittable-network} all still apply, ahead of this. This flag turns the
         * mechanism off globally; {@code loans.liquidation.markets} turns it off per market.
         */
        @Value("${loans.liquidation.convert.enabled:true}")
        private boolean enabled = true;

        /**
         * What {@code feeValueLovelace - (txFee + orderAda)} must reach for a convert to be built.
         *
         * <p><b>Default {@code 0} refuses every net loss</b> while allowing exact break-even, matching
         * the compound floor and for the same reason: this path advances no principal and carries no
         * position, so demanding the liquidation path's 1_500_000 premium would refuse sound work.
         * It is also what refuses a bond with {@code liquidationFeePerMille = 0} out of the box — such
         * a bond pays nothing, so the net is exactly minus the outlay.
         *
         * <p>⚠ <b>This is also the operator's lever on a modelling risk</b>, not only on margin. The
         * income side is the collateral-denominated fee valued at the oracle price — real, but held in
         * tokens and unrealised. An operator who doubts the price, or the liquidity behind it, raises
         * this until they are paid enough ada-equivalent to be worth the exposure. See
         * {@code ConvertEconomics}.
         *
         * <p>A negative value is a number the operator STATES rather than a protection they switch
         * off — and it is <b>honoured on every network, mainnet included</b> (Giovanni, 2026-09-03:
         * <i>"operating at a loss MUST be implemented even on mainnet"</i>). The bot is a
         * protocol-health tool and a stated-loss convert that clears a loan nobody else will touch is
         * the point. What protects an operator is this default of 0, which no copy-paste can turn
         * negative; a node that does state a negative announces it loudly at boot.
         */
        @Value("${loans.liquidation.convert.profit-margin-lovelace:0}")
        private BigInteger profitMarginLovelace = BigInteger.ZERO;

        /**
         * ⛔ <b>The floor under what one DEX interaction is assumed to cost the operator, in
         * lovelace.</b> Giovanni's ruling, 2026-09-03: <i>"the margin must take into account for
         * convert the ADA spent to interact with the DEX. So between batcher and tx fee you can round
         * at 4 ada or 5 ada."</i> Default {@code 5_000_000} — the conservative end of the figure he
         * named.
         *
         * <p><b>A floor, not an addend, and that distinction is the whole design.</b> The gate charges
         * {@code max(txFee + mandatoryOrderAda, this)}, so the measured components stay visible and the
         * assessment records which one bound — while the gate can never be more optimistic than the
         * operator's stated cost of touching a DEX.
         *
         * <p>⚑ <b>Why a floor rather than adding the batcher fee to the measurement.</b> Read at
         * {@code e0b818e}: for an <b>ada</b> collateral the validator requires the order's total
         * lovelace to equal {@code swappableCollateralAmount} exactly — <b>no extra ada at all</b> —
         * so Minswap's {@code max_batcher_fee} of 700,000 comes out of the <em>swap input</em>, which
         * is the lender's proceeds, not the bot's wallet. Adding it to the bot's outlay would be a
         * false attribution. A floor captures Giovanni's conservatism <b>without asserting who pays
         * what</b>, which is the honest instrument for a cost whose incidence is genuinely split.
         *
         * <p>It is separable from {@link #profitMarginLovelace} and cannot contradict it: this one says
         * what the interaction costs, that one says how far above break-even the operator wants to be.
         *
         * <p>Refused at startup when negative, on any network — a negative cost floor is not a bound an
         * operator can meaningfully state, it is a typo.
         */
        @Value("${loans.liquidation.convert.dex-cost-floor-lovelace:5000000}")
        private BigInteger dexCostFloorLovelace = BigInteger.valueOf(5_000_000L);

        /** Test seam: {@code @Value} owns these in production. */
        public ConvertConfiguration(boolean enabled, BigInteger profitMarginLovelace) {
            this(enabled, profitMarginLovelace, BigInteger.valueOf(5_000_000L));
        }

        /** Test seam: {@code @Value} owns these in production. */
        public ConvertConfiguration(boolean enabled, BigInteger profitMarginLovelace,
                                    BigInteger dexCostFloorLovelace) {
            this.enabled = enabled;
            this.profitMarginLovelace = profitMarginLovelace;
            this.dexCostFloorLovelace = dexCostFloorLovelace;
        }
    }

}
