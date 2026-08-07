package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * {@code RepaymentMode} from {@code lib/fluidtokens/types/general.ak} — the three debt
 * formulas {@code finance.get_remaining_debt} switches on.
 */
public sealed interface RepaymentMode {

    /** Constr 0 — amortization: interest on the remaining principal. */
    record InterestOnRemainingPrincipal(BigInteger maxPossibleRecasts) implements RepaymentMode {
    }

    /** Constr 1 — installment amount = (principal + interest) / installments. */
    record PrincipalAndInterestOnInstallments() implements RepaymentMode {
    }

    /**
     * Constr 2 — no deadline; APY grows linearly along {@code APY = mx + c} where
     * {@code m = apyIncreaseLinearCoefficient / 1_000_000} and {@code c = interestRate / 10_000}.
     */
    record PerpetualLoan(BigInteger apyIncreaseLinearCoefficient,
                         BigInteger maxPossibleRecasts) implements RepaymentMode {
    }
}
