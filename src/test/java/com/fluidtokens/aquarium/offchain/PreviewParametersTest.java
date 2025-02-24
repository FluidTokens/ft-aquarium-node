package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.supplier.ogmios.OgmiosTransactionEvaluator;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.blueprint.parameters.SpendValidator;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.DatumParameters;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumParametersConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.impl.DatumParametersData;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.client.common.model.Networks.preview;
import static com.fluidtokens.aquarium.offchain.PreviewTestConstants.BF_PREVIEW_KEY;
import static java.math.BigInteger.ONE;

@Slf4j
public class PreviewParametersTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Network network = preview();

    private final SpendValidator spendValidator = new SpendValidator(network);

    private final BFBackendService bfBackendService = new BFBackendService(Constants.BLOCKFROST_PREVIEW_URL, BF_PREVIEW_KEY);

    @Test
    public void account() {
        log.info("{}", new Account().mnemonic());
    }

    @Test
    public void mintSettings() throws Exception {


        var settingsContract = spendValidator.getPlutusScript();
        var settingsAddress = AddressProvider.getEntAddress(Credential.fromScript(settingsContract.getScriptHash()), network);
        log.info("settingsContract.getPolicyId(): {}", settingsContract.getPolicyId());

        var admin = new Account(network, PreviewTestConstants.MNEMONIC_ADMIN);
        log.info("admin: {}", admin.baseAddress());

        BFBackendService bfBackendService = new BFBackendService(Constants.BLOCKFROST_PREVIEW_URL, BF_PREVIEW_KEY);
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(bfBackendService);

        //        31940b356abe616dffbf45d6f0a171d5ce844c6e16338aea31bf6be27b66a2a0#2
        var bootstrappingUtxo = bfBackendService.getUtxoService()
                .getTxOutput("31940b356abe616dffbf45d6f0a171d5ce844c6e16338aea31bf6be27b66a2a0", 2).getValue();

        var settingsNft = Asset.builder().name("0x" + HexUtil.encodeHexString("parameters".getBytes())).value(ONE).build();

        Value settingsValueContract = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(settingsContract.getPolicyId())
                                .assets(List.of(settingsNft))
                                .build()
                ))
                .build();

        var rewardAddress = AddressProvider.getBaseAddress(Credential.fromKey("1c471b31ea0b04c652bd8f76b239aea5f57139bdc5a2b28ab1e69175"),
                Credential.fromKey("fd3a6bfce30d7744ac55e9cf9146d8a2a04ec7fb2ce2ee6986260653"), network);

        DatumParameters settings = new DatumParametersData();
        settings.setMinToStake(BigInteger.valueOf(10_000_000_000L));
        settings.setOwner(admin.getBaseAddress().getPaymentCredentialHash().get());
        settings.setAddressRewards(AddressUtil.toOnchainAddress(rewardAddress));
        settings.setMinAda(BigInteger.valueOf(1_500_000L));

        var datum = settings.toPlutusData();

        var tx = new ScriptTx()
                .collectFrom(bootstrappingUtxo)
                .mintAsset(settingsContract, settingsNft, ConstrPlutusData.of(0))
                .payToContract(settingsAddress.getAddress(), ValueUtil.toAmountList(settingsValueContract), datum)
                .withChangeAddress(admin.baseAddress());

        var transaction = quickTxBuilder
                .compose(tx)
                .withSigner(SignerProviders.signerFrom(admin))
                .collateralPayer(admin.baseAddress())
                .feePayer(admin.baseAddress())
                .completeAndWait();

        log.info("transaction: {}", OBJECT_MAPPER.writeValueAsString(transaction));

    }


    private TransactionEvaluator ogmiosTE() {
        return new OgmiosTransactionEvaluator("http://ryzen:31357");
    }


}
