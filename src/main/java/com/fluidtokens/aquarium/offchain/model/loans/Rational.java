package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;
import java.util.Optional;

/**
 * A faithful port of {@code aiken/math/rational} (stdlib v2.1.0), the arithmetic the loan
 * validators run on.
 * <p>
 * <b>Deliberately not reduced.</b> Aiken's {@code add}/{@code sub}/{@code mul} cross-multiply and
 * never call {@code reduce}, and Aiken compares records <em>structurally</em> — so {@code 0/5} is
 * not equal to {@code 0/1}. {@code can_liquidate} leans on exactly that
 * ({@code collateralInLovelace == rational.zero}), so reducing here would silently change which
 * loans the engine calls liquidatable. Keep the representation identical, not merely equivalent.
 * <p>
 * The invariant from {@link #of} and {@link #reciprocal} is that the denominator is always
 * positive; {@link #compare} depends on it.
 */
public record Rational(BigInteger numerator, BigInteger denominator) {

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    /** {@code rational.new} — empty when the denominator is zero, sign normalised onto the numerator. */
    public static Optional<Rational> of(BigInteger numerator, BigInteger denominator) {
        int sign = denominator.signum();
        if (sign == 0) {
            return Optional.empty();
        }
        if (sign < 0) {
            return Optional.of(new Rational(numerator.negate(), denominator.negate()));
        }
        return Optional.of(new Rational(numerator, denominator));
    }

    public static Optional<Rational> of(long numerator, long denominator) {
        return of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /** {@code rational.new} where a zero denominator is a contract-level failure ({@code expect Some}). */
    public static Rational required(BigInteger numerator, BigInteger denominator) {
        return of(numerator, denominator).orElseThrow(
                () -> new ArithmeticException("rational.new with a zero denominator"));
    }

    public static Rational required(long numerator, long denominator) {
        return required(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Rational fromInt(BigInteger value) {
        return new Rational(value, BigInteger.ONE);
    }

    public static Rational fromInt(long value) {
        return fromInt(BigInteger.valueOf(value));
    }

    public Rational add(Rational other) {
        return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Rational sub(Rational other) {
        return new Rational(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Rational mul(Rational other) {
        return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    /** {@code rational.reciprocal} — empty for zero, which is what makes division by zero fail. */
    public Optional<Rational> reciprocal() {
        int sign = numerator.signum();
        if (sign == 0) {
            return Optional.empty();
        }
        if (sign < 0) {
            return Optional.of(new Rational(denominator.negate(), numerator.negate()));
        }
        return Optional.of(new Rational(denominator, numerator));
    }

    public Optional<Rational> div(Rational other) {
        return other.reciprocal().map(this::mul);
    }

    /** Division where a zero divisor is a contract-level failure ({@code rational_safe_div}). */
    public Rational divRequired(Rational other) {
        return div(other).orElseThrow(() -> new ArithmeticException("rational division by zero"));
    }

    /**
     * {@code rational.ceil}. Aiken uses the <em>truncating</em> builtins here
     * ({@code quotient_integer}/{@code remainder_integer}), which {@link BigInteger#divide} and
     * {@link BigInteger#remainder} match exactly.
     */
    public BigInteger ceil() {
        BigInteger quotient = numerator.divide(denominator);
        return numerator.remainder(denominator).signum() > 0 ? quotient.add(BigInteger.ONE) : quotient;
    }

    /**
     * {@code rational.floor}. Aiken uses {@code /}, which is the <em>flooring</em>
     * {@code divide_integer} builtin — not the truncating division {@link #ceil()} uses. The
     * asymmetry is in the stdlib, and it matters: equity goes negative.
     */
    public BigInteger floor() {
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        BigInteger quotient = quotientAndRemainder[0];
        BigInteger remainder = quotientAndRemainder[1];
        if (remainder.signum() != 0 && remainder.signum() != denominator.signum()) {
            return quotient.subtract(BigInteger.ONE);
        }
        return quotient;
    }

    /** {@code rational.compare}. Valid because denominators are kept positive. */
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    public boolean isZeroValued() {
        return numerator.signum() == 0;
    }

    /** {@code aiken/math.pow} — zero for negative exponents, as the contract's does. */
    public static BigInteger pow(BigInteger self, int exponent) {
        if (exponent < 0) {
            return BigInteger.ZERO;
        }
        return self.pow(exponent);
    }

    /**
     * {@code finance.rational_pow}. Note it recurses down to {@code exponent == 1} and so never
     * terminates for {@code exponent < 1} — a loan with zero installments would hang the
     * validator, and we surface that as an exception rather than reproducing the hang.
     */
    public Rational pow(int exponent) {
        if (exponent < 1) {
            throw new ArithmeticException(
                    "rational_pow with exponent " + exponent + " does not terminate on chain");
        }
        Rational result = this;
        for (int i = 1; i < exponent; i++) {
            result = result.mul(this);
        }
        return result;
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
