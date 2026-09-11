# Mainnet Lending v4 configuration provenance

Observed read-only through Blockfrost mainnet HTTP `GET` endpoints on 2026-09-11. The snapshots in
this directory are the inline datum bytes returned for the unspent outputs below.

| Item | Coordinate | Identity | Datum/script hash | Observed (UTC) |
|---|---|---|---|---|
| Config | `ffced74c7936e803d9f3aedd5abe7e5261e14515dc1a0b045cdb2f03c8b0d36b#0` | NFT `db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416706172616d6574657273`, quantity 1 | datum `e17ddc288e5a9ea6925aa39a2d2738407555d97ee7a6e454329999a615a39089` | `2026-09-11T19:47:03.995099Z` |
| LenderManager config | `1c4a91283f9fc2bffe13c0b10584b1d1492f770e910bd858da8586c395a8bdaa#0` | NFT `a56b0ac2654663f395601601a7825649e5488905648747e912d870e4706172616d6574657273`, quantity 1 | datum `776170a7a5296fddbcdba7d887428e11f89ea8f5f41dfc48d3e9565f7f9fd35a` | `2026-09-11T19:47:04.165445Z` |
| LenderManager compound action | `8d92115bb26dece0f197b110b0cf2c9bfa5f542cb1fd4dc53e595f1a1b73341a#0` | Plutus V3 reference script | `ad34c3db53d20c1e368d7fea64724a0b0249b603a57c8f2a1670bda6`, 5,144 bytes | `2026-09-11T19:47:54.663686Z` |

Transaction-output reads and current address-UTxO reads both reported the three outputs unspent.
The Config datum is constructor 0 with 29 fields; the LenderManager datum is constructor 0 with 8
fields. Relative to the preceding deployment, ConfigDatum field 24 changed pool-sell action hash
from `e3fbf7d5ab00c63bd8107782d3804ff93a9d327ef0896c744b43f672` to
`12773eaf55b80546be4e6afb87cc6e1073049be5f8271ac0c117a006`, and LMConfigDatum field 3 changed
compound action hash from `1551bd4efdef76f3184798331e1c74f6a1cef51955b0c96b8db18d1f` to
`ad34c3db53d20c1e368d7fea64724a0b0249b603a57c8f2a1670bda6`.

The matching compiled artifact is FluidTokens `ft-cardano-loans-v4` revision
`4c4d14346b42078ca1680a8ca9c28364319c933b`, whose committed `plutus.json` SHA-256 is
`63f5fcf395c5a3e76c211e71e8a327aeb1009205e0773b2bdb732ab8020904a5`. The preserved
preview/legacy artifact SHA-256 is
`768c951b65f301e697a2d08088b3ef59a596d6471300977371cc1c7258d3fb09`. Applying the mainnet
parameters to the selected artifact reproduces every published Config and LenderManager hash. The
captured compound CBOR in `mainnet-compound-script.hex` is byte-identical to the parameterized
registry output. The convert-action hash remains
`c3f51e55dd156a4c29a41df0d630b0d8f1c96f396f5a317788a94b70`.

The actual reward account, `stake17xknfs7m20fqc83k34l75erjfg9syjdkqwjhere2zectmfs2w9fv9`,
was reported registered (`active=false`) at `2026-09-11T19:47:54.826700Z`. Its latest registration
was the publication transaction above, with a 2,000,000 lovelace deposit.

Limits: Blockfrost is the single chain provider for these observations. The snapshots prove artifact,
parameter, datum and reference-script compatibility at the observation time; they do not prove future
unspent state, transaction profitability, phase-1 validity, operator wallet readiness, or that any
transaction has been or will be signed or submitted.
