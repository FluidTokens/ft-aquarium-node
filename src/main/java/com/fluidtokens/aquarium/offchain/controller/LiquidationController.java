package com.fluidtokens.aquarium.offchain.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationDecisionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view over the liquidation loop: what mode it is in, what the last cycle saw, and what
 * it decided about each candidate it acted on.
 * <p>
 * This endpoint exists so the bot can be judged before it is armed. {@code armed} is the field that
 * matters — with the shipped defaults it is {@code false}, and a decision of {@code WOULD_SUBMIT}
 * next to {@code armed: false} is precisely the claim being made: the transaction was built and
 * priced, and then dropped. {@code submit_veto} says which check dropped it.
 * <p>
 * The exclusion histogram and the decision list are complementary, not overlapping: every scanned
 * bond is either counted in {@code exclusions} or eligible to appear in {@code decisions}, so
 * {@code bonds_scanned} always reconciles.
 */
@RestController
@RequestMapping("${apiPrefix}/loans/liquidations")
@RequiredArgsConstructor
@Slf4j
public class LiquidationController {

    private static final int DEFAULT_LIMIT = 50;

    private final AppConfig.LiquidationConfiguration configuration;

    private final LiquidationDecisionLog decisionLog;

    /**
     * One recorded decision. Every field from {@code expected_fee_lovelace} down is null unless the
     * transaction was actually built.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionView(String decidedAt,
                               long decidedAtMillis,
                               String loanId,
                               String loanUtxo,
                               String bondUtxo,
                               String variant,
                               String outcome,
                               String reason,
                               String detail,
                               Boolean late,
                               BigInteger remainingDebt,
                               BigInteger equity,
                               BigInteger liquidationFee,
                               String collateralUnit,
                               BigInteger expectedFeeLovelace,
                               BigInteger txFeeLovelace,
                               BigInteger marginLovelace,
                               BigInteger expectedProfitLovelace,
                               String txHash,
                               Integer txSizeBytes,
                               // The whole unsigned transaction is kilobytes of hex, so it is opt-in
                               // rather than paid for on every poll — and omitted outright, not sent
                               // as null, so a client cannot mistake "not asked for" for "not built".
                               @JsonInclude(JsonInclude.Include.NON_NULL) String txCborHex,
                               Integer inputs,
                               Integer outputs,
                               Integer referenceInputs,
                               Integer redeemers,
                               // Which of the seven submit vetoes stopped this candidate, or null:
                               // either it never reached the veto chain, or every veto passed and a
                               // submission was attempted. Read next to `outcome`: WOULD_SUBMIT with
                               // MODE_NOT_LIVE is shadow working, SUBMIT_VETOED with TX_TOO_LARGE is
                               // an armed bot declining.
                               String submitVeto) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LiquidationsView(String mode,
                                   boolean armed,
                                   String lastRunAt,
                                   int bondsScanned,
                                   int unreadable,
                                   int settled,
                                   int buildable,
                                   Map<String, Integer> exclusions,
                                   List<DecisionView> decisions) {
    }

    /**
     * @param limit       how many decisions to return, newest first
     * @param includeCbor whether to include {@code tx_cbor_hex} on each decision
     */
    @GetMapping
    public LiquidationsView liquidations(
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(name = "include_cbor", defaultValue = "false") boolean includeCbor) {

        LiquidationDecisionLog.RunSummary lastRun = decisionLog.lastRun();
        List<DecisionView> decisions = decisionLog.newestFirst(limit).stream()
                .map(decision -> toView(decision, includeCbor))
                .toList();

        return new LiquidationsView(
                configuration.getMode().name(),
                configuration.isArmed(),
                lastRun.at() == null ? null : Instant.ofEpochMilli(lastRun.at()).toString(),
                lastRun.bondsScanned(),
                lastRun.unreadable(),
                lastRun.settled(),
                lastRun.buildable(),
                exclusions(lastRun),
                decisions);
    }

    private static Map<String, Integer> exclusions(LiquidationDecisionLog.RunSummary lastRun) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (Map.Entry<LiquidationExclusion, Integer> entry : lastRun.exclusions().entrySet()) {
            byName.put(entry.getKey().name(), entry.getValue());
        }
        return byName;
    }

    private static DecisionView toView(LiquidationDecision decision, boolean includeCbor) {
        return new DecisionView(
                Instant.ofEpochMilli(decision.decidedAt()).toString(),
                decision.decidedAt(),
                decision.loanId(),
                decision.loanUtxoRef(),
                decision.bondUtxoRef(),
                decision.variant(),
                decision.outcome().name(),
                decision.reason(),
                decision.detail(),
                decision.late(),
                decision.remainingDebt(),
                decision.equity(),
                decision.liquidationFee(),
                decision.collateralUnit(),
                decision.expectedFeeLovelace(),
                decision.txFeeLovelace(),
                decision.marginLovelace(),
                decision.expectedProfitLovelace(),
                decision.txHash(),
                decision.txSizeBytes(),
                includeCbor ? decision.txCborHex() : null,
                decision.inputs(),
                decision.outputs(),
                decision.referenceInputs(),
                decision.redeemers(),
                decision.submitVeto());
    }
}
