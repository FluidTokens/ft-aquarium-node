package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * An indexed Lending v4 lender-bond: the on-chain {@link LenderManagerDatum} plus the UTxO
 * facts that are not in the datum.
 * <p>
 * {@code inlineDatum} is kept alongside the decoded {@link #datum()} because the on-chain
 * validator requires the bond output to be a byte-identical echo of the input — see
 * {@link LenderManagerDatum#lenderStakeCredential()}.
 *
 * @param loanId asset name of the lender-bond NFT — also the loan's asset name, which is the
 *               join key between a loan and its bond ({@code lm_liquidate_action.ak:131-135})
 * @param inlineDatum the raw inline datum hex, needed for the byte-identical echo in T-008
 */
public record LenderBond(String txHash,
                         int outputIndex,
                         String address,
                         String loanId,
                         String inlineDatum,
                         LenderManagerDatum datum) {

    public String utxoRef() {
        return txHash + "#" + outputIndex;
    }
}
