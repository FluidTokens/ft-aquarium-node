package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.supplier.ogmios.OgmiosTransactionEvaluator;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.blueprint.staker.SpendValidator;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumParametersConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.RedeemerStakerData;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;
import static com.fluidtokens.aquarium.offchain.PreviewTestConstants.*;

@Slf4j
public class PreviewStakingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Network network = preview();

    private final SpendValidator spendValidator = new SpendValidator(network);

    private final DatumParametersConverter datumParametersConverter = new DatumParametersConverter();

    private final BFBackendService bfBackendService = new BFBackendService(Constants.BLOCKFROST_PREVIEW_URL, BF_PREVIEW_KEY);

    QuickTxBuilder quickTxBuilder = new QuickTxBuilder(bfBackendService);

    @Test
    public void fetchParameters() throws Exception {

        log.info("BF_PREVIEW_KEY: {}", BF_PREVIEW_KEY);

        var parametersUtxoResponse = bfBackendService
                .getUtxoService()
                .getTxOutput(PARAMETERS_REF_INPUT_TX_ID, PARAMETERS_REF_INPUT_OUTPUT_INDEX);

        if (!parametersUtxoResponse.isSuccessful()) {
            log.info("error: {}", parametersUtxoResponse.getResponse());
            Assertions.fail();
        }

        var parametersUtxo = parametersUtxoResponse.getValue();

        log.info("parametersUtxo: {}", parametersUtxo);

        var parameters = datumParametersConverter.deserialize(parametersUtxo.getInlineDatum());
        log.info("parameters: {}", parameters);
        log.info("parameters.getMintToStake: {}", parameters.getMinToStake());
        log.info("parameters.getOwner: {}", HexUtil.encodeHexString(parameters.getOwner()));

    }

    @Test
    public void stake() throws Exception {

        var adminAccount = new Account(network, MNEMONIC_ADMIN);
        log.info("adminAccount: {}", adminAccount.baseAddress());

        PlutusScript stakerScript = spendValidator.getPlutusScript();
        var scriptAddress = AddressProvider.getBaseAddress(Credential.fromScript(stakerScript.getScriptHash()),
                Credential.fromKey(adminAccount.getBaseAddress().getDelegationCredentialHash().get()),
                network);

        var stakingNft = Asset.builder()
                .name("0x" + HexUtil.encodeHexString(adminAccount.getBaseAddress().getDelegationCredentialHash().get()))
                .value(BigInteger.ONE)
                .build();

        var stakingNftAmount = Value.builder()
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(stakerScript.getPolicyId())
                                .assets(List.of(stakingNft))
                                .build()
                ))
                .build();
        log.info("stakingNftAmount: {}", stakingNftAmount);

        var stake = Value.builder()
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(TEST_FT_TOKEN_POLICY_ID)
                                .assets(List.of(Asset.builder()
                                        .name("0x" + TEST_FT_TOKEN_ASSET_NAME)
                                        .value(BigInteger.valueOf(30_000_000_000L))
                                        .build()))
                                .build()
                ))
                .build();
        log.info("stake: {}", stake);

        var valueToSend = stakingNftAmount.plus(stake);
        valueToSend.setCoin(BigInteger.valueOf(1_500_000L));
        log.info("valueToSend: {}", valueToSend);

        var redeemer = new RedeemerStakerData();
        redeemer.setSigner(AddressUtil.toOnchainAddress(adminAccount.getBaseAddress()));
        redeemer.setReferenceIndex(BigInteger.ZERO);
        redeemer.setOutputStaking(BigInteger.ZERO);

        var tx = new ScriptTx()
                .mintAsset(stakerScript, stakingNft, redeemer.toPlutusData())
                .payToContract(scriptAddress.getAddress(), ValueUtil.toAmountList(valueToSend), (PlutusData) null)
                .readFrom(TransactionInput.builder()
                        .transactionId(PARAMETERS_REF_INPUT_TX_ID)
                        .index(PARAMETERS_REF_INPUT_OUTPUT_INDEX)
                        .build());

        var transaction = quickTxBuilder.compose(tx)
                .feePayer(adminAccount.baseAddress())
                .collateralPayer(adminAccount.baseAddress())
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withSigner(SignerProviders.stakeKeySignerFrom(adminAccount))
                .withRequiredSigners(adminAccount.getBaseAddress().getDelegationCredentialHash().get())
                .withTxEvaluator(ogmiosTE())
                .completeAndWait();

//        log.info("tx: {}", objectMapper.writeValueAsString(transaction));
//        log.info("tx: {}", HexUtil.encodeHexString(transaction.serialize()));


    }


    @Test
    public void unstake() throws Exception {

        var adminAccount = new Account(network, MNEMONIC_ADMIN);
        log.info("adminAccount: {}", adminAccount.baseAddress());

        PlutusScript stakerScript = spendValidator.getPlutusScript();
        var scriptAddress = AddressProvider.getBaseAddress(Credential.fromScript(stakerScript.getScriptHash()),
                Credential.fromKey(adminAccount.getBaseAddress().getDelegationCredentialHash().get()),
                network);

        log.info("Staker script hash: {}", HexUtil.encodeHexString(spendValidator.getPlutusScript().getScriptHash()));

        var stakingUtxos = bfBackendService.getUtxoService().getUtxos(scriptAddress.getAddress(), 10, 1)
                .getValue();

        log.info("stakingUtxos size: {}", stakingUtxos.size());

        var stakingUtxo = stakingUtxos.getFirst();
        log.info("stakingUtxo: {}:{}", stakingUtxo.getTxHash(), stakingUtxo.getOutputIndex());

        var stakingNft = Asset.builder()
                .name("0x" + HexUtil.encodeHexString(adminAccount.getBaseAddress().getDelegationCredentialHash().get()))
                .value(BigInteger.ONE.negate())
                .build();

        var redeemer = new RedeemerStakerData();
        redeemer.setSigner(AddressUtil.toOnchainAddress(adminAccount.getBaseAddress()));
        redeemer.setReferenceIndex(BigInteger.ZERO);
        redeemer.setOutputStaking(BigInteger.ZERO);

        var tx = new ScriptTx()
                .collectFrom(stakingUtxo, redeemer.toPlutusData())
                .mintAsset(stakerScript, stakingNft, redeemer.toPlutusData())
                .readFrom(TransactionInput.builder()
                        .transactionId(PARAMETERS_REF_INPUT_TX_ID)
                        .index(PARAMETERS_REF_INPUT_OUTPUT_INDEX)
                        .build())
                .withChangeAddress(adminAccount.baseAddress());

        quickTxBuilder.compose(tx)
                .feePayer(adminAccount.baseAddress())
                .collateralPayer(adminAccount.baseAddress())
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withSigner(SignerProviders.stakeKeySignerFrom(adminAccount))
                .withRequiredSigners(adminAccount.getBaseAddress().getDelegationCredentialHash().get())
                .withTxEvaluator(ogmiosTE())
                .completeAndWait();

    }


    private TransactionEvaluator ogmiosTE() {
        return new OgmiosTransactionEvaluator("http://ryzen:31357");
    }


}
