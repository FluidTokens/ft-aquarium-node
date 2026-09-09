package com.fluidtokens.aquarium.offchain.model.loans;

import com.bloxbean.cardano.client.api.model.Utxo;

import java.math.BigInteger;

/**
 * One asset-manager escrow, resolved as far as the index allows, with the reason it cannot proceed
 * when it cannot.
 *
 * <p>Refused candidates are carried rather than dropped: "no compound candidates" and "eight
 * candidates, all with burned pools" are very different states, and the second must not present as
 * the first (findings §20.2).
 *
 * @param loanId      the escrow's {@code ownerAsset} asset name — the loan this escrow descends from
 * @param escrow      the unspent asset-manager UTxO holding the repaid principal
 * @param escrowDatum its decoded datum, or {@code null} when it could not be decoded as token-owned
 * @param addedLiquidity the quantity of the pool's principal asset in the escrow — for an
 *                       ADA-principal pool this is the escrow's <b>entire</b> lovelace, matching
 *                       {@code quantity_of(assetInput.output.value, principalAsset…)} in
 *                       {@code lm_compound_action}
 * @param bond        the lender bond naming the pool, or {@code null} when none was found
 * @param poolId      the bond's {@code poolId}, or {@code null}
 * @param pool        the pool UTxO when the pair resolved, else {@code null}
 * @param poolManager the pool-manager UTxO when the pair resolved, else {@code null}
 * @param feePerMille {@code compoudingFeePerMille} from the pool manager's datum, or 0 when unknown
 * @param principalIsAda whether the bond's {@code principalAsset} is ada
 * @param exclusion   why this cannot proceed, or {@code null} when it is structurally ready
 * @param detail      a human-readable account of the above, for the decision log
 */
public record CompoundCandidate(String loanId,
                                Utxo escrow,
                                AssetManagerDatumWithToken escrowDatum,
                                BigInteger addedLiquidity,
                                LenderBond bond,
                                String poolId,
                                Utxo pool,
                                Utxo poolManager,
                                long feePerMille,
                                boolean principalIsAda,
                                CompoundExclusion exclusion,
                                String detail) {

    /** True when every structural precondition the validator checks is satisfied. */
    public boolean structurallyReady() {
        return exclusion == null;
    }

    public String escrowRef() {
        return escrow == null ? "<none>" : escrow.getTxHash() + "#" + escrow.getOutputIndex();
    }
}
