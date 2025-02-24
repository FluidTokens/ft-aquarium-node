package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.blueprint.tank.SpendValidator;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumTankConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.impl.DatumTankData;
import com.fluidtokens.aquarium.offchain.blueprint.types.general.model.impl.CardanoTokenData;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bloxbean.cardano.client.backend.blockfrost.common.Constants.BLOCKFROST_MAINNET_URL;
import static com.bloxbean.cardano.client.common.model.Networks.mainnet;

@Slf4j
public class MainnetTankTest {

    private final String blockfrostMainnetKey = System.getenv("BLOCKFROST_KEY");

    private final String mainnetMnemonic = System.getenv("MNEMONIC_DEV");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Network network = mainnet();

    private final BFBackendService bfBackendService = new BFBackendService(BLOCKFROST_MAINNET_URL, blockfrostMainnetKey);

    private final QuickTxBuilder quickTxBuilder = new QuickTxBuilder(bfBackendService);

    private final SpendValidator spendValidator = new SpendValidator(network);

    private final DatumTankConverter datumConverter = new DatumTankConverter();

    @Test
    public void deployContract() throws Exception {

        PlutusScript recurringPaymentsContract = spendValidator.getPlutusScript();

        var adminAddress = new Account(network, mainnetMnemonic);

        Tx createRefInputTx = new Tx()
                .from(adminAddress.getBaseAddress().getAddress())
                .payToAddress("addr1qymctj0fjzmjyd2rjfsaqull7x05fd3g5dv7h4pcnw6cw2ranpky7a73z2g0kn7g8ax0xqvj0w7u0pftkgx2tsz5z70q5ndm2w", List.of(Amount.ada(1)), recurringPaymentsContract)
                .withChangeAddress(adminAddress.getBaseAddress().getAddress());

        quickTxBuilder.compose(createRefInputTx)
                .feePayer(adminAddress.getBaseAddress().getAddress())
                .withSigner(SignerProviders.signerFrom(adminAddress))
                .mergeOutputs(false)
                .completeAndWait();

    }

