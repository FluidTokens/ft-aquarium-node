# `mainnet-oracle-script-<hash8>.hex`

The deployed, APPLIED FluidTokens oracle validators on **mainnet**, fetched from Koios
`script_info` on 2026-10-01 (read-only). The applied parameters (verification keys, threshold,
Charli3/Orcfax specs) are not published, so the deployed code is the only source.

| file | script hash | registry entries withdrawing from it | Koios size |
| --- | --- | --- | --- |
| `mainnet-oracle-script-a0bb657d.hex` | `a0bb657d97d04dc66abee40da7ef94b92fea67ef892aff22c153448e` | NIGHT v1 | 4104 |
| `mainnet-oracle-script-756897fd.hex` | `756897fd5f7f838ae49b244efd813207ef525e6c5ab85c3e2fdc79a5` | NIGHT v2 | 4122 |
| `mainnet-oracle-script-d81a8bea.hex` | `d81a8bea722dc9487e7e693487f6948983af45c3f7897bf1d37916c8` | FLDT v1 **and** v2 (shared) | 4131 |
| `mainnet-oracle-script-d97da4f3.hex` | `d97da4f3f2c3757971332b9bfb3c46df18715b059e6ab0fe66ddb036` | STRIKE v1 | 8214 (`wc -c` characters, not Koios size) |
| `mainnet-oracle-script-3a561e40.hex` | `3a561e401144af4f5dfeeb28ad7fe16b33e656ad5099a93e434928a4` | USDM v1 | 8226 (`wc -c` characters, not Koios size) |
| `mainnet-oracle-script-df6f292e.hex` | `df6f292ece90837f738ef156f8940e5f98db2027b3afa8d2041c492d` | USDM v2 | 8262 (`wc -c` characters, not Koios size) |

`MainnetOracleVersionsDryEvalTest` asserts each file hashes to the withdraw credential of the
registry entry it is used for — that assertion is what makes it the real validator.

The last three files (STRIKE v1, USDM v1, USDM v2) were fetched and recorded by FAB-225 and FAB-226; their
source transactions are in `mainnet-strike-usdm-pay-in-advance.PROVENANCE.md` (`d97da4f3`, `3a561e40`) and
`mainnet-ada-collateral-pay-in-advance.PROVENANCE.md` (`df6f292e`). FAB-244 uses them in
`MainnetOracleVersionsDryEvalTest` too.
