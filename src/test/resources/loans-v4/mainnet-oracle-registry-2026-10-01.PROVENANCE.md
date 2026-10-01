# `mainnet-oracle-registry-2026-10-01.json`

The FluidTokens **mainnet** oracle registry, `GET https://api.fluidtokens.com/get-oracle-tokens`,
fetched 2026-10-01 ~07:55Z. Structurally identical to `qaapi.fluidtokens.com` the same morning.

- **35 entries across 19 tokens.** 16 tokens are listed twice: `oracleVersion: 1` (oracle NFT policy
  `93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b`, Lending v3) at array indices 1–18 and
  `oracleVersion: 2` (policy `26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26`, Lending v4)
  at 19–34. Same asset names, same signing keys, same prices; different reference inputs/scripts.
- v2 NFTs minted 2026-09-30 10:54Z; all 16 v2 entries verified on chain (NFT at `referenceInput`,
  `referenceScript` hash = `rewardAddress` credential = `scriptHash`).
- Every live current-v4 loan (64 on 2026-10-01) names a **v1** oracle.

Signatures and validity windows in this payload are long expired; it is a fixture for REGISTRY
SHAPE (which entries exist and what they point at), never for pricing at "now".
