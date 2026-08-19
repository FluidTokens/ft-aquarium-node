package com.fluidtokens.aquarium.offchain.model.loans;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.List;

/**
 * Everything the registry knows about one asset's oracle — the price, and the deployment a
 * liquidation transaction has to reference.
 * <p>
 * There is one of these per priced asset, not one per protocol: the registry publishes 19 entries
 * with 19 distinct reward addresses. That is forced by the validator, because
 * {@code retrieve_oracle_data} resolves its feed with
 * {@code pairs.get_first(redeemers, Withdraw(oraclePaymentCredential))} — one redeemer per
 * credential. A loan with a token principal <em>and</em> token collateral therefore needs two
 * separate oracle withdrawals, one per leg, each with its own reference input.
 *
 * @param token           the asset being priced, as it appears inside the signed feed
 * @param oracleToken     the oracle's own NFT. This is what a loan datum points at
 *                        ({@code collateral.oracleTokenAsset}), and what
 *                        {@code retrieve_oracle_data} requires the reference input to hold, so it
 *                        — not {@link #token} — is the authoritative way to find the right oracle
 *                        for a given loan.
 * @param referenceInput  the UTxO holding {@link #oracleToken}, included as a reference input
 * @param referenceScript the UTxO the oracle script is published at
 * @param verificationKeys the keys {@link OracleSignature#keyPosition()} indexes into. Believed to
 *                        match the validator's {@code verification_keys} parameter in order; see
 *                        docs/auto-liquidation-design.md §6.3 for why that is not yet proven.
 * @param threshold       how many valid signatures the validator requires. Taken from the entry
 *                        level; the registry sometimes reports a different number alongside the
 *                        feed, which is why {@link #signatures()} carries every published
 *                        signature rather than a threshold-sized subset.
 * @param charlieProviderReferenceInput the Charli3 provider UTxO a {@code PRICE_DATA_CHARLIE}
 *                        feed is validated against, from {@code supportedOracle.c3.referenceInput}.
 *                        Null for every non-c3 entry, and null for a c3 entry whose registry node
 *                        omits it. c3 feeds carry no signature over their own bytes — the validator
 *                        checks them structurally against this reference input instead — so this,
 *                        not a signature count, is what decides whether one is liquidatable.
 */
public record OracleEntry(AssetType token,
                          AssetType oracleToken,
                          String rewardAddress,
                          String withdrawCredentialHash,
                          TransactionInput referenceInput,
                          TransactionInput referenceScript,
                          List<String> verificationKeys,
                          int threshold,
                          OraclePriceFeed feed,
                          List<OracleSignature> signatures,
                          TransactionInput charlieProviderReferenceInput) {

    public OracleEntry {
        verificationKeys = List.copyOf(verificationKeys);
        signatures = List.copyOf(signatures);
    }

    /**
     * Whether this entry could satisfy the validator as it stands. Signatures whose key could not
     * be located are dropped during parsing, so a short list here means the redeemer would fail.
     */
    public boolean hasEnoughSignatures() {
        return threshold > 0 && signatures.size() >= threshold;
    }

    /**
     * Whether a liquidation could actually be built against this oracle today.
     * <p>
     * A price is not sufficient. First, no parseable {@code fluidOracle.referenceInput} means no
     * liquidation can be built for <em>any</em> variant — {@code retrieve_oracle_data} requires that
     * reference input (the UTxO holding {@link #oracleToken}) to be present, and a
     * {@code null} {@link #referenceInput} is a parse-time property that never resolves later — so
     * this fails closed regardless of signatures or Charli3 backing. Beyond that:
     * {@code AGGREGATED}/{@code DEDICATED} feeds need enough resolved signatures; a
     * {@code PRICE_DATA_CHARLIE} feed carries none at all — it is validated structurally against
     * {@link #charlieProviderReferenceInput}, so it is usable exactly when that reference input is
     * known. {@code PRICE_DATA_ORCFAX}/{@code POOLED} are not modelled and stay unusable.
     */
    public boolean usableForLiquidation() {
        if (referenceInput == null) {
            return false;
        }
        return switch (feed.variant()) {
            case AGGREGATED, DEDICATED -> hasEnoughSignatures();
            case PRICE_DATA_CHARLIE -> charlieProviderReferenceInput != null;
            case PRICE_DATA_ORCFAX, POOLED -> false;
        };
    }
}
