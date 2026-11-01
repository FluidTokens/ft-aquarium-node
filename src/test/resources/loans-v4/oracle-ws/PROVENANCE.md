# `oracle-ws/` — preview oracle WebSocket pushes and the API snapshots around them

Two FluidTokens **preview** oracle WebSocket messages, from
`wss://2tgda3mk4l.execute-api.eu-west-1.amazonaws.com/dev`, and three
`GET https://testapi.fluidtokens.com/get-oracle-tokens` snapshots taken around them, all on 2026-10-29.

- **Push shape:** `{"type":"oracle-tokens","timestamp":<ms>,"tokens":[...]}`. The `tokens` array has the
  same entry shape as the API array.
  - `preview-push-20261029T130459Z.json`: `timestamp` 1793279099046 (2026-10-29T13:04:59.046Z),
    received 13:04:59.194Z.
  - `preview-push-20261029T130959Z.json`: `timestamp` 1793279399502 (13:09:59.502Z), received
    13:09:59.572Z.
  - Each holds **5 entries: 3 `c3` and 2 `multisig`**, all `oracleVersion` 1.
- **API snapshots:** `preview-api-20261029T130505Z.json`, `preview-api-20261029T130525Z.json` and
  `preview-api-20261029T131027Z.json`, taken at 13:05:05Z, 13:05:25Z and 13:10:27Z.
- **Once nulls are dropped, push 1 equals the 13:05:25Z snapshot and push 2 equals the 13:10:27Z
  snapshot.** The 13:05:05Z snapshot differs from push 1 only in the three c3 `validFrom`/`validTo`
  windows: the push arrived before the API had moved to the new windows.
- The API carries `supportedOracle.c3.feedId: null` and `supportedOracle.c3.pointerReferenceInput: null`,
  which the push omits.

Signatures and validity windows in these payloads are long expired; they are fixtures for SHAPE and
EQUALITY (push against API), never for pricing at "now".
