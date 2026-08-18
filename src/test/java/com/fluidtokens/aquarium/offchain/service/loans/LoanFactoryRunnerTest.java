package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;

/**
 * Manual runner: builds the create → borrow → recovery-cancel pipeline for the wallet behind
 * {@code WALLET_MNEMONIC} through {@link LoanFactory} and <b>prints</b> each unsigned transaction. In
 * the shape of {@link ReferenceScriptPublishRunnerTest} and this repo's other manual deploy scripts —
 * an ordinary JUnit class gated on environment variables and skipped in an ordinary build.
 *
 * <h2>It does not submit, and it cannot</h2>
 * {@link LoanFactory} holds no {@code TransactionProcessor} and opens no backend; the three landed
 * builders it delegates to each construct their {@code QuickTxBuilder} with a null processor. This
 * class holds no backend and never signs anything, and the transactions printed below are unsigned.
 * The mnemonic is used only to derive the wallet's <b>address and payment key hash</b> — the pool's
 * {@code lenderAuth} and the recovery required signer — and is never logged, printed or used to sign.
 * Originating the first loan <b>on chain</b> is T-016-X and needs Giovanni present; it is out of scope
 * here on purpose.
 *
 * <h2>Two gates, not one</h2>
 * {@code WALLET_MNEMONIC} alone is not enough: {@code .env.preview} sets it for every live test in this
 * repo, and merely having sourced that file must never put a wallet on the path of a loan origination.
 * {@code AQUARIUM_CREATE_LIQUIDATABLE_LOAN=true} is the second, explicit opt-in.
 *
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   AQUARIUM_CREATE_LIQUIDATABLE_LOAN=true \
 *     ./gradlew cleanTest test --tests '*LoanFactoryRunnerTest' -i
 * }</pre>
 *
 * <h2>The funding UTxOs are synthetic</h2>
 * This class has no chain access by design, so it cannot resolve the wallet's real UTxO set; the seed,
 * funder and collateral inputs are synthetic. Every transaction is nonetheless dry-evaluated against
 * the real deployed validators inside {@link LoanFactory} before it is returned, and the fee-100,
 * born-liquidatable and recovery-destination gates all run — so a printed transaction is one the real
 * validators accept and the tool's own gates passed. The sizes and fees will move once real UTxOs are
 * resolved by the submission change.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+",
        disabledReason = "manual origination script: needs the wallet's mnemonic, "
                + "run with `set -a; . ./.env.preview; set +a`")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_CREATE_LIQUIDATABLE_LOAN", matches = "true",
        disabledReason = "second, explicit opt-in: having a mnemonic set must never on its own put a "
                + "wallet on the path of a loan origination")
public class LoanFactoryRunnerTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** Synthetic funding legs; see the class javadoc. */
    private static final long SEED_LOVELACE = 100_000_000L;
    private static final long FUNDER_LOVELACE = 100_000_000L;
    private static final long FUNDER_TFLDT = 20_000_000L;
    private static final long COLLATERAL_LOVELACE = 60_000_000L;

    private static final long LOAN_OUTPUT_LOVELACE = 2_500_000L;
    private static final long BOND_LOVELACE = 1_500_000L;
    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_100L;

    /** The born-liquidatable design price: 0.5 lovelace per tFLDT unit. */
    private static final long PRICE_NUMERATOR = 1L;
    private static final long PRICE_DENOMINATOR = 2L;

    @Test
    public void printTheOriginationPipeline() throws Exception {
        // The wallet. The mnemonic itself is never logged, printed or otherwise emitted — only the
        // address it derives, which is public, and the payment key hash, which the pool commits to.
        String mnemonic = System.getenv("WALLET_MNEMONIC");
        Account wallet = new Account(preview(), mnemonic);
        Address lender = new Address(wallet.baseAddress());

        PoolFixtures.PoolParameters params = PoolFixtures.defaults();

        Utxo seedUtxo = LoanFixtures.adaUtxo(PoolFixtures.TX_SEED, 0, lender.getAddress(), SEED_LOVELACE);
        Utxo funderUtxo = LoanFixtures.utxo("ee".repeat(32), 0, lender.getAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(FUNDER_LOVELACE)),
                        Amount.asset(LoanFixtures.unit(PoolFixtures.TFLDT), BigInteger.valueOf(FUNDER_TFLDT))),
                null);
        Utxo collateralUtxo = LoanFixtures.adaUtxo("ef".repeat(32), 1, lender.getAddress(), COLLATERAL_LOVELACE);

        List<Utxo> baseUniverse = new ArrayList<>(List.of(
                seedUtxo, funderUtxo, collateralUtxo, PoolFixtures.configUtxo()));

        LoanFactory factory = new LoanFactory(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(baseUniverse), LoanFixtures.protocolParams());

        LoanFactory.Recipe recipe = new LoanFactory.Recipe(
                params, lender, lender, PRICE_NUMERATOR, PRICE_DENOMINATOR,
                seedUtxo, funderUtxo, PoolFixtures.configUtxo(), PoolFixtures.poolPolicyRefScriptUtxo(),
                borrowReferenceScriptUtxos(), cancelReferenceScriptUtxos(),
                PoolFixtures.TFLDT, params.principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE, VALID_FROM_SLOT, VALID_TO_SLOT);

        // Every transaction below is dry-evaluated and gated inside LoanFactory before it is returned.
        Transaction create = factory.buildCreate(recipe);
        Transaction borrow = factory.buildBorrow(recipe, create);
        Transaction cancel = factory.buildRecoveryCancel(recipe, create);

        String poolAssetName = PoolTxEncoder.poolAssetName(0, new TransactionInput(seedUtxo.getTxHash(), 0));
        String bondAssetName = PoolTxEncoder.bondAssetName(
                new TransactionInput(TransactionUtil.getTxHash(create), 0));
        BigInteger emittedBondFee = emittedLenderBondFee(borrow, bondAssetName);

        log.info("wallet address (preview base): {}", lender.getAddress());
        log.info("pool NFT name: {}", poolAssetName);
        log.info("born-liquidatable at collateral price {}/{} lovelace per tFLDT unit",
                PRICE_NUMERATOR, PRICE_DENOMINATOR);
        log.info("emitted lender bond fee per mille: {}", emittedBondFee);

        log.info("CREATE unsigned cbor: {}", HexUtil.encodeHexString(create.serialize()));
        log.info("BORROW unsigned cbor: {}", HexUtil.encodeHexString(borrow.serialize()));
        log.info("RECOVERY-CANCEL unsigned cbor: {}", HexUtil.encodeHexString(cancel.serialize()));

        log.info("NOTHING WAS SUBMITTED. This runner has no backend and no signer; the on-chain "
                + "create+borrow is T-016-X and needs Giovanni present.");
    }

    private static BigInteger emittedLenderBondFee(Transaction borrow, String bondAssetName) {
        TransactionOutput bondOutput = borrow.getBody().getOutputs().stream()
                .filter(o -> LoanFixtures.bondAddress().equals(o.getAddress())
                        && o.getValue().getMultiAssets().stream()
                        .filter(ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getLenderBondPolicyId()))
                        .flatMap(ma -> ma.getAssets().stream())
                        .anyMatch(a -> strip(a.getNameAsHex()).equalsIgnoreCase(bondAssetName)))
                .findFirst().orElseThrow(() -> new AssertionError("no lender bond output in the borrow"));
        LenderManagerDatum datum = new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
        return datum.liquidationFeePerMille();
    }

    private static List<Utxo> borrowReferenceScriptUtxos() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolPolicyId(), REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolBorrowActionScriptHash(), REGISTRY.getLoanPolicyId(),
                REGISTRY.getLenderBondPolicyId(), REGISTRY.getBorrowerBondPolicyId()));
    }

    private static List<Utxo> cancelReferenceScriptUtxos() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolSpendScriptHash(), REGISTRY.getPoolPolicyId(),
                REGISTRY.getPoolCancelActionScriptHash()));
    }

    private static List<Utxo> referenceScriptUtxos(List<String> hashes) {
        List<Utxo> utxos = new ArrayList<>();
        for (String hash : hashes) {
            String coord = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(hash);
            String txHash = coord.substring(0, coord.indexOf('#'));
            int index = Integer.parseInt(coord.substring(coord.indexOf('#') + 1));
            utxos.add(Utxo.builder()
                    .txHash(txHash).outputIndex(index)
                    .address(LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash)
                    .build());
        }
        return utxos;
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
