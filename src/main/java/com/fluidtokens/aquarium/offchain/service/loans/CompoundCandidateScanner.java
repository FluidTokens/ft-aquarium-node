package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds asset-manager escrows that {@code lm_compound_action} could collect into a pool.
 *
 * <p>The join, in the order the validator itself performs it (findings §20.1): an escrow's
 * {@code ownerAsset} names a <b>lender bond</b>; that bond's {@code LenderManagerDatum} names a
 * {@code poolId}; that id must be the asset name of a live pool NFT <b>and</b> a live pool-manager
 * NFT; and the pool manager's datum carries the {@code compoudingFeePerMille} the economics need.
 *
 * <p>Refused candidates are returned rather than dropped. "No candidates" and "eight candidates, all
 * with burned pools" are different states and the second must not present as the first — the same
 * reasoning as {@code LiquidationCandidateScanner}'s census.
 *
 * <p>Read-only, entirely from the local index. Only as fresh as the Yaci Store cursor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompoundCandidateScanner {

    private final UtxoRepository utxoRepository;
    private final LoansContractRegistry registry;
    private final LenderBondService lenderBondService;
    private final LiquidationUtxoResolver resolver;

    private final AssetManagerDatumConverter converter = new AssetManagerDatumConverter();

    public record Scan(List<CompoundCandidate> candidates) {
        public List<CompoundCandidate> ready() {
            return candidates.stream().filter(CompoundCandidate::structurallyReady).toList();
        }
    }

    public Scan scan() {
        Map<String, LenderBond> bondsByLoanId = lenderBondService.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(LenderBond::loanId, Function.identity(),
                        (a, b) -> a));

        List<Utxo> escrows = escrows();

        List<CompoundCandidate> candidates = escrows.stream()
                .map(escrow -> classify(escrow, bondsByLoanId))
                .sorted(Comparator.comparing(CompoundCandidate::loanId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        log.debug("{} unspent asset-manager escrows, {} structurally ready to compound",
                escrows.size(), candidates.stream().filter(CompoundCandidate::structurallyReady).count());
        return new Scan(candidates);
    }

    /**
     * Every unspent UTxO at the asset-manager spend credential.
     *
     * <p>Overridable on purpose, matching {@code LiquidationUtxoResolver}'s convention: it is the one
     * method that reaches the index, so a test can drive the whole classification with recorded UTxOs
     * and no repository. Nothing else here touches storage.
     */
    public List<Utxo> escrows() {
        return utxoRepository
                .findUnspentByOwnerPaymentCredential(registry.getAssetManagerSpendScriptHash(), Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();
    }

    private CompoundCandidate classify(Utxo escrow, Map<String, LenderBond> bondsByLoanId) {
        String datumHex = escrow.getInlineDatum();
        if (datumHex == null || !converter.isTokenOwned(datumHex)) {
            // The Q3 fix: FluidTokens publishes the asset-manager script AS A REFERENCE SCRIPT at its
            // own address (verified on chain 2026-09-09), so it appears in this census on every cycle,
            // forever, and it is not datum-less junk — it is a protocol object that must NEVER be
            // spent. Naming it by its reference script hash, the same shape LiquidationExecutor's
            // whyNotSpendable already uses, is what stops an operator reading this line as "clean up
            // this junk". The classification is unchanged (still ESCROW_NOT_TOKEN_OWNED); only the
            // wording for this one shape changes. The genuinely no-datum case keeps its original text.
            String detail = escrow.getReferenceScriptHash() != null
                    ? "reference script " + escrow.getReferenceScriptHash()
                            + " published at its own address — protocol object, never collectable"
                    : "no inline datum, or not AssetManagerDatumWithToken; nothing token-owned can collect it";
            return refuse(escrow, null, CompoundExclusion.ESCROW_NOT_TOKEN_OWNED, detail);
        }

        var datum = converter.deserialize(datumHex);
        String loanId = datum.ownerAsset().assetName();

        if (!registry.getLenderBondPolicyId().equals(datum.ownerAsset().policyId())) {
            return refuse(escrow, loanId, CompoundExclusion.NOT_LENDER_OWNED,
                    "ownerAsset policy is " + datum.ownerAsset().policyId()
                            + ", not the lender-bond policy; lm_compound_action's bond lookup does not apply");
        }

        LenderBond bond = bondsByLoanId.get(loanId);
        if (bond == null) {
            return refuse(escrow, loanId, CompoundExclusion.BOND_NOT_FOUND,
                    "no unspent lender bond for loan " + loanId + " in the index");
        }

        String poolId = bond.datum().poolId();
        boolean principalIsAda = bond.datum().principalAsset().isAda();

        // Receipt-bearing and unrelated-asset escrows remain outside the ordinary topology. The
        // native case admitted here is exactly lovelace plus the configured principal unit.
        String principalUnit = bond.datum().principalAsset().toUnit();
        var escrowUnits = escrow.getAmount().stream()
                .filter(a -> a.getQuantity() != null && a.getQuantity().signum() != 0)
                .collect(java.util.stream.Collectors.toMap(Amount::getUnit, Amount::getQuantity,
                        BigInteger::add));
        Set<String> expectedEscrowUnits = principalIsAda
                ? Set.of("lovelace") : Set.of("lovelace", principalUnit);
        if (!escrowUnits.keySet().equals(expectedEscrowUnits)) {
            return refuse(escrow, loanId, CompoundExclusion.ESCROW_SHAPE_REJECTED,
                    "escrow carries units " + escrowUnits.keySet() + "; ordinary "
                            + (principalIsAda ? "ADA" : "native") + " compound requires exactly "
                            + expectedEscrowUnits + " and refuses receipts or unrelated assets");
        }

        if (poolId == null || poolId.isBlank()) {
            return refuse(escrow, loanId, CompoundExclusion.BOND_NAMES_NO_POOL,
                    "the lender bond names no pool, so this escrow can never be compounded by anyone");
        }

        var pair = resolver.resolvePool(poolId);
        if (pair.outcome() != LiquidationUtxoResolver.PoolLookup.RESOLVED) {
            return new CompoundCandidate(loanId, escrow, datum, null, bond, poolId, null, null,
                    0L, principalIsAda, CompoundExclusion.POOL_NOT_LIVE, pair.detail());
        }

        BigInteger addedLiquidity = principalAmount(escrow, bond);
        FeeDecode decodedFee = decodeCompoundingFee(pair.poolManager());
        if (decodedFee.exclusion() != null) {
            return new CompoundCandidate(loanId, escrow, datum, addedLiquidity, bond, poolId,
                    pair.pool(), pair.poolManager(), 0L, principalIsAda,
                    decodedFee.exclusion(), decodedFee.detail());
        }

        // Admission uses THE fee the validator computes (CompoundEconomics.compoundingFee): an ADA pool
        // pays at least 1 ADA since FluidTokens' 2026-10-26 update, so an ADA escrow of 1 ADA or less
        // leaves the pool nothing (Q - F <= 0) and pool_compound_action's addedPrincipal > 0 refuses it.
        // There is deliberately no c == 0 skip: an ADA pool at c = 0 pays the floor, and a token pool at
        // c = 0 pays 0 and is decided by the escrow ADA the bot recovers.
        long feePerMille = decodedFee.value();
        BigInteger fee = CompoundEconomics.compoundingFee(addedLiquidity, feePerMille,
                bond.datum().principalAsset());
        if (addedLiquidity.signum() <= 0 || addedLiquidity.subtract(fee).signum() <= 0) {
            return new CompoundCandidate(loanId, escrow, datum, addedLiquidity, bond, poolId,
                    pair.pool(), pair.poolManager(), feePerMille, principalIsAda,
                    CompoundExclusion.PRINCIPAL_AMOUNT_INVALID,
                    "principal must satisfy Q > 0 and Q - F > 0, where F is the validator's compounding "
                            + "fee (" + (principalIsAda ? "ADA: max(Q*c/1000, 1 ADA)" : "token: Q*c/1000")
                            + "); Q=" + addedLiquidity + ", c=" + feePerMille + ", F=" + fee);
        }

        return new CompoundCandidate(loanId, escrow, datum, addedLiquidity, bond, poolId,
                pair.pool(), pair.poolManager(), feePerMille, principalIsAda, null, pair.detail());
    }

    private CompoundCandidate refuse(Utxo escrow, String loanId, CompoundExclusion why, String detail) {
        return new CompoundCandidate(loanId, escrow, null, null, null, null, null, null,
                0L, false, why, detail);
    }

    /**
     * {@code quantity_of(assetInput.output.value, principalAsset…)} — for an ada-principal pool this
     * is the escrow's <b>entire</b> lovelace, which is what the validator sums into
     * {@code addedLiquidity}. Not a net-of-min-ada figure: the escrow UTxO is consumed whole.
     */
    static BigInteger principalAmount(Utxo escrow, LenderBond bond) {
        // AssetType.toUnit() already yields "lovelace" for ada, so this covers both cases.
        String unit = bond.datum().principalAsset().toUnit();
        return escrow.getAmount().stream()
                .filter(a -> unit.equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    /**
     * Field 1 of {@code PoolManagerDatum { poolOwnerAuth, compoudingFeePerMille }}.
     *
     * <p>Only the fee is read. {@code poolOwnerAuth} governs who may cancel or edit the pool and has
     * no bearing on compounding — {@code pm_compound_liquidity} authorises nothing of its own
     * (findings D-17) — so decoding it would be modelling a field to ignore it.
     *
     * <p>Zero is accepted only when it is present as the exact integer zero. Missing, malformed,
     * overflowing and out-of-range values remain distinct refusals.
     */
    private record FeeDecode(long value, CompoundExclusion exclusion, String detail) {
        static FeeDecode accepted(long value) {
            return new FeeDecode(value, null, null);
        }

        static FeeDecode refused(CompoundExclusion exclusion, String detail) {
            return new FeeDecode(0L, exclusion, detail);
        }
    }

    static FeeDecode decodeCompoundingFee(Utxo poolManager) {
        if (poolManager.getInlineDatum() == null || poolManager.getInlineDatum().isBlank()) {
            return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_MISSING,
                    "pool manager datum is absent");
        }
        try {
            var constr = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(poolManager.getInlineDatum())));
            var fields = constr.getData().getPlutusDataList();
            if (fields.size() < 2) {
                return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_MISSING,
                        "pool manager datum has no compoudingFeePerMille field");
            }
            if (!(fields.get(1) instanceof BigIntPlutusData fee)) {
                return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_MALFORMED,
                        "pool manager compoudingFeePerMille is not an integer");
            }
            final long value;
            try {
                value = fee.getValue().longValueExact();
            } catch (ArithmeticException e) {
                return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_OVERFLOW,
                        "pool manager compoudingFeePerMille exceeds the host integer range");
            }
            if (value < 0L || value > 999L) {
                return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_OUT_OF_RANGE,
                        "pool manager compoudingFeePerMille=" + value + " is outside [0,999]");
            }
            return FeeDecode.accepted(value);
        } catch (Exception e) {
            return FeeDecode.refused(CompoundExclusion.POOL_MANAGER_FEE_MALFORMED,
                    "could not decode pool manager datum: " + e.getClass().getSimpleName());
        }
    }

    /** Legacy fixture helper; production classification consumes the full named decode result. */
    static long compoundingFeePerMille(Utxo poolManager) {
        FeeDecode decoded = decodeCompoundingFee(poolManager);
        if (decoded.exclusion() != null) {
            throw new IllegalArgumentException(decoded.exclusion() + ": " + decoded.detail());
        }
        return decoded.value();
    }
}
