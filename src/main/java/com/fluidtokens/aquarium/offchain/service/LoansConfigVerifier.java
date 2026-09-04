package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks, at startup, that what {@link LoansContractRegistry} derived matches what the
 * live config UTxOs actually publish — and refuses to start if it does not.
 * <p>
 * Without this the node fails <em>silently</em>: FluidTokens redeploys the v4 config NFTs
 * (which already happened once between 2026-07-14 and 2026-08-05), the policy ids in
 * {@code application.yaml} go stale, derivation still succeeds, and the node indexes a
 * dead deployment while looking perfectly healthy. Turning that into a startup failure is
 * the whole point of this class.
 *
 * <h2>Failure modes, deliberately kept distinct</h2>
 * <ul>
 *   <li><b>Mismatch, or config NFT not found</b> — hard fail. Both mean the configured
 *       policy ids no longer describe the deployment.</li>
 *   <li><b>Blockfrost unreachable</b> — warn and continue by default. A network blip must
 *       not take down the Aquarium scheduled-transaction path, which does not depend on
 *       loans at all. Set {@code loans.verify-config.fail-on-unreachable=true} to make
 *       the check mandatory.</li>
 * </ul>
 * Field indices below come from {@code lib/fluidtokens/types/config.ak} and
 * {@code lib/fluidtokens/types/lender_manager.ak}. Both datums are inline
 * {@code constructor 0} records; field order is consensus-critical on chain and is
 * asserted here by position.
 */
@Service
@Slf4j
public class LoansConfigVerifier {

    // ConfigDatum field indices.
    private static final int CFG_SMART_TOKENS_SPEND = 0;
    private static final int CFG_POOL_POLICY_ID = 2;
    private static final int CFG_REQUEST_POLICY_ID = 3;
    private static final int CFG_BORROWER_BOND_POLICY_ID = 4;
    private static final int CFG_LENDER_BOND_POLICY_ID = 5;
    private static final int CFG_LOAN_POLICY_ID = 6;
    private static final int CFG_POOL_SPEND = 8;
    private static final int CFG_REQUEST_SPEND = 9;
    private static final int CFG_LOAN_SPEND = 10;
    private static final int CFG_LOAN_CLAIM_ACTION = 11;
    private static final int CFG_LOAN_REPAY_ACTION = 12;
    private static final int CFG_LOAN_CHANGE_COLLATERAL_ACTION = 13;
    private static final int CFG_LOAN_RECAST_ACTION = 14;
    private static final int CFG_ASSET_MANAGER_SPEND = 15;
    private static final int CFG_POOL_CANCEL_ACTION = 22;
    private static final int CFG_POOL_BORROW_ACTION = 23;
    private static final int CFG_POOL_SELL_LENDER_POSITION_ACTION = 24;
    private static final int CFG_POOL_COMPOUND_ACTION = 25;
    private static final int CFG_POOL_MANAGER_SPEND = 26;
    private static final int CFG_POOL_MANAGER_POLICY_ID = 27;
    private static final int CFG_LOCKED_BORROWER_MANAGER_SPEND = 28;
    private static final int CONFIG_DATUM_FIELDS = 29;

    // LMConfigDatum field indices.
    //
    // ⛔ Field 5 was "deliberately unchecked: it takes five Minswap parameters we do not have" until
    // 2026-09-03. We have them (findings §25.1), and it is now derived and REPORTED — never a
    // mismatch that stops the node. The Minswap coordinates are NETWORK-SPECIFIC, so a node
    // configured with mainnet's while running elsewhere derives a real hash for the wrong deployment.
    // That disables exactly one path and breaks none, so refusing to boot over it would be the
    // disproportionate response — and it is the state every preview node is in today (§28.1).
    private static final int LM_WITHDRAW_BONDS_ACTION = 1;
    private static final int LM_LIQUIDATE_ACTION = 2;
    private static final int LM_COMPOUND_ACTION = 3;
    private static final int LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION = 4;
    private static final int LM_LIQUIDATE_AND_CONVERT_ACTION = 5;
    private static final int LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION = 6;
    private static final int LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION = 7;
    private static final int LM_CONFIG_DATUM_FIELDS = 8;

    private final LoansContractRegistry registry;
    private final String configuredSmartTokensSpendScriptHash;
    private final AppConfig.Network network;
    private final BFBackendService bfBackendService;
    private final boolean failOnUnreachable;

