# FluidTokens' fixed convert action, unapplied — `bb4349c`

`ft-bb4349c-convert-action-unapplied.hex` is the `compiledCode` of
`lender_manager/lm_liquidate_and_convert_action.actionValidator.withdraw`, copied verbatim from
**FluidTokens' committed `plutus.json` at `bb4349c25bedc0d3a46f3a131a1aea4aec29fdf9`**
(`fixed minswap order datum`, 2026-09-04) in `../ft-cardano-loans-v4`.

- unapplied hash, as their blueprint declares it: `4ef9f7274455d7975b8b7916b75ea08dcea7f481d6f9df89065c996f`
- the commit changes exactly one validator; compiler unchanged at `v1.1.21+42babe5`
- previous (deployed until 11:19 UTC) unapplied hash: `8c96b1ffda1500d821a40563473a229e1cbed0a10fe3c6eaf8430b19`

**Why it is here and not in `src/main`.** The vendored `loans-v4.plutus.json` must stay
byte-identical to the commit that is DEPLOYED — that identity is what makes the runtime hash
derivation valid. This file exists so `MainnetConvertActionDerivationTest` can answer whether
`bb4349c` *is* that commit, without pre-emptively vendoring it. **Delete it when the main blueprint
is re-vendored; keeping both is how two artefacts start disagreeing.**
