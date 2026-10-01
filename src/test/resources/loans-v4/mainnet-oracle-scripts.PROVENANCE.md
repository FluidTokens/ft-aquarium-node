# `mainnet-oracle-script-<hash8>.hex`

The deployed, APPLIED FluidTokens oracle validators on **mainnet**, fetched from Koios
`script_info` on 2026-10-01 (read-only). The applied parameters (verification keys, threshold,
Charli3/Orcfax specs) are not published, so the deployed code is the only source.

| file | script hash | registry entries withdrawing from it | Koios size |
| --- | --- | --- | --- |
| `mainnet-oracle-script-a0bb657d.hex` | `a0bb657d97d04dc66abee40da7ef94b92fea67ef892aff22c153448e` | NIGHT v1 | 4104 |
| `mainnet-oracle-script-756897fd.hex` | `756897fd5f7f838ae49b244efd813207ef525e6c5ab85c3e2fdc79a5` | NIGHT v2 | 4122 |
| `mainnet-oracle-script-d81a8bea.hex` | `d81a8bea722dc9487e7e693487f6948983af45c3f7897bf1d37916c8` | FLDT v1 **and** v2 (shared) | 4131 |

`MainnetOracleVersionsDryEvalTest` asserts each file hashes to the withdraw credential of the
registry entry it is used for — that assertion is what makes it the real validator.
