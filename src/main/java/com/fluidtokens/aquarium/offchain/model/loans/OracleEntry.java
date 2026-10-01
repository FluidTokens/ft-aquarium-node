package com.fluidtokens.aquarium.offchain.model.loans;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.List;

/**
 * Everything the registry knows about one asset's oracle — the price, and the deployment a
 * liquidation transaction has to reference.
 * <p>
 * There is one of these per ORACLE NFT. Since 2026-09-30 that is not one per priced asset: the
 * registry lists most tokens twice — {@code oracleVersion} 1 (Lending v3) and 2 (Lending v4), under
 * different NFT policies — 35 entries across 19 tokens on 2026-10-01. A loan names exactly one of them
 * ({@link #oracleToken}), and the validator requires exactly that NFT.
 * <p>
 * Two versions may share one withdraw CREDENTIAL (FLDT's v1 and v2 run one script under two NFTs).
 * {@code retrieve_oracle_data} resolves its feed with
 * {@code pairs.get_first(redeemers, Withdraw(oraclePaymentCredential))} — one redeemer per credential —
 * so a transaction carries ONE withdrawal per credential but ONE reference input per NFT its legs name.
 * A loan with a token principal and token collateral behind different credentials needs two
 * withdrawals, one per leg.
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
 * @param oracleVersion   the registry's {@code oracleVersion}: 1 (Lending v3) or 2 (Lending v4) since
 *                        2026-09-30; null when the registry omits it (unknown, never an error).
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
                          TransactionInput charlieProviderReferenceInput,
                          Integer oracleVersion) {

    /**
     * Without a version: what every entry was before FluidTokens added {@code oracleVersion} to the
     * registry on 2026-09-30, and what most fixtures still build.
     */
    public OracleEntry(AssetType token, AssetType oracleToken, String rewardAddress,
                       String withdrawCredentialHash, TransactionInput referenceInput,
                       TransactionInput referenceScript, List<String> verificationKeys, int threshold,
                       OraclePriceFeed feed, List<OracleSignature> signatures,
                       TransactionInput charlieProviderReferenceInput) {
        this(token, oracleToken, rewardAddress, withdrawCredentialHash, referenceInput, referenceScript,
                verificationKeys, threshold, feed, signatures, charlieProviderReferenceInput, null);
    }

    /**
     * ⛔ <b>The oracle a loan names for one LEG — only if it prices that leg's token.</b> The ONE rule
     * every NFT-keyed lookup on a loan's behalf goes through (oracle re-slice, cross-provider finding 2):
     * {@code retrieve_oracle_data}'s {@code is_feed_token_correct} refuses an oracle for another token,
     * so such an entry is no oracle for this leg — never a price to compute with.
     *
     * @return the entry, or null when the map has none for {@code oracleToken} or it prices another token
     */
    public static OracleEntry namedForLeg(java.util.Map<String, OracleEntry> byOracleNft, AssetType asset,
                                          AssetType oracleToken) {
        if (byOracleNft == null || asset == null || oracleToken == null) {
            return null;
        }
        OracleEntry entry = byOracleNft.get(oracleToken.toUnit());
        return entry != null && asset.equals(entry.token()) ? entry : null;
    }

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