    // Plain constructor params rather than injected config objects, so the verifier can be
    // built directly in a test against recorded datums.
    public LoansConfigVerifier(LoansContractRegistry registry,
                               @Value("${loans.smart-tokens-spend-script-hash:}")
                               String configuredSmartTokensSpendScriptHash,
                               AppConfig.Network network,
                               BFBackendService bfBackendService,
                               @Value("${loans.verify-config.fail-on-unreachable:false}")
                               boolean failOnUnreachable) {
        this.registry = registry;
        this.configuredSmartTokensSpendScriptHash = configuredSmartTokensSpendScriptHash;
        this.network = network;
        this.bfBackendService = bfBackendService;
        this.failOnUnreachable = failOnUnreachable;
    }

    /** {@code smartTokensSpendScriptHash} as published on chain — the authoritative value. */
    @Getter
    private String onChainSmartTokensSpendScriptHash;

    @PostConstruct
    public void verify() {
        // ⛔ ABSENT COORDINATES ARE NOT A MISMATCH, and this distinction became load-bearing on
        // 2026-09-04 when `loans.enabled` was removed. There is no longer a switch that stops this
        // bean existing, so a bare install — a chart that ships `loans.config.policy-id: ""` — reaches
        // this method on EVERY node.
        //
        // What it would do without this guard, measured rather than assumed: a blank policy id
        // derives an address (`addr1wy22xa6y`), Blockfrost answers 4xx, and `fetchConfigDatumHex`
        // correctly treats a 4xx as an ANSWER rather than an outage — so it throws, out of a
        // @PostConstruct, and the pod CRASH-LOOPS with no way to turn it off.
        //
        // ⚠ The hard fail below is NOT weakened by this and must not be. A MISMATCH means the
        // configured policy ids no longer describe the deployment, which is precisely the redeploy
        // that verifies cleanly forever while the node indexes a dead world (§12). Absent means the
        // operator has not said which deployment to watch yet. One is a fault; the other is a Tuesday.
        if (!registry.isConfigured()) {
            log.warn("Lending v4 is NOT CONFIGURED — loans.config.policy-id / loans.lm-config.policy-id "
                    + "are absent or malformed, so no deployment is being watched, NOTHING is indexed "
                    + "for v4, and nothing was verified against chain. This is the correct state for a "
                    + "fresh install; supply both policy ids to start indexing.");
            return;
        }

        List<String> mismatches;
        try {
            mismatches = verifyAgainst(
                    fetchConfigDatumHex(registry.getConfigPolicyId(), "ConfigDatum"),
                    fetchConfigDatumHex(registry.getLmConfigPolicyId(), "LMConfigDatum"));
        } catch (ConfigUnreachableException e) {
            if (failOnUnreachable) {
                throw new IllegalStateException("Cannot verify Lending v4 config: " + e.getMessage(), e);
            }
            log.warn("Could not verify Lending v4 config against chain ({}). Continuing unverified — " +
                    "the derived hashes may describe a superseded deployment.", e.getMessage());
            return;
        }

        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("""
                    Lending v4 config mismatch — the derived script hashes do not match the live \
                    config UTxOs. The contracts were almost certainly redeployed; update \
                    loans.config.policy-id / loans.lm-config.policy-id (and \
                    loans.smart-tokens-spend-script-hash) in application.yaml. Mismatches: """
                    + String.join("; ", mismatches));
        }
        log.info("Lending v4 config verified against chain: derived hashes match both config datums");
    }

    /**
     * The comparison itself, separated from the network fetch so it can be exercised against
     * recorded datums in {@code LoansConfigVerifierTest} — the field indices are the fragile
     * part and they deserve a test that does not need Blockfrost.
     *
     * @return one entry per mismatching field; empty means verified
     */
    public List<String> verifyAgainst(String configDatumHex, String lmConfigDatumHex) {
        List<String> mismatches = new ArrayList<>();
        mismatches.addAll(verifyMainConfig(parseFields(configDatumHex, CONFIG_DATUM_FIELDS, "ConfigDatum")));
        mismatches.addAll(verifyLmConfig(parseFields(lmConfigDatumHex, LM_CONFIG_DATUM_FIELDS, "LMConfigDatum")));
        return mismatches;
    }

    // ---- Main ConfigDatum ---------------------------------------------------------------

    private List<String> verifyMainConfig(List<PlutusData> fields) {
        onChainSmartTokensSpendScriptHash = bytesAt(fields, CFG_SMART_TOKENS_SPEND);
        String configured = configuredSmartTokensSpendScriptHash;
        if (configured == null || configured.isBlank()) {
            log.warn("loans.smart-tokens-spend-script-hash is unset; the chain says it is {} — " +
                            "set it to enable the pool-manager derivation branch",
                    onChainSmartTokensSpendScriptHash);
        }

        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(CFG_POOL_POLICY_ID, registry.getPoolPolicyId());
        expected.put(CFG_REQUEST_POLICY_ID, registry.getRequestPolicyId());
        expected.put(CFG_LOAN_POLICY_ID, registry.getLoanPolicyId());
        expected.put(CFG_POOL_SPEND, registry.getPoolSpendScriptHash());
        expected.put(CFG_REQUEST_SPEND, registry.getRequestSpendScriptHash());
        expected.put(CFG_LOAN_SPEND, registry.getLoanSpendScriptHash());
        expected.put(CFG_LOAN_CLAIM_ACTION, registry.getLoanClaimActionScriptHash());
        expected.put(CFG_LOAN_REPAY_ACTION, registry.getLoanRepayActionScriptHash());
        expected.put(CFG_LOAN_CHANGE_COLLATERAL_ACTION, registry.getLoanChangeCollateralActionScriptHash());
        expected.put(CFG_LOAN_RECAST_ACTION, registry.getLoanRecastActionScriptHash());
        expected.put(CFG_ASSET_MANAGER_SPEND, registry.getAssetManagerSpendScriptHash());
        expected.put(CFG_POOL_CANCEL_ACTION, registry.getPoolCancelActionScriptHash());
        expected.put(CFG_POOL_BORROW_ACTION, registry.getPoolBorrowActionScriptHash());
        expected.put(CFG_POOL_SELL_LENDER_POSITION_ACTION, registry.getPoolSellLenderPositionActionScriptHash());
        expected.put(CFG_POOL_COMPOUND_ACTION, registry.getPoolCompoundActionScriptHash());
        expected.put(CFG_POOL_MANAGER_SPEND, registry.getPoolManagerSpendScriptHash());
        expected.put(CFG_BORROWER_BOND_POLICY_ID, registry.getBorrowerBondPolicyId());
        expected.put(CFG_LENDER_BOND_POLICY_ID, registry.getLenderBondPolicyId());
        expected.put(CFG_POOL_MANAGER_POLICY_ID, registry.getPoolManagerPolicyId());
        expected.put(CFG_LOCKED_BORROWER_MANAGER_SPEND, registry.getLockedBorrowerManagerSpendScriptHash());

        // smartTokensSpendScriptHash is not derived but is configured, so it is worth checking:
        // a stale value here silently corrupts the whole pool-manager branch.
        if (configured != null && !configured.isBlank()) {
            expected.put(CFG_SMART_TOKENS_SPEND, configured);
        }

        return compare("ConfigDatum", fields, expected);
    }

    // ---- LMConfigDatum ------------------------------------------------------------------

    private List<String> verifyLmConfig(List<PlutusData> fields) {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(LM_WITHDRAW_BONDS_ACTION, registry.getLmWithdrawBondsActionScriptHash());
        expected.put(LM_LIQUIDATE_ACTION, registry.getLmLiquidateActionScriptHash());
        expected.put(LM_COMPOUND_ACTION, registry.getLmCompoundActionScriptHash());
        expected.put(LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, registry.getLmLiquidateAndPayInAdvanceActionScriptHash());
        expected.put(LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION,
                registry.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash());
        expected.put(LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION,
                registry.getLmLiquidateConvertAndCompoundActionScriptHash());

        List<String> mismatches = compare("LMConfigDatum", fields, expected);
        reportConvertAvailability(fields);
        return mismatches;
    }

    /**
     * ⛔ <b>Whether this node can convert at all</b>, answered against the chain rather than assumed.
     *
     * <p>Reported, never fatal — see the field-index comment above. The three outcomes are
     * deliberately distinguishable in the log, because "the coordinates are for another network" and
     * "we have no coordinates" send an operator to completely different places.
     */
    private void reportConvertAvailability(List<PlutusData> fields) {
        String derived = registry.getLmLiquidateAndConvertActionScriptHash();
        if (derived == null) {
            log.warn("CONVERT UNAVAILABLE: loans.minswap.* is not set, so "
                    + "lm_liquidate_and_convert_action cannot be derived");
            return;
        }
        String published = bytesAt(fields, LM_LIQUIDATE_AND_CONVERT_ACTION);
        if (derived.equalsIgnoreCase(published)) {
            log.info("CONVERT AVAILABLE: lm_liquidate_and_convert_action derives {} and the "
                    + "LMConfigDatum publishes the same — the configured Minswap coordinates are "
                    + "this deployment's", derived);
            return;
        }
        log.warn("⛔ CONVERT UNAVAILABLE: lm_liquidate_and_convert_action derives {} but this "
                        + "deployment's LMConfigDatum publishes {}. The configured loans.minswap.* "
                        + "coordinates belong to a DIFFERENT network's Minswap deployment. Every "
                        + "other path on this node is unaffected.", derived, published);
    }

    // ---- Plumbing -----------------------------------------------------------------------

    /**
     * A null expected value means the registry could not derive that field (the pool-manager
     * branch without smartTokensSpendScriptHash); those are reported as unchecked, not as
     * mismatches, so a partial derivation still starts.
     */
    private List<String> compare(String datum, List<PlutusData> fields, Map<Integer, String> expected) {
        List<String> mismatches = new ArrayList<>();
        int unchecked = 0;
        for (Map.Entry<Integer, String> e : expected.entrySet()) {
            if (e.getValue() == null) {
                unchecked++;
                continue;
            }
            String actual = bytesAt(fields, e.getKey());
            if (!e.getValue().equals(actual)) {
                mismatches.add("%s[%d]: derived %s, chain %s".formatted(datum, e.getKey(), e.getValue(), actual));
            }
        }
        log.info("{}: {} fields checked, {} skipped (not derivable), {} mismatched",
                datum, expected.size() - unchecked, unchecked, mismatches.size());
        return mismatches;
    }

    /**
     * Reads the inline datum of the UTxO holding the config NFT. The NFT sits at its own
     * validator's script address, so the policy id doubles as the payment credential.
     */
    private String fetchConfigDatumHex(String policyId, String datumName) {
        String address = AddressProvider
                .getEntAddress(Credential.fromScript(HexUtil.decodeHexString(policyId)),
                        network.getCardanoNetwork())
                .getAddress();
        String unit = policyId + registry.getConfigAssetName();

        List<Utxo> utxos;
        try {
            var result = bfBackendService.getUtxoService().getUtxos(address, 100, 1);
            if (!result.isSuccessful()) {
                // A 4xx is an answer, not an outage: a bad key or an address with no history
                // both mean the node is misconfigured, and degrading those to "unverified"
                // would reopen exactly the silent-failure hole this class exists to close.
                // Only 5xx / throttling / transport errors are treated as transient.
                int code = result.code();
                String detail = "HTTP " + code + " for " + datumName + " at " + address + ": " + result.getResponse();
                if (code >= 400 && code < 500 && code != 429) {
                    throw new IllegalStateException("Lending v4 config lookup rejected — " + detail);
                }
                throw new ConfigUnreachableException(detail);
            }
            utxos = result.getValue();
        } catch (ConfigUnreachableException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigUnreachableException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Utxo configUtxo = utxos.stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
                .findFirst()
                // Not "unreachable": the call succeeded and the NFT is not there. That means
                // the configured policy id describes a deployment that no longer exists.
                .orElseThrow(() -> new IllegalStateException(
                        "Lending v4 config NFT %s not found at %s — the configured policy id is stale"
                                .formatted(unit, address)));

        if (configUtxo.getInlineDatum() == null || configUtxo.getInlineDatum().isBlank()) {
            throw new IllegalStateException(datumName + " UTxO " + configUtxo.getTxHash() + "#"
                    + configUtxo.getOutputIndex() + " has no inline datum");
        }
        log.info("Read {} from {}#{}", datumName, configUtxo.getTxHash(), configUtxo.getOutputIndex());
        return configUtxo.getInlineDatum();
    }

    private static List<PlutusData> parseFields(String datumHex, int expectedFieldCount, String datumName) {
        PlutusData datum;
        try {
            datum = PlutusData.deserialize(HexUtil.decodeHexString(datumHex));
        } catch (Exception e) {
            throw new IllegalStateException("cannot deserialize " + datumName, e);
        }
        if (!(datum instanceof ConstrPlutusData constr) || constr.getAlternative() != 0) {
            throw new IllegalStateException(datumName + " is not a constructor-0 record: " + datum);
        }
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        if (fields.size() != expectedFieldCount) {
            throw new IllegalStateException("%s has %d fields, expected %d — the contract types changed, so the "
                    .formatted(datumName, fields.size(), expectedFieldCount)
                    + "field indices in this class are no longer valid");
        }
        return fields;
    }

    private static String bytesAt(List<PlutusData> fields, int index) {
        PlutusData field = fields.get(index);
        if (!(field instanceof BytesPlutusData bytes)) {
            throw new IllegalStateException("field " + index + " is not a ByteArray: " + field);
        }
        return HexUtil.encodeHexString(bytes.getValue());
    }

    /** Signals "we could not ask the chain", as opposed to "we asked and the answer was wrong". */
    private static class ConfigUnreachableException extends RuntimeException {
        ConfigUnreachableException(String message) {
            super(message);
        }
    }
}
