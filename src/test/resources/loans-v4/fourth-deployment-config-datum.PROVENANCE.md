# Fourth-deployment config datums — captured from chain, 2026-09-02

Both fixtures are the **inline datums of the two live preview config UTxOs**, copied verbatim
from Blockfrost. They are the deployment `application.yaml`'s preview profile is pinned to and
the one the running pod uses.

|  | main config | LM config |
|---|---|---|
| file | `fourth-deployment-config-datum.hex` | `fourth-deployment-lm-config-datum.hex` |
| NFT | `d46f626f…706172616d6574657273` | `a7d4b762…706172616d6574657273` |
| asset name | `parameters` | `parameters` |
| UTxO | `8dd38e97…#0` | `8dd38e97…#1` |

**Both NFTs were minted in the same transaction and have NEVER been spent:**

```
tx      8dd38e97b79cc7c8a3c59400944b7cd9f724876a1d49ea17ffb5e49b3785091c
block   4,590,589
time    2026-08-21 10:13:44 UTC
signer  ea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934
        — sole required_signer AND sole vkey witness, and the adminCredential
          in the main config datum's own field 1
```

`/assets/{asset}/history` and `/assets/{asset}/transactions` each return **exactly one entry** for
both NFTs — their creation. So there has been no in-place update and no second config.

> ⚠ **That is a fact with an expiry date.** The admin key can spend these UTxOs at any time and
> replace the datums in place, at the same policy ids, leaving `application.yaml` still pointing at
> coordinates that verify cleanly (findings §12). **If that happens these fixtures go stale and
> `ShippedRegistryMatchesPinnedConfigTest` keeps passing** — it compares code against this snapshot,
> not against the chain. The live counterpart is `LoansConfigVerifierLiveTest`, which is skipped
> unless `BLOCKFROST_KEY` is set. **Re-capture these files whenever that test fails, and never the
> other way round.**

## Do not confuse these with `preview-config-datum.hex`

`preview-config-datum.hex` in this directory is the **THIRD** deployment, kept deliberately because
25 test files replay recorded third-deployment data through `LoanFixtures.registry()`. Comparing a
third-deployment registry against a fourth-deployment config produces 19 mismatches out of 23 and
means nothing — **that mistake was made, published and retracted (findings §19.3 → §21)**, and
`ShippedRegistryMatchesPinnedConfigTest#theThirdDeploymentFixtureMustNotVerify` now pins it.
