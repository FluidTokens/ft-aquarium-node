package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * The two ledger rejections that can mean our cached protocol parameters are no longer the chain's.
 *
 * <ul>
 *   <li>{@code PPViewHashesDontMatch}: the script-integrity hash was computed from cost models the
 *       ledger no longer holds;</li>
 *   <li>{@code FeeTooSmallUTxO}: the fee was computed from other coefficients.</li>
 * </ul>
 *
 * <p>⚠ This is a cheap GUESS, not a diagnosis. {@code FeeTooSmallUTxO} is also exactly what an unpriced
 * reference script produces (CCL trap 9), and a refresh does not fix that. So a match only brings the
 * {@link EpochProtocolParamsSupplier}'s next fetch forward (rate-guarded there); nothing here retries a
 * submission, alters a fee or changes an outcome.
 */
@Slf4j
public final class ProtocolParamsRejections {

    /** The markers, as the ledger names them in a rejection. */
    static final List<String> MARKERS = List.of("PPViewHashesDontMatch", "FeeTooSmallUTxO");

    private ProtocolParamsRejections() {
    }

    /** True iff {@code response} names one of the {@link #MARKERS}; a {@code null} response is no match. */
    public static boolean paramsRejected(String response) {
        return marker(response).isPresent();
    }

    /**
     * When {@code result} is a rejection naming the parameters and {@code supplier} is the per-epoch one,
     * invalidate its cache. Anything else — a success, a {@code null}, an unrelated rejection, another
     * supplier — is left alone. Never throws: this sits on a submission path whose outcome it must not
     * change.
     */
    public static void refreshIfParamsRejected(ProtocolParamsSupplier supplier, Result<?> result) {
        try {
            if (result == null || result.isSuccessful()
                    || !(supplier instanceof EpochProtocolParamsSupplier epochSupplier)) {
                return;
            }
            marker(result.getResponse()).ifPresent(marker ->
                    epochSupplier.invalidate("the ledger rejected a submission with " + marker));
        } catch (RuntimeException e) {
            log.warn("Could not check a rejection for a protocol parameters refresh: {}", e.toString());
        }
    }

    private static Optional<String> marker(String response) {
        if (response == null) {
            return Optional.empty();
        }
        return MARKERS.stream().filter(response::contains).findFirst();
    }
}
