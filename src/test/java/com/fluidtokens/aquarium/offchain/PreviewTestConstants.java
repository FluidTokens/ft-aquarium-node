package com.fluidtokens.aquarium.offchain;

public interface PreviewTestConstants {

    String MNEMONIC_ADMIN = System.getenv("WALLET_MNEMONIC_ADMIN");
    String MNEMONIC_USER = System.getenv("WALLET_MNEMONIC_USER");

    String BF_PREVIEW_KEY = System.getenv("BF_PREVIEW_KEY");

    String PARAMETERS_REF_INPUT_TX_ID = "187a0d77dc8693a8d332b7f809f623124becb49a73410b6fd6bfc1416f2d2eb8";
    int PARAMETERS_REF_INPUT_OUTPUT_INDEX = 0;

    String TEST_FT_TOKEN_POLICY_ID = "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    String TEST_FT_TOKEN_ASSET_NAME = "0014df1074464c4454";

}
