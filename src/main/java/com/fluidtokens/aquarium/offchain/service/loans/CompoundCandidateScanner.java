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
            return refuse(escrow, null, CompoundExclusion.ESCROW_NOT_TOKEN_OWNED,
                    "no inline datum, or not AssetManagerDatumWithToken; nothing token-owned can collect it");
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

        // The validator's shape constraint: an ada-principal escrow must carry lovelace and nothing
        // else. Checked BEFORE the pool lookup because it is cheaper and cannot change.
        if (principalIsAda && escrow.getAmount().size() != 1) {
            return refuse(escrow, loanId, CompoundExclusion.ESCROW_SHAPE_REJECTED,
                    "escrow holds " + escrow.getAmount().size() + " distinct assets; an ada-principal "
                            + "escrow must hold lovelace alone (plus repayment receipt NFTs, which "
                            + "this node cannot yet identify — see the class note)");
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
        long feePerMille = compoundingFeePerMille(pair.poolManager());

        if (!principalIsAda) {
            return new CompoundCandidate(loanId, escrow, datum, addedLiquidity, bond, poolId,
                    pair.pool(), pair.poolManager(), feePerMille, false,
                    CompoundExclusion.PRINCIPAL_NOT_ADA,
                    "pool principal is " + bond.datum().principalAsset()
                            + "; its fee is denominated in that token and cannot be compared to a lovelace tx fee");
        }

        return new CompoundCandidate(loanId, escrow, datum, addedLiquidity, bond, poolId,
                pair.pool(), pair.poolManager(), feePerMille, true, null, pair.detail());
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
     * <p>Returns 0 when it cannot be read, which is the fail-closed direction: a fee of 0 makes the
     * candidate unprofitable and the economics gate refuses it unless the operator has stated
     * otherwise. A wrong high value would be the dangerous error, not a wrong low one.
     */
    static long compoundingFeePerMille(Utxo poolManager) {
        try {
            var constr = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(poolManager.getInlineDatum())));
            var fields = constr.getData().getPlutusDataList();
            if (fields.size() < 2 || !(fields.get(1) instanceof BigIntPlutusData fee)) {
                log.warn("pool manager {}#{} datum has no readable compoudingFeePerMille; treating as 0",
                        poolManager.getTxHash(), poolManager.getOutputIndex());
                return 0L;
            }
            return fee.getValue().longValueExact();
        } catch (Exception e) {
            log.warn("could not decode pool manager {}#{} datum ({}); treating compoudingFeePerMille as 0",
                    poolManager.getTxHash(), poolManager.getOutputIndex(), e.toString());
            return 0L;
        }
    }
}
