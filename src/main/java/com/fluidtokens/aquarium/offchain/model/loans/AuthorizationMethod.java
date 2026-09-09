package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * {@code AuthorizationMethod} from {@code lib/fluidtokens/types/general.ak}.
 * <p>
 * Constructor indices are load-bearing and are asserted against the blueprint
 * definition {@code fluidtokens/types/general/AuthorizationMethod}.
 */
public sealed interface AuthorizationMethod {

    /** Constr 0 — authorised by a signature from the key whose hash this is. */
    record CardanoSignature(String hash) implements AuthorizationMethod {
    }

    /** Constr 1 — authorised by the spend of a script with this hash. */
    record CardanoSpendScript(String hash) implements AuthorizationMethod {
    }

    /** Constr 2 — authorised by a withdrawal from a script with this hash. */
    record CardanoWithdrawScript(String hash) implements AuthorizationMethod {
    }

    /** Constr 3 — authorised by a mint from a script with this hash. */
    record CardanoMintScript(String hash) implements AuthorizationMethod {
    }
}