    @Test
    public void setupPayment() throws Exception {

        var account = new Account(network, "MNEMONIC_USER");

        var fiveMinutesAgoMs = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1_000;

        DatumTankData datum = new DatumTankData();

        var allowedToken = new CardanoTokenData();
        allowedToken.setPolicyid(new byte[0]);
        allowedToken.setAssetname(new byte[0]);
        allowedToken.setAmount(BigInteger.ONE);
        allowedToken.setDivider(BigInteger.ONE);
        allowedToken.setOracle(Optional.empty());
        allowedToken.toPlutusData();

        var scheduledAmount = new CardanoTokenData();
        scheduledAmount.setPolicyid(new byte[0]);
        scheduledAmount.setAssetname(new byte[0]);
        scheduledAmount.setAmount(BigInteger.valueOf(10_000_000L));
        scheduledAmount.setDivider(BigInteger.ONE);
        scheduledAmount.setOracle(Optional.empty());

        var rewardAmount = new CardanoTokenData();
        rewardAmount.setPolicyid(new byte[0]);
        rewardAmount.setAssetname(new byte[0]);
        rewardAmount.setAmount(BigInteger.valueOf(2_000_000));
        rewardAmount.setDivider(BigInteger.ONE);
        rewardAmount.setOracle(Optional.empty());

        datum.setAllowedtokens(List.of(allowedToken));
        datum.setTankowner(AddressUtil.toOnchainAddress(account.getBaseAddress()));
        datum.setWhitelistedaddresses(List.of());
        datum.setExecutiontime(BigInteger.valueOf(fiveMinutesAgoMs));
        datum.setDestionationaaddress(AddressUtil.toOnchainAddress(new Address("addr_test1qqgern5qmhfqlztqfkk7wjfc8qvadlsjc2xhwm45nrdzudn3fakarjxlcrdwsee2wtcja4l3neq6dfxernah25938dsskl9d9z")));
        datum.setScheduledamount(scheduledAmount);
        datum.setReward(rewardAmount);


        var tankOwnerAddress = AddressProvider.getEntAddress(spendValidator.getPlutusScript(), network);

        var tx = new Tx()
                .from(account.baseAddress())
                .payToContract(tankOwnerAddress.getAddress(), Amount.ada(12), datum.toPlutusData());

        quickTxBuilder
                .compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .completeAndWait();

//        PlutusScript tankScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(FT_TANK_CONTRACT_PREVIEW_BYTES, PlutusVersion.v3);
//        log.info("tank script hash: {}", HexUtil.encodeHexString(tankScript.getScriptHash()));
//
//        var account = new Account(preview(), MNEMONIC);
//
//        var tankDatum = TankDatum.builder()
//                .allowedTokens(List.of(CardanoToken.builder()
//                        .policyId(new byte[0])
//                        .assetName(new byte[0])
//                        .amount(ONE).divider(ONE).build()))
//                .tankOwner(Address.toAddress(account.getBaseAddress()))
//                .whitelistedAddresses(List.of())
//                .executionTime(BigInteger.valueOf(1727514633000L))
//                .destinationAddress(Address.toAddress(new com.bloxbean.cardano.client.address.Address("addr_test1qqgern5qmhfqlztqfkk7wjfc8qvadlsjc2xhwm45nrdzudn3fakarjxlcrdwsee2wtcja4l3neq6dfxernah25938dsskl9d9z")))
//                .scheduledAmount(CardanoToken.builder()
//                        .policyId(new byte[0])
//                        .assetName(new byte[0])
//                        .amount(BigInteger.valueOf(10_000_000L)).divider(ONE).build())
//                .reward(CardanoToken.builder()
//                        .policyId(new byte[0])
//                        .assetName(new byte[0]).amount(BigInteger.valueOf(2_000_000L)).divider(ONE).build())
//                .build();
//
//        var tankOwnerAddress = AddressProvider.getBaseAddress(Credential.fromScript(tankScript.getScriptHash()),
//                Credential.fromKey(account.getBaseAddress().getDelegationCredentialHash().get()), preview());
//
//        var datum = TankDatumConverter.toPlutusData(tankDatum);
//
//        var tx = new Tx()
//                .from(account.baseAddress())
//                .payToContract(tankOwnerAddress.getAddress(), Amount.ada(12), datum);
//
//        quickTxBuilder
//                .compose(tx)
//                .withSigner(SignerProviders.signerFrom(account))
//                .completeAndWait();

    }


//    @Test
//    public void executePayment() throws Exception {
//
//        PlutusScript tankScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(FT_TANK_CONTRACT_PREVIEW_BYTES, PlutusVersion.v3);
//        log.info("tank script hash: {}", HexUtil.encodeHexString(tankScript.getScriptHash()));
//
//        BFBackendService bfBackendService = new BFBackendService(Constants.BLOCKFROST_PREVIEW_URL, BF_PREVIEW_KEY);
//
//        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(bfBackendService);
//
//        var adminAccount = new Account(preview(), MNEMONIC_ADMIN);
//
//        var tankPaymentAddress = "addr_test1zrym3a0dra3jhtkhev4lsp2z727wu323qeqe3gzclcp6lc0a8f4leccdwaz2c40fe7g5dk9z5p8v07evuthxnp3xqefsev2208";
//
//        var tankPaymentUtxos = bfBackendService.getUtxoService().getUtxos(tankPaymentAddress, 1, 1).getValue();
//        log.info("found {} scheduled payments", tankPaymentUtxos.size());
//
//
//        tankPaymentUtxos.forEach(tankPaymentUtxo -> {
//            log.info("tankPaymentUtxo: {}", tankPaymentUtxo);
//
//            List<Utxo> walletUtxos;
//            try {
//                walletUtxos = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 10, 1).getValue();
//            } catch (ApiException e) {
//                log.warn("error: ", e);
//                return;
//            }
//            var walletUtxo = walletUtxos
//                    .stream()
//                    .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
//                    .findFirst()
//                    .get();
//
//            var tankDatum = TankDatumConverter.deserialize(tankPaymentUtxo.getInlineDatum());
//            log.info("tankDatum: {}", tankDatum);
//
//            var address = tankDatum.getTankOwner();
//            var paymentCredentials = (VerificationKey) address.getPaymentCredential();
//            var stakeCredentials = (VerificationKey) address.getStakeCredential().get().getCredential();
//
//            var tankOwnerAddress = AddressProvider.getBaseAddress(Credential.fromKey(paymentCredentials.getVerificationKeyHash()),
//                    Credential.fromKey(stakeCredentials.getVerificationKeyHash()), preprod());
//
//            log.info("tankOwnerAddress: {}", tankOwnerAddress.getAddress());
//
//            BigInteger inputTankIndex;
//            if (walletUtxo.getTxHash().compareTo(tankPaymentUtxo.getTxHash()) > 0) {
//                inputTankIndex = BigInteger.ZERO;
//            } else {
//                inputTankIndex = ONE;
//            }
//
//            log.info("inputTankIndex: {}", inputTankIndex);
//
//            var scheduledTransaction = ScheduledTransactionRedeemer.builder()
//                    .inputTankIndex(inputTankIndex)
//                    .batcher(Address.toAddress(adminAccount.getBaseAddress()))
//                    .build();
//
//            var redeemer = scheduledTransactionRedeemerConverter.toPlutusData(scheduledTransaction);
//            redeemer = ConstrPlutusData.of(3, redeemer.getData().getPlutusDataList().toArray(new PlutusData[0]));
//            try {
//                log.info("redeemer: {}", objectMapper.writeValueAsString(redeemer));
//            } catch (JsonProcessingException e) {
//                log.warn("error", e);
//            }
//
//            var amountToSend = tankDatum.getScheduledAmount().toValue();
//
//            var destinationAddress = tankDatum.getDestinationAddress();
//            var payeePaymentCredentials = (VerificationKey) destinationAddress.getPaymentCredential();
//            var payeeStakeCredentials = (VerificationKey) destinationAddress.getStakeCredential().get().getCredential();
//
//            var payeeAddress = AddressProvider.getBaseAddress(Credential.fromKey(payeePaymentCredentials.getVerificationKeyHash()),
//                    Credential.fromKey(payeeStakeCredentials.getVerificationKeyHash()), preprod());
//
//            Long slot;
//            try {
//                slot = bfBackendService.getBlockService().getLatestBlock().getValue().getSlot();
//            } catch (ApiException e) {
//                log.warn("error", e);
//                return;
//            }
//
//            var reward = tankDatum.getReward().toValue();
//
//            var tx = new ScriptTx()
//                    .collectFrom(walletUtxo)
//                    .collectFrom(tankPaymentUtxo, redeemer);
//            if (inputTankIndex.longValue() > 0) {
//                tx.payToAddress(adminAccount.baseAddress(), Amount.ada(1));
//            }
//            tx.payToAddress(payeeAddress.getAddress(), ValueUtil.toAmountList(amountToSend))
//                    .payToAddress(adminAccount.baseAddress(), ValueUtil.toAmountList(reward))
//                    .withChangeAddress(adminAccount.baseAddress())
//                    .readFrom(TransactionInput.builder()
//                            .transactionId("332038e642ea621ae83bb2a2595c319a92104daeecc436bec62ba6539ff46196")
//                            .index(0)
//                            .build())
//                    .attachSpendingValidator(tankScript);
//
//            var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(adminAccount))
//                    .withSigner(SignerProviders.stakeKeySignerFrom(adminAccount))
//                    .withRequiredSigners(adminAccount.getBaseAddress().getDelegationCredentialHash().get())
//                    .validFrom(slot)
//                    .validTo(slot + 180)
//                    .feePayer(adminAccount.baseAddress())
//                    .collateralPayer(adminAccount.baseAddress())
//                    .mergeOutputs(false)
//                    .withTxEvaluator(ogmiosTE())
//                    .completeAndWait();
//
//
//            try {
//                log.info("transaction: {}", objectMapper.writeValueAsString(transaction));
////                log.info("transaction: {}", HexUtil.encodeHexString(transaction.serialize()));
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//
//        });
//
//
//    }


}
