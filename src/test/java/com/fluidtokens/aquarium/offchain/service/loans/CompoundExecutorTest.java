package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.BlockfrostKey;
import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound loop, driven end to end over the REAL recorded candidate {@code e833a769…} — the same
 * on-chain objects {@link CompoundDryEvalTest} evaluates. The builder is the real one, so these tests
 * exercise scan → build → price → submit rather than a mock of it.
 *
 * <p>The submitter is the only thing faked at the wire, and it records instead of transmitting.
 */
class CompoundExecutorTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";

    /** Every candidate here is ada-principal, so this never actually reaches the oracle. */
    private static PricingService pricingService() {
        return new PricingService(new FluidOracleClient("http://unused.invalid"));
    }
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);

    // ---- the recorded universe ---------------------------------------------------------------

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundExecutorTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("compound candidate fixture missing", e);
        }
    }

    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
    }

    private static Utxo collateral() {
        return Utxo.builder().txHash("c0".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)))).build();
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        return List.copyOf(universe);
    }

    private static List<Utxo> baseUniverse() {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                utxo("config"), utxo("lmConfig"), collateral());
    }

    private static CompoundCandidate ready(long feePerMille) {
        Utxo bondUtxo = utxo("bond");
        LenderBond bond = new LenderBond(bondUtxo.getTxHash(), bondUtxo.getOutputIndex(),
                bondUtxo.getAddress(), LOAN_ID, bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, bond, POOL_ID, utxo("pool"), utxo("poolManager"),
                feePerMille, true, null, "recorded preview candidate");
    }

    private static CompoundCandidate refused() {
        return new CompoundCandidate(LOAN_ID, utxo("escrow"), null, null, null, POOL_ID, null, null,
                0L, true, CompoundExclusion.POOL_NOT_LIVE, "the pool NFT is burned");
    }

    // ---- wiring ------------------------------------------------------------------------------

    private record Wiring(CompoundExecutor executor, List<byte[]> submitted) {
    }

    private static Wiring wiringWithWallet(List<CompoundCandidate> candidates, List<Utxo> walletUtxos) {
        return wiring(candidates, true, -2_000_000L, walletUtxos, "preview");
    }

    private static Wiring wiringOnNetwork(List<CompoundCandidate> candidates, String network) {
        return wiring(candidates, true, -2_000_000L, List.of(wallet(), collateral()), network);
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor) {
        return wiring(candidates, armed, floor, List.of(wallet(), collateral()), "preview");
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor,
                                 List<Utxo> walletUtxos, String networkName) {
        var configuration = new AppConfig.CompoundConfiguration(armed, 60L, BigInteger.valueOf(floor));
        var network = new AppConfig.Network();
        network.setNetworkForTest(networkName);

        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        List<byte[]> submitted = new ArrayList<>();
        CompoundExecutor.TransactionSubmitter submitter = bytes -> {
            submitted.add(bytes);
            return Result.success("deadbeefcafe").withValue("deadbeefcafe");
        };

        List<Utxo> universe = new java.util.ArrayList<>(baseUniverse());
        walletUtxos.stream().filter(candidate -> universe.stream().noneMatch(existing ->
                        existing.getTxHash().equals(candidate.getTxHash())
                                && existing.getOutputIndex() == candidate.getOutputIndex()))
                .forEach(universe::add);
        candidates.stream().map(CompoundCandidate::escrow)
                .filter(candidate -> universe.stream().noneMatch(existing ->
                        existing.getTxHash().equals(candidate.getTxHash())
                                && existing.getOutputIndex() == candidate.getOutputIndex()))
                .forEach(universe::add);
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT,
                new FakeScanner(candidates), new CompoundEconomics(configuration, network, pricingService()),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), submitter);
        return new Wiring(executor, submitted);
    }

    // ---- the tests ---------------------------------------------------------------------------

    /**
     * ⛔ Disarmed is the default, and a READY candidate must still be reported. Silence here is the
     * failure mode an operator cannot distinguish from "nothing to do".
     */
    @Test
    void aReadyCandidateIsNotProcessedWhileDisarmed() {
        Wiring w = wiring(List.of(ready(0L)), false, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(), "a disarmed loop must transmit nothing");
    }

    /**
     * ⛔ A below-cost compound at the safe default: built, priced, and declined on the numbers. Since
     * FAB-167 a zero-rate ADA pool pays the 1 ADA floor, so this is no longer "pays nothing": it is
     * declined because this rig builds ALL-INLINE (no reference scripts), and that final fee — measured
     * 1,270,532 lovelace — exceeds the 1 ADA the pool pays.
     */
    @Test
    void aZeroFeePoolIsDeclinedAtTheDefaultFloor() {
        Wiring w = wiring(List.of(ready(0L)), true, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(),
                "the 1 ADA floor minus an all-inline tx fee above 1 ADA is negative, and the default floor refuses it");
    }

    /**
     * ⛔ AND THE RULED CASE (FAB-167 task 4: unchanged). The same below-cost candidate, with the
     * operator's stated negative bound, IS compounded — a negative margin still admits a loss.
     */
    @Test
    void aStatedNegativeFloorProcessesTheZeroFeeCandidate() {
        Wiring w = wiring(List.of(ready(0L)), true, -2_000_000L);
        w.executor().cycle();
        assertEquals(1, w.submitted().size(),
                "with the loss stated and bounded, the armed loop compounds");
        assertTrue(w.submitted().getFirst().length > 0, "signed bytes reached the submitter");
    }

    @Test
    void aSuccessfulSubmissionStopsTheCandidatePass() {
        assertResponseAttempts(Result.<String>success("ok").withValue("deadbeef"), 1,
                "a successful first submission is terminal; a second intent would violate the pass budget");
    }

    @Test
    void anAmbiguousTransportResultStops() {
        AtomicInteger ambiguousCalls = new AtomicInteger();
        Account signingAccount = org.mockito.Mockito.spy(ACCOUNT);
        CompoundExecutor ambiguous = executor(twoReadyCandidates(), realBuilder(twoReadyCandidates()),
                signingAccount, bytes -> {
            ambiguousCalls.incrementAndGet();
            throw new java.io.IOException("acceptance unknown");
        });
        ambiguous.cycle();
        org.mockito.Mockito.verify(signingAccount, org.mockito.Mockito.times(1))
                .sign(org.mockito.ArgumentMatchers.any(Transaction.class));
        assertEquals(1, ambiguousCalls.get(), "transport ambiguity must end the candidate pass");
    }

    @Test
    void boundedBackendCanonicalProviderRefusalAdvancesToTheDistinctSecondCandidate() throws Exception {
        BoundedWireAttempts attempts = boundedWireAttempts(WireOutcome.PROVIDER_400_THEN_SUCCESS);

        assertEquals(2, attempts.signs());
        assertSubmittedCandidateOrder(attempts, 2);
    }

    @Test
    void boundedBackendHttp408IsTerminalAfterTheFirstCandidate() throws Exception {
        BoundedWireAttempts attempts = boundedWireAttempts(WireOutcome.HTTP_408);

        assertEquals(1, attempts.signs());
        assertSubmittedCandidateOrder(attempts, 1);
    }

    @Test
    void boundedBackendHttp500IsTerminalAfterTheFirstCandidate() throws Exception {
        BoundedWireAttempts attempts = boundedWireAttempts(WireOutcome.HTTP_500);

        assertEquals(1, attempts.signs());
        assertSubmittedCandidateOrder(attempts, 1);
    }

    @Test
    void boundedBackendResetAfterFullBodyIsOneTerminalPostWithoutReplay() throws Exception {
        BoundedWireAttempts attempts = boundedWireAttempts(WireOutcome.RESET_AFTER_BODY);

        assertEquals(1, attempts.signs());
        assertSubmittedCandidateOrder(attempts, 1);
    }

    @Test
    void boundedBackendDecodedSuccessStopsAfterTheFirstCandidate() throws Exception {
        BoundedWireAttempts attempts = boundedWireAttempts(WireOutcome.SUCCESS);

        assertEquals(1, attempts.signs());
        assertSubmittedCandidateOrder(attempts, 1);
    }

    @Test
    void aProviderSubmitRefusalWithALegacyLookingMessageContinuesToTheNextCandidate() {
        assertResponseAttempts(blockfrostRejection(400,
                "transaction submit error ShelleyTxValidationError ShelleyBasedEraConway "
                        + "(ApplyTxError [UtxowFailure (UtxoFailure (BadInputsUTxO "
                        + "(fromList [TxIn deadbeef 0])))])"), 2,
                "a complete decoded Blockfrost provider submit refusal may try the next candidate");
    }

    @Test
    void documentedBlockfrostBadRequestContinuesToSecondCandidate() {
        assertResponseAttempts(Result.<String>error(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":"
                        + "\"Backend did not understand your request.\"}").code(400), 2,
                "the documented Blockfrost submit-refusal envelope");
    }

    @Test
    void opaqueMessageNeverDecidesProviderRefusal() {
        for (String message : List.of(
                "",
                "arbitrary prose",
                "transaction submit error arbitrary ShelleyTxValidationError text ApplyTxError",
                "transaction submit error ShelleyTxValidationError (ApplyTxError [BadInputsUTxO])",
                "{\"constructor\":\"ApplyTxError\",\"details\":[]}")) {
            assertResponseAttempts(blockfrostRejection(400, message), 2,
                    "message is opaque: " + message);
        }
    }

    @Test
    void schemaPermittedExtraMemberStillContinues() {
        assertResponseAttempts(Result.<String>error(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                        + "\"provider_trace\":{\"region\":\"eu\"}}").code(400), 2,
                "the provider schema permits additional members");
    }

    @Test
    void resultAndEnvelopeStatusMustBothBe400() {
        List<Result<String>> responses = List.of(
                Result.<String>error(blockfrostError(400, "Bad Request", "opaque")).code(408),
                Result.<String>error(blockfrostError(408, "Bad Request", "opaque")).code(400),
                Result.<String>error("{\"status_code\":400.0,\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}").code(400),
                Result.<String>error("{\"status_code\":\"400\",\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}").code(400));
        for (Result<String> response : responses) {
            assertResponseAttempts(response, 1, "both statuses must be the integral number 400");
        }
    }

    @Test
    void badRequestNameMustMatchRecognizedSubset() {
        for (String body : List.of(
                "{\"status_code\":400,\"message\":\"opaque\",\"extra\":0}",
                "{\"status_code\":400,\"error\":400,\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"bad request\",\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Conflict\",\"message\":\"opaque\"}")) {
            assertResponseAttempts(Result.<String>error(body).code(400), 1,
                    "error must be the exact recognized provider example");
        }
    }

    @Test
    void allRequiredFieldsAreTyped() {
        for (String body : List.of(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"extra\":0}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":400}",
                "{\"error\":\"Bad Request\",\"message\":\"opaque\",\"extra\":0}",
                "{\"status_code\":null,\"error\":\"Bad Request\",\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":null,\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":null}",
                "[400,\"Bad Request\",\"opaque\"]")) {
            assertResponseAttempts(Result.<String>error(body).code(400), 1,
                    "required fields and root must retain their documented JSON types");
        }
    }

    @Test
    void duplicateOrTrailingJsonIsAmbiguous() {
        for (String body : List.of(
                "{\"status_code\":400,\"status_code\":400,\"error\":\"Bad Request\","
                        + "\"message\":\"opaque\"}",
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                        + "\"extra\":{\"x\":1,\"x\":2}}",
                blockfrostError(400, "Bad Request", "opaque") + "{}",
                blockfrostError(400, "Bad Request", "opaque") + " trailing",
                "\uFEFF" + blockfrostError(400, "Bad Request", "opaque"))) {
            assertResponseAttempts(Result.<String>error(body).code(400), 1,
                    "duplicates, a BOM, and unconsumed input are ambiguous");
        }
    }

    @Test
    void malformedOrUnframed400IsAmbiguous() {
        for (String body : List.of("{not-json", "Bad Request", "<html>Bad Request</html>", "", " \t\r\n")) {
            assertResponseAttempts(Result.<String>error(body).code(400), 1,
                    "only a complete provider JSON envelope is definite");
        }
    }

    @Test
    void decoderBoundsAreClosed() {
        List<String> exactBounds = List.of(
                exactLengthBlockfrostBody(131_072),
                blockfrostError(400, "Bad Request", "x".repeat(65_536)),
                escapedMessageBody(20_000),
                longFieldNameBody(65_536),
                nestedExtraBody(15),
                extraNumberBody("12345678901234567890"),
                memberCountBody(32));
        for (int i = 0; i < exactBounds.size(); i++) {
            assertResponseAttempts(Result.<String>error(exactBounds.get(i)).code(400), 2,
                    "exact body/string/escaped-string/field-name/depth/number/member control " + i);
        }

        List<String> oneOverBounds = List.of(
                exactLengthBlockfrostBody(131_073),
                blockfrostError(400, "Bad Request", "x".repeat(65_537)),
                longFieldNameBody(65_537),
                nestedExtraBody(16),
                extraNumberBody("123456789012345678901"),
                memberCountBody(33));
        for (int i = 0; i < oneOverBounds.size(); i++) {
            assertResponseAttempts(Result.<String>error(oneOverBounds.get(i)).code(400), 1,
                    "one-over body/string/field-name/depth/number/member control " + i);
        }

        assertResponseAttempts(Result.<String>error(" \t\r\n"
                + blockfrostError(400, "Bad Request", "opaque") + "\n\t ").code(400), 2,
                "JSON whitespace around the one complete document is allowed");
    }

    @Test
    void http408StopsAfterExactlyOneSignAndSubmitIntent() {
        assertResponseAttempts(Result.<String>error(blockfrostError(408, "Request Timeout",
                        "request timed out while waiting for the backend")).code(408), 1,
                "HTTP 408 does not establish rejection");
    }

    @Test
    void undecodable4xxStopsAfterExactlyOneSignAndSubmitIntent() {
        assertResponseAttempts(Result.<String>error("upstream proxy refused the request").code(429), 1,
                "an arbitrary 4xx body does not establish rejection");
    }

    @Test
    void nullOther4xxAnd5xxResponsesRemainTerminalAmbiguity() {
        assertResponseAttempts(null, 1, "a null result has unknown acceptance");
        assertResponseAttempts(Result.<String>error(null).code(400), 1,
                "an HTTP 400 without a response body has unknown acceptance");
        for (int status : List.of(418, 425, 429, 500, 503)) {
            assertResponseAttempts(Result.<String>error(
                    blockfrostError(status, "Bad Request", "opaque")).code(status), 1,
                    "HTTP " + status + " is outside the recognized provider refusal boundary");
        }
    }

    @Test
    void raw400CannotMasqueradeAsProviderSubmitRefusal() {
        assertResponseAttempts(Result.<String>error("BadInputsUTxO").code(400), 1,
                "a status and raw ledger tag are not a decoded provider refusal");
    }

    @Test
    void malformedJsonCannotMasqueradeAsProviderSubmitRefusal() {
        assertResponseAttempts(Result.<String>error("{not-json").code(400), 1,
                "malformed JSON is not a decoded provider refusal");
    }

    @Test
    void statusMismatchedPayloadCannotMasqueradeAsProviderSubmitRefusal() {
        assertResponseAttempts(Result.<String>error(blockfrostError(409, "Conflict",
                        "transaction submit error ShelleyTxValidationError ShelleyBasedEraConway "
                                + "(ApplyTxError [BadInputsUTxO])")).code(400), 1,
                "HTTP status and payload status must agree");
    }

    @Test
    void aWouldSubmitResultIsTerminalEvenWithoutATransactionHash() {
        assertResponseAttempts(Result.<String>success("WOULD_SUBMIT").withValue("WOULD_SUBMIT"), 1,
                "WOULD_SUBMIT represents a terminal intent, not a refusal");
    }

    @Test
    void evidenceNativeZeroFeeCandidateBuildsAndSubmitsWithoutAnOracleCall() {
        var token = new com.fluidtokens.aquarium.offchain.model.AssetType("d0".repeat(28), "5553444d");
        CompoundCandidate base = ready(0L);
        BigInteger q = BigInteger.valueOf(176_000_000L);
        Utxo escrow = Utxo.builder().txHash(base.escrow().getTxHash())
                .outputIndex(base.escrow().getOutputIndex()).address(base.escrow().getAddress())
                .inlineDatum(base.escrow().getInlineDatum())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_986_910L)),
                        Amount.asset(token.toUnit(), q))).build();
        LenderBond bond = new LenderBond(base.bond().txHash(), base.bond().outputIndex(), base.bond().address(),
                base.bond().loanId(), base.bond().inlineDatum(),
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), token));
        CompoundCandidate nativeCandidate = new CompoundCandidate(base.loanId(), escrow, base.escrowDatum(), q,
                bond, base.poolId(), base.pool(), base.poolManager(), 0L, false, null,
                "offline evidence-derived native candidate");
        List<Utxo> universe = new ArrayList<>(List.of(escrow, utxo("bond"), base.pool(), base.poolManager(),
                utxo("config"), utxo("lmConfig"), wallet(), collateral()));
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
        var throwingClient = new FluidOracleClient("http://unused.invalid") {
            @Override
            public Optional<com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed> findFeed(
                    com.fluidtokens.aquarium.offchain.model.AssetType asset, long atMillis) {
                throw new AssertionError("native F=0 must not consult the oracle");
            }
        };
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.ZERO);
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var block = new BlockEventListener(null);
        block.getIsSyncing().set(false);
        AtomicInteger submits = new AtomicInteger();
        var executor = new CompoundExecutor(configuration, network, block,
                new FakeAppUtxoService(List.of(wallet(), collateral())), ACCOUNT,
                new FakeScanner(List.of(nativeCandidate)),
                new CompoundEconomics(configuration, network, new PricingService(throwingClient)), builder,
                new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), bytes -> {
                    submits.incrementAndGet();
                    return Result.success("ok").withValue("native-offline");
                });

        executor.cycle();

        assertEquals(1, submits.get(), "recovered E exceeds measured L+T at floor zero");
    }

    @Test
    void productionSigningSubmitsTheExactKernelValidatedBody() {
        AtomicReference<byte[]> validatedBody = new AtomicReference<>();
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        CompoundTransactionBuilder recording = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null) {
            @Override
            public Built buildWithFacts(Request request) {
                Built built = super.buildWithFacts(request);
                validatedBody.set(built.facts().bodyIdentity());
                return built;
            }
        };
        AtomicInteger submits = new AtomicInteger();
        CompoundExecutor executor = executor(List.of(ready(0L)), recording, bytes -> {
            submits.incrementAndGet();
            Transaction signed = Transaction.deserialize(bytes);
            assertArrayEquals(validatedBody.get(), CompoundTransactionBuilder.bodyIdentity(signed),
                    "account.sign(transaction).serialize() may add witnesses but not change the body");
            return Result.success("ok").withValue("deadbeef");
        });

        executor.cycle();

        assertNotNull(validatedBody.get());
        assertEquals(1, submits.get());
    }

    @Test
    void bodyMutationAfterKernelValidationIsRefusedImmediatelyBeforeSigning() {
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        CompoundTransactionBuilder mutating = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null) {
            @Override
            public Built buildWithFacts(Request request) {
                Built built = super.buildWithFacts(request);
                built.transaction().getBody().setFee(built.transaction().getBody().getFee().add(BigInteger.ONE));
                return built;
            }
        };
        Account signingAccount = org.mockito.Mockito.spy(ACCOUNT);
        AtomicInteger submits = new AtomicInteger();
        CompoundExecutor executor = executor(List.of(ready(0L)), mutating, signingAccount, bytes -> {
            submits.incrementAndGet();
            return Result.success("unexpected");
        });

        executor.cycle();

        org.mockito.Mockito.verify(signingAccount, org.mockito.Mockito.never())
                .sign(org.mockito.ArgumentMatchers.any(Transaction.class));
        assertEquals(0, submits.get(), "a post-validation body mutation must not reach submission");
    }

    @Test
    void changedAndExpiredPriceObservationsAreRefusedBeforeSigning() {
        for (CompoundExclusion refusal : List.of(CompoundExclusion.PRICE_OBSERVATION_CHANGED,
                CompoundExclusion.PRICE_OBSERVATION_EXPIRED)) {
            var configuration = new AppConfig.CompoundConfiguration(
                    true, 60L, BigInteger.valueOf(-2_000_000L));
            var network = new AppConfig.Network();
            network.setNetworkForTest("preview");
            CompoundEconomics economics = org.mockito.Mockito.spy(
                    new CompoundEconomics(configuration, network, pricingService()));
            org.mockito.Mockito.doReturn(refusal).when(economics).revalidateObservation(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong());
            Account signingAccount = org.mockito.Mockito.spy(ACCOUNT);
            AtomicInteger submits = new AtomicInteger();
            CompoundExecutor executor = executor(List.of(ready(0L)), realBuilder(), signingAccount,
                    economics, bytes -> {
                        submits.incrementAndGet();
                        return Result.success("unexpected");
                    });

            executor.cycle();

            org.mockito.Mockito.verify(signingAccount, org.mockito.Mockito.never())
                    .sign(org.mockito.ArgumentMatchers.any(Transaction.class));
            assertEquals(0, submits.get(), refusal + " must refuse before signing or submission");
        }
    }

    /** A structurally refused candidate never reaches the builder. */
    @Test
    void aStructurallyRefusedCandidateIsReportedAndNotBuilt() {
        Wiring w = wiring(List.of(refused()), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** An empty scan is not an error and transmits nothing. */
    @Test
    void anEmptyScanIsQuietAndHarmless() {
        Wiring w = wiring(List.of(), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** Syncing is a hard skip: the index is not yet a view of the chain. */
    @Test
    void nothingRunsWhileTheIndexerIsSyncing() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(true);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a syncing node must not submit"); });

        executor.cycle();
        assertEquals(0, scans.get(), "a syncing node must not even scan");
    }

    /** ⛔ FAB-115: compound is a Lending v4 transaction path, so a closed gate stops it too. */
    @Test
    void aClosedLendingConfigGateStopsCompoundBeforeTheScan() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a closed lending gate must not submit"); });

        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum[11]: derived 63b26ff9…, chain 64d9b13f…");
        executor.setLendingConfigGate(gate);

        executor.cycle();
        assertEquals(0, scans.get(), "FAB-115: a closed lending gate must stop compound before the scan");
    }

    @Test
    void anOpenLendingConfigGateLetsCompoundScan() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> com.bloxbean.cardano.client.api.model.Result.<String>success("ok").withValue("deadbeef"));

        // FAB-115 audit finding 3: the control. An OPEN gate must not stop compound — a condition
        // that refused whenever a gate bean merely existed would refuse forever in production.
        executor.setLendingConfigGate(new com.fluidtokens.aquarium.offchain.service.LendingConfigGate());

        executor.cycle();
        assertEquals(1, scans.get(), "an open lending gate must let compound scan");
    }

    @Test
    void aClosedLendingConfigGateIsLoggedOnceAtErrorNotEveryCycle() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a closed lending gate must not submit"); });

        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum[11]: derived 63b26ff9…, chain 64d9b13f…");
        executor.setLendingConfigGate(gate);

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CompoundExecutor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.cycle();
            executor.cycle();
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .filter(e -> e.getFormattedMessage().contains("LENDING_CONFIG_MISMATCH"))
                        .count(),
                "FAB-115: the refusal is said ONCE at ERROR, naming LENDING_CONFIG_MISMATCH — not every cycle");
    }

    /**
     * ⛔ THE PRODUCTION SHAPE, 2026-09-02. The wallet held 9,898 ada and could build nothing, because
     * its ONE utxo also carried a native token — CCL trap 17: a successful transaction's change
     * output disables the builder that made it. The publications that put the reference scripts on
     * chain consumed every ada-only utxo and swept the remainder, token included, into a single
     * change output.
     *
     * <p>The predicate is RIGHT — collateral must be pure ada (trap 16) — so this must SKIP, and the
     * log must say the wallet is the wrong SHAPE rather than empty.
     */
    @Test
    void aWalletWhoseOnlyUtxoCarriesATokenIsSkippedNotSpent() {
        Utxo tokenBearing = Utxo.builder().txHash("2e".repeat(32)).outputIndex(1)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(9_898_050_234L)),
                        Amount.builder().unit("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f620014df1074464c4454")
                                .quantity(BigInteger.valueOf(20_000_000L)).build()))
                .build();

        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tokenBearing));
        w.executor().cycle();

        assertTrue(w.submitted().isEmpty(),
                "9,898 ada on a token-bearing utxo is not spendable as collateral; it must skip");
    }

    /**
     * ⚠ And the LARGEST nominable is taken, not the first. A 1 ada utxo cannot cover the collateral
     * the ledger requires (150% of a ~0.84 ada fee), and nominating it produces a NEGATIVE collateral
     * return — trap 16, a transaction the node cannot even parse. Picking first-in-list would have
     * done exactly that here.
     */
    @Test
    void theLargestAdaOnlyUtxoIsNominatedNotTheFirst() {
        Utxo tiny = Utxo.builder().txHash("01".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_000_000L)))).build();
        Utxo ample = Utxo.builder().txHash("02".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();

        // tiny FIRST in the list — first-in-list selection would take it and fail on collateral.
        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tiny, ample));
        w.executor().cycle();

        assertEquals(1, w.submitted().size(),
                "the ample utxo must be nominated; taking the 1 ada one first would produce a "
                        + "negative collateral return and an unparseable transaction");
    }

    /**
     * ⛔ <b>THE INVERSE OF WHAT THESE THREE TESTS USED TO ASSERT.</b> Until 2026-09-04 this path read
     * {@code loans.submittable-network} and refused to submit unless it named the node's own network;
     * three tests here pinned that gate, its {@code preview} default, and the fact that stating
     * mainnet lifted it.
     *
     * <p>The key was removed on Giovanni's ruling — <i>"a barrier that silently blocks submission
     * even when everything else is armed is a bug, not a safeguard"</i> — so an armed compound node
     * now submits on whichever network it is pointed at. Kept as the regression guard: any
     * re-introduced network check inside {@code submit} turns this red.
     *
     * <p>⚑ <b>And the asymmetry it leaves is RULED, not outstanding.</b> Liquidation has
     * {@code mode == live} plus the market's effective mode plus two floors; compound has
     * {@code compound.enabled} plus one floor. That was put to Giovanni directly on 2026-09-04 and
     * he answered it: compound stays <b>ON/OFF plus a minimum margin</b> — <i>"shadow doesn't make
     * sense, it's a low-risk operation."</i> It advances no capital and moves already-repaid
     * principal into the pool that is owed it.
     *
     * <p>⛔ <b>So do not add a compound mode or a second switch.</b> It was considered and declined.
     * The same ruling removed liquidation's own redundant second boolean
     * ({@code loans.liquidation.enabled}) for the same reason: a gate that restates another gate is
     * cost without safety. <b>The paths differ because the risks differ, deliberately.</b>
     */
    @Test
    void anArmedNodeSubmitsOnWhicheverNetworkItIsPointedAt() {
        for (String networkName : List.of("mainnet", "preprod", "preview")) {
            Wiring w = wiringOnNetwork(List.of(ready(0L)), networkName);
            w.executor().cycle();
            assertEquals(1, w.submitted().size(),
                    "on " + networkName + ": armed, funded and profitable under the stated floor "
                            + "must submit — there is no second network value left to disagree with "
                            + "config.network");
        }
    }

    /**
     * ⚠ The one thing that DOES still hold this path back, asserted beside the removal so the pair
     * reads together: {@code loans.compound.enabled}. It is the whole list.
     */
    @Test
    void anUnarmedNodeStillSubmitsNothingOnAnyNetwork() {
        for (String networkName : List.of("mainnet", "preview")) {
            Wiring w = wiring(List.of(ready(0L)), false, -2_000_000L, List.of(wallet()), networkName);
            w.executor().cycle();
            assertTrue(w.submitted().isEmpty(),
                    "on " + networkName + ": loans.compound.enabled=false is the ONLY policy switch "
                            + "on this path — ruled sufficient because compound is low-risk — so it "
                            + "had better hold");
        }
    }

    /**
     * ⛔ FAB-108 OWN-B: the executor reads the compound reference scripts when it BUILDS, not when it is
     * constructed. The executor bean may exist before {@code LoansReferenceScriptVerifier}'s
     * {@code @PostConstruct} narrows the list, so the narrowing here happens AFTER construction, as it does
     * there. Both coordinates publish a script in the universe the executor resolves against, so a
     * construction-time read would still reference B; only a call-time read references A alone.
     */
    @Test
    void theBuilderIsHandedTheReferenceScriptsAsNarrowedAfterTheExecutorWasBuilt() {
        String txA = "5a".repeat(32);
        String txB = "5b".repeat(32);
        String hashA = "a5".repeat(28);
        String hashB = "b5".repeat(28);
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "referenceScripts",
                txA + "#0," + txB + "#0");
        java.util.concurrent.atomic.AtomicReference<CompoundTransactionBuilder.Request> handed =
                new java.util.concurrent.atomic.AtomicReference<>();
        CompoundTransactionBuilder recording = org.mockito.Mockito.mock(CompoundTransactionBuilder.class);
        org.mockito.Mockito.when(recording.buildWithFacts(
                        org.mockito.ArgumentMatchers.any(CompoundTransactionBuilder.Request.class)))
                .thenAnswer(invocation -> {
                    handed.set(invocation.getArgument(0));
                    throw new IllegalStateException("recorded; the transaction itself is not under test here");
                });
        List<Utxo> universe = new ArrayList<>(universe());
        universe.add(referenceScriptAt(txA, hashA));
        universe.add(referenceScriptAt(txB, hashB));
        CompoundExecutor executor = executorWithBuilder(configuration, recording, universe);

        configuration.narrowReferenceScripts(List.of(
                new com.bloxbean.cardano.client.transaction.spec.TransactionInput(txA, 0)));
        executor.cycle();

        assertNotNull(handed.get(), "the cycle never reached the builder, so this test says nothing");
        assertEquals(java.util.Map.of(hashA, new com.bloxbean.cardano.client.transaction.spec.TransactionInput(txA, 0)),
                handed.get().referenceScripts(),
                "the builder must be handed only the coordinate the boot narrowing kept, not the list the "
                        + "executor saw when it was constructed");
    }

    /** An output at {@code txHash#0} that publishes the reference script {@code scriptHash}. */
    private static Utxo referenceScriptAt(String txHash, String scriptHash) {
        return Utxo.builder().txHash(txHash).outputIndex(0).address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(scriptHash).build();
    }

    private static CompoundTransactionBuilder realBuilder() {
        return realBuilder(List.of());
    }

    private static CompoundTransactionBuilder realBuilder(List<CompoundCandidate> candidates) {
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        candidates.stream().map(CompoundCandidate::escrow)
                .filter(candidate -> universe.stream().noneMatch(existing ->
                        existing.getTxHash().equals(candidate.getTxHash())
                                && existing.getOutputIndex() == candidate.getOutputIndex()))
                .forEach(universe::add);
        return new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
    }

    private static CompoundExecutor executor(List<CompoundCandidate> candidates,
                                             CompoundTransactionBuilder builder,
                                             CompoundExecutor.TransactionSubmitter submitter) {
        return executor(candidates, builder, ACCOUNT, submitter);
    }

    private static CompoundExecutor executor(List<CompoundCandidate> candidates,
                                             CompoundTransactionBuilder builder,
                                             Account account,
                                             CompoundExecutor.TransactionSubmitter submitter) {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        return executor(candidates, builder, account,
                new CompoundEconomics(configuration, network, pricingService()), submitter);
    }

    private static CompoundExecutor executor(List<CompoundCandidate> candidates,
                                             CompoundTransactionBuilder builder,
                                             Account account,
                                             CompoundEconomics economics,
                                             CompoundExecutor.TransactionSubmitter submitter) {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        candidates.stream().map(CompoundCandidate::escrow)
                .filter(candidate -> universe.stream().noneMatch(existing ->
                        existing.getTxHash().equals(candidate.getTxHash())
                                && existing.getOutputIndex() == candidate.getOutputIndex()))
                .forEach(universe::add);
        return new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet(), collateral())), account,
                new FakeScanner(candidates), economics,
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe),
                LoanFixtures.converters(), submitter);
    }

    private static Result<String> blockfrostRejection(int statusCode, String message) {
        return Result.<String>error(blockfrostError(statusCode, "Bad Request", message)).code(statusCode);
    }

    private static String blockfrostError(int statusCode, String error, String message) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "status_code", statusCode,
                    "error", error,
                    "message", message));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("test-only Blockfrost response fixture must serialize", e);
        }
    }

    private record AttemptCounts(long signs, int submits) {
    }

    private enum WireOutcome {
        PROVIDER_400_THEN_SUCCESS,
        HTTP_408,
        HTTP_500,
        RESET_AFTER_BODY,
        SUCCESS
    }

    private record BoundedWireAttempts(long signs, List<byte[]> posts, List<String> requestLines) {
    }

    private static BoundedWireAttempts boundedWireAttempts(WireOutcome outcome) throws Exception {
        List<CompoundCandidate> candidates = twoReadyCandidates();
        Account signingAccount = org.mockito.Mockito.spy(ACCOUNT);
        try (CompoundSubmitServer server = new CompoundSubmitServer(outcome)) {
            BoundedBFBackendService backend = new BoundedBFBackendService(
                    server.baseUrl(), BlockfrostKey.of("test-key"),
                    1, 2, 2, 2, 2, 2, 5);
            try {
                executorWithBackend(candidates, realBuilder(candidates), signingAccount, backend).cycle();
                server.awaitQuiescent();
                long signs = org.mockito.Mockito.mockingDetails(signingAccount).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("sign"))
                        .count();
                return new BoundedWireAttempts(signs, server.posts(), server.requestLines());
            } finally {
                closeBackend(backend);
            }
        }
    }

    private static CompoundExecutor executorWithBackend(List<CompoundCandidate> candidates,
                                                        CompoundTransactionBuilder builder,
                                                        Account account,
                                                        BoundedBFBackendService backend) {
        var configuration = new AppConfig.CompoundConfiguration(
                true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        candidates.stream().map(CompoundCandidate::escrow)
                .filter(candidate -> universe.stream().noneMatch(existing ->
                        existing.getTxHash().equals(candidate.getTxHash())
                                && existing.getOutputIndex() == candidate.getOutputIndex()))
                .forEach(universe::add);
        return new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet(), collateral())), account,
                new FakeScanner(candidates), new CompoundEconomics(configuration, network, pricingService()),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(),
                EvalFixtures.protocolParams(), backend);
    }

    private static void assertSubmittedCandidateOrder(BoundedWireAttempts attempts, int expectedPosts)
            throws Exception {
        assertEquals(expectedPosts, attempts.posts().size(), "exactly one POST is allowed per signed intent");
        assertEquals(expectedPosts, attempts.requestLines().size());
        assertTrue(attempts.requestLines().stream()
                .allMatch("POST /api/v0/tx/submit HTTP/1.1"::equals));

        List<CompoundCandidate> candidates = twoReadyCandidates();
        for (int i = 0; i < expectedPosts; i++) {
            Transaction submitted = Transaction.deserialize(attempts.posts().get(i));
            TransactionInput expected = new TransactionInput(
                    candidates.get(i).escrow().getTxHash(), candidates.get(i).escrow().getOutputIndex());
            TransactionInput other = new TransactionInput(
                    candidates.get(1 - i).escrow().getTxHash(), candidates.get(1 - i).escrow().getOutputIndex());
            assertTrue(submitted.getBody().getInputs().contains(expected),
                    "POST " + i + " must spend candidate " + candidates.get(i).escrowRef());
            assertFalse(submitted.getBody().getInputs().contains(other),
                    "POST " + i + " must not replay or substitute candidate "
                            + candidates.get(1 - i).escrowRef());
        }
        if (expectedPosts == 2) {
            assertFalse(Arrays.equals(attempts.posts().get(0), attempts.posts().get(1)),
                    "the second POST must be the distinct second candidate, not a retry");
        }
    }

    private static void closeBackend(BoundedBFBackendService backend) throws Exception {
        Field baseClientField = BoundedBFBackendService.class.getDeclaredField("baseClient");
        baseClientField.setAccessible(true);
        OkHttpClient client = (OkHttpClient) baseClientField.get(backend);
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
        assertTrue(client.dispatcher().executorService().awaitTermination(3, TimeUnit.SECONDS),
                "the bounded client dispatcher did not terminate");
    }

    private static final class CompoundSubmitServer implements AutoCloseable {
        private static final int QUIET_ACCEPT_MILLIS = 700;
        private static final long FIXTURE_SECONDS = 4;

        private final WireOutcome outcome;
        private final ServerSocket server;
        private final ExecutorService handler = Executors.newSingleThreadExecutor();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<byte[]> posts = new CopyOnWriteArrayList<>();
        private final List<String> requestLines = new CopyOnWriteArrayList<>();

        private CompoundSubmitServer(WireOutcome outcome) throws IOException {
            this.outcome = outcome;
            this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            this.server.setSoTimeout((int) TimeUnit.SECONDS.toMillis(FIXTURE_SECONDS));
            handler.submit(this::serve);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/";
        }

        private List<byte[]> posts() {
            return posts.stream().map(byte[]::clone).toList();
        }

        private List<String> requestLines() {
            return List.copyOf(requestLines);
        }

        private void serve() {
            try {
                while (true) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(FIXTURE_SECONDS));
                        RequestBody request = readRequest(socket.getInputStream());
                        requestLines.add(request.requestLine());
                        posts.add(request.body());
                        server.setSoTimeout(QUIET_ACCEPT_MILLIS);
                        if (outcome == WireOutcome.RESET_AFTER_BODY) {
                            socket.setSoLinger(true, 0);
                            continue;
                        }
                        writeResponse(socket.getOutputStream(), responseFor(posts.size()));
                    }
                }
            } catch (SocketTimeoutException quiet) {
                // The quiet accept window is the negative control for a replay or second candidate.
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                exited.countDown();
            }
        }

        private HttpResponse responseFor(int postNumber) {
            return switch (outcome) {
                case PROVIDER_400_THEN_SUCCESS -> postNumber == 1
                        ? new HttpResponse("400 Bad Request",
                        "{\"status_code\":400,\"error\":\"Bad Request\","
                                + "\"message\":\"Backend did not understand your request.\"}")
                        : new HttpResponse("200 OK", "\"deadbeef\"");
                case HTTP_408 -> new HttpResponse("408 Request Timeout", "{}");
                case HTTP_500 -> new HttpResponse("500 Internal Server Error", "{}");
                case SUCCESS -> new HttpResponse("200 OK", "\"deadbeef\"");
                case RESET_AFTER_BODY -> throw new AssertionError("reset responses have no HTTP envelope");
            };
        }

        private static RequestBody readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int a = -1;
            int b = -1;
            int c = -1;
            for (int next; (next = input.read()) != -1; ) {
                headerBytes.write(next);
                if (headerBytes.size() > 65_536) {
                    throw new IOException("request headers exceed fixture bound");
                }
                if (a == '\r' && b == '\n' && c == '\r' && next == '\n') {
                    break;
                }
                a = b;
                b = c;
                c = next;
            }
            String headers = headerBytes.toString(StandardCharsets.US_ASCII);
            int firstLineEnd = headers.indexOf("\r\n");
            if (firstLineEnd < 0) {
                throw new IOException("request headers did not complete");
            }
            int contentLength = -1;
            for (String line : headers.split("\r\n")) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            if (contentLength < 0) {
                throw new IOException("submission request has no Content-Length");
            }
            byte[] body = input.readNBytes(contentLength);
            if (body.length != contentLength) {
                throw new IOException("submission body ended at " + body.length + " of " + contentLength);
            }
            return new RequestBody(headers.substring(0, firstLineEnd), body);
        }

        private static void writeResponse(OutputStream output, HttpResponse response) throws IOException {
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            output.write(("HTTP/1.1 " + response.status() + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        private void awaitQuiescent() throws Exception {
            assertTrue(exited.await(FIXTURE_SECONDS, TimeUnit.SECONDS),
                    "the joint submission handler did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("joint submission fixture failed", failure.get());
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            handler.shutdownNow();
            assertTrue(handler.awaitTermination(FIXTURE_SECONDS, TimeUnit.SECONDS),
                    "the joint submission fixture executor did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("joint submission fixture failed", failure.get());
            }
        }

        private record RequestBody(String requestLine, byte[] body) {
        }

        private record HttpResponse(String status, String body) {
        }
    }

    private static AttemptCounts responseAttempts(Result<String> response) {
        Account signingAccount = org.mockito.Mockito.spy(ACCOUNT);
        AtomicInteger submits = new AtomicInteger();
        CompoundExecutor executor = executor(twoReadyCandidates(), realBuilder(twoReadyCandidates()),
                signingAccount, bytes -> {
                    submits.incrementAndGet();
                    return response;
                });
        executor.cycle();
        long signs = org.mockito.Mockito.mockingDetails(signingAccount).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sign"))
                .count();
        return new AttemptCounts(signs, submits.get());
    }

    private static void assertResponseAttempts(Result<String> response, int expected, String message) {
        AttemptCounts counts = responseAttempts(response);
        assertEquals(expected, counts.signs(), message + " (signs)");
        assertEquals(expected, counts.submits(), message + " (submits)");
    }

    private static String exactLengthBlockfrostBody(int targetLength) {
        String prefix = "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"\""
                + ",\"a\":\"";
        String middle = "\",\"b\":\"";
        String suffix = "\"}";
        int firstLength = 65_536;
        int secondLength = targetLength - prefix.length() - firstLength - middle.length() - suffix.length();
        if (secondLength < 0 || secondLength > 65_536) {
            throw new IllegalArgumentException("unsupported exact body length " + targetLength);
        }
        String body = prefix + "x".repeat(firstLength) + middle + "y".repeat(secondLength) + suffix;
        assertEquals(targetLength, body.length(), "test fixture must isolate the body-length bound");
        return body;
    }

    private static String nestedExtraBody(int nestedArrays) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                + "\"nested\":" + "[".repeat(nestedArrays) + "0" + "]".repeat(nestedArrays) + "}";
    }

    private static String escapedMessageBody(int decodedLength) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\""
                + "\\u0061".repeat(decodedLength) + "\"}";
    }

    private static String longFieldNameBody(int fieldNameLength) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\",\""
                + "f".repeat(fieldNameLength) + "\":null}";
    }

    private static String extraNumberBody(String numberToken) {
        return "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\","
                + "\"number\":" + numberToken + "}";
    }

    private static String memberCountBody(int memberCount) {
        StringBuilder body = new StringBuilder(
                "{\"status_code\":400,\"error\":\"Bad Request\",\"message\":\"opaque\"");
        for (int i = 3; i < memberCount; i++) {
            body.append(",\"extra").append(i).append("\":null");
        }
        return body.append('}').toString();
    }

    private static List<CompoundCandidate> twoReadyCandidates() {
        return List.of(readyAt("11".repeat(32), 0L), readyAt("12".repeat(32), 0L));
    }

    /** The {@code wiring(...)} executor, with the builder stated and the universe the executor resolves against. */
    private static CompoundExecutor executorWithBuilder(AppConfig.CompoundConfiguration configuration,
                                                        CompoundTransactionBuilder builder, List<Utxo> universe) {
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        CompoundExecutor.TransactionSubmitter submitter = bytes -> {
            throw new AssertionError("nothing is built, so nothing may be submitted");
        };
        return new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet(), collateral())), ACCOUNT,
                new FakeScanner(List.of(ready(0L))), new CompoundEconomics(configuration, network, pricingService()),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), submitter);
    }

    // ---- FAB-144: the most profitable compound first ------------------------------------------

    /** {@link #ready(long)} at its own escrow ref, so two ready candidates can be told apart. */
    private static CompoundCandidate readyAt(String escrowTxHash, long feePerMille) {
        CompoundCandidate base = ready(feePerMille);
        Utxo escrow = utxo("escrow");
        escrow.setTxHash(escrowTxHash);
        return new CompoundCandidate(base.loanId(), escrow, base.escrowDatum(), base.addedLiquidity(),
                base.bond(), base.poolId(), base.pool(), base.poolManager(), base.feePerMille(),
                base.principalIsAda(), base.exclusion(), base.detail());
    }

    /** One disarmed cycle's INFO lines, in the order the executor logged them. */
    private static List<String> infoLinesOfOneCycle(Wiring w) {
        var logger = (Logger) LoggerFactory.getLogger(CompoundExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            w.executor().cycle();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * FAB-144, cycle level. Scanned refused-first, then the 40/1000 escrow, then the 80/1000 one: the
     * cycle must walk the 80/1000 escrow first (2,328,741 lovelace on 29,109,268), then the 40/1000
     * (1,164,370), and the structurally refused one last — still reported, never dropped. Disarmed, so
     * each ready candidate says READY BUT NOT ARMED and nothing is built. (FAB-167: both rates sit above
     * the 1 ADA floor on this escrow, so the per-mille term is what ranks them.)
     */
    @Test
    void aCycleWalksTheMostProfitableCompoundFirstAndARefusedOneLast() {
        CompoundCandidate tenPerMille = readyAt("01".repeat(32), 40L);
        CompoundCandidate twentyPerMille = readyAt("02".repeat(32), 80L);
        String cheap = tenPerMille.escrowRef();
        String rich = twentyPerMille.escrowRef();
        Wiring w = wiring(List.of(refused(), tenPerMille, twentyPerMille), false, 0L);

        List<String> info = infoLinesOfOneCycle(w);

        List<String> walked = info.stream()
                .filter(line -> line.startsWith("compound READY but NOT ARMED")
                        || line.startsWith("compound REFUSED"))
                .toList();
        assertEquals(3, walked.size(), "every candidate is reported, each once: " + info);
        assertTrue(walked.get(0).contains(rich), "the 80/1000 escrow first: " + walked);
        assertTrue(walked.get(1).contains(cheap), "the 40/1000 escrow second: " + walked);
        assertTrue(walked.get(2).startsWith("compound REFUSED"), "the refused one last: " + walked);
        assertEquals(List.of("compound order (most profitable first): " + rich + " gross≈2328741 lovelace, "
                        + cheap + " gross≈1164370 lovelace, " + refused().escrowRef() + " gross unpriced"),
                info.stream().filter(line -> line.startsWith("compound order")).toList(),
                "one INFO line naming the order: " + info);
        assertTrue(w.submitted().isEmpty(), "a disarmed loop transmits nothing");
    }

    @Test
    void nativeRankingUsesLovelaceGrossNotRawTokenQuantity() {
        var token = new com.fluidtokens.aquarium.offchain.model.AssetType("d0".repeat(28), "5553444d");
        CompoundCandidate largerRaw = nativeCandidate("31".repeat(32), token,
                BigInteger.valueOf(1_000_000L), 100L, BigInteger.valueOf(2_000_000L));
        CompoundCandidate smallerRaw = nativeCandidate("32".repeat(32), token,
                BigInteger.valueOf(1_000L), 100L, BigInteger.valueOf(5_000_000L));
        var client = new FluidOracleClient("http://unused.invalid") {
            @Override
            public Optional<com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed> findFeed(
                    com.fluidtokens.aquarium.offchain.model.AssetType asset, long atMillis) {
                return Optional.of(com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed.aggregated(
                        token, BigInteger.ONE, BigInteger.valueOf(1_000L), atMillis - 1, atMillis + 1));
            }
        };
        var configuration = new AppConfig.CompoundConfiguration(false, 60L, BigInteger.ZERO);
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var block = new BlockEventListener(null);
        block.getIsSyncing().set(false);
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        var executor = new CompoundExecutor(configuration, network, block,
                new FakeAppUtxoService(List.of(wallet(), collateral())), ACCOUNT,
                new FakeScanner(List.of(largerRaw, smallerRaw)),
                new CompoundEconomics(configuration, network, new PricingService(client)), realBuilder(),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("disarmed"); });
        var logger = (Logger) LoggerFactory.getLogger(CompoundExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.cycle();
        } finally {
            logger.detachAppender(appender);
        }

        String order = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("compound order")).findFirst().orElseThrow();
        assertTrue(order.indexOf(smallerRaw.escrowRef()) < order.indexOf(largerRaw.escrowRef()), order);
        assertTrue(order.contains("gross≈5000000 lovelace"), order);
        assertTrue(order.contains("gross≈2000100 lovelace"), order);
    }

    private static CompoundCandidate nativeCandidate(String txHash,
                                                      com.fluidtokens.aquarium.offchain.model.AssetType token,
                                                      BigInteger quantity,
                                                      long feePerMille,
                                                      BigInteger escrowCoin) {
        CompoundCandidate base = readyAt(txHash, feePerMille);
        Utxo escrow = Utxo.builder().txHash(txHash).outputIndex(base.escrow().getOutputIndex())
                .address(base.escrow().getAddress()).inlineDatum(base.escrow().getInlineDatum())
                .amount(List.of(Amount.lovelace(escrowCoin), Amount.asset(token.toUnit(), quantity))).build();
        LenderBond bond = new LenderBond(base.bond().txHash(), base.bond().outputIndex(), base.bond().address(),
                base.bond().loanId(), base.bond().inlineDatum(),
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), token));
        return new CompoundCandidate(base.loanId(), escrow, base.escrowDatum(), quantity, bond,
                base.poolId(), base.pool(), base.poolManager(), feePerMille, false, null, "native ranking");
    }

    /** {@link #readyAt} with no {@code addedLiquidity} — ready on paper, but nothing to price. */
    private static CompoundCandidate readyWithoutAmountAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), null, base.bond(),
                base.poolId(), base.pool(), base.poolManager(), base.feePerMille(), base.principalIsAda(),
                null, base.detail());
    }

    /** {@link #readyAt} whose bond names a non-ADA principal, for the legacy ADA-only helper. */
    private static CompoundCandidate tokenPrincipalAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        var token = new com.fluidtokens.aquarium.offchain.model.AssetType("d0".repeat(28), "5553444d");
        LenderBond bond = new LenderBond(base.bond().txHash(), base.bond().outputIndex(), base.bond().address(),
                base.bond().loanId(), base.bond().inlineDatum(),
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), token));
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), base.addedLiquidity(),
                bond, base.poolId(), base.pool(), base.poolManager(), base.feePerMille(), false, null,
                base.detail());
    }

    /**
     * FAB-144: the compound ranking key is {@code expectedFee} — 29,109,268 × 80 / 1000 = 2,328,741.44,
     * truncated to 2,328,741 lovelace — and empty, never thrown, for whatever cannot be priced. FAB-167:
     * it is the validator's fee, so below the crossover every ADA rate ranks at the 1 ADA floor.
     */
    @Test
    void theCompoundRankingKeyIsTheExpectedFeeAndEmptyForWhatCannotBePriced() {
        assertEquals(Optional.of(BigInteger.valueOf(2_328_741L)),
                CompoundExecutor.expectedFeeLovelace(readyAt("02".repeat(32), 80L)));
        assertEquals(Optional.of(BigInteger.valueOf(1_000_000L)), CompoundExecutor.expectedFeeLovelace(ready(0L)),
                "a zero-rate ADA pool is priced at the 1 ADA floor, not at zero and not unpriced");
        assertEquals(Optional.of(BigInteger.valueOf(1_000_000L)),
                CompoundExecutor.expectedFeeLovelace(readyAt("02".repeat(32), 20L)),
                "582,185 per mille on this escrow is below the floor, so 1 ADA");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(refused()),
                "structurally unready");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(readyWithoutAmountAt("03".repeat(32))),
                "no addedLiquidity");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(tokenPrincipalAt("05".repeat(32))),
                "a non-ada principal: expectedFee is not lovelace there");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(null));
    }

    /**
     * FAB-144: descending by expected fee; a tie breaks on {@code escrowRef} ascending; the structurally
     * unready, the amount-less and the non-ada candidates all go after every priced one, by escrow ref.
     * A permutation of the input, never a filter.
     */
    @Test
    void mostProfitableFirstOrdersCompoundsWithTheUnpricedLastByEscrowRef() {
        CompoundCandidate refused = refused();                          // fb48…#2, unpriced
        CompoundCandidate noAmount = readyWithoutAmountAt("03".repeat(32)); // unpriced
        CompoundCandidate nonAda = tokenPrincipalAt("05".repeat(32));     // unpriced
        CompoundCandidate ten = readyAt("01".repeat(32), 40L);            // 1,164,370
        CompoundCandidate twentyB = readyAt("04".repeat(32), 80L);        // 2,328,741
        CompoundCandidate twentyA = readyAt("02".repeat(32), 80L);        // 2,328,741
        List<CompoundCandidate> scanned = List.of(refused, noAmount, nonAda, ten, twentyB, twentyA);

        List<CompoundCandidate> ordered = CompoundExecutor.mostProfitableFirst(scanned,
                CompoundExecutor::expectedFeeLovelace);

        assertEquals(List.of(twentyA.escrowRef(), twentyB.escrowRef(), ten.escrowRef(),
                        noAmount.escrowRef(), nonAda.escrowRef(), refused.escrowRef()),
                ordered.stream().map(CompoundCandidate::escrowRef).toList());
        assertEquals(scanned.size(), ordered.size(), "nothing dropped, nothing duplicated");
        for (CompoundCandidate candidate : scanned) {
            assertEquals(1, ordered.stream().filter(o -> o == candidate).count(), candidate.escrowRef());
        }
        assertEquals(List.of(refused, noAmount, nonAda, ten, twentyB, twentyA), scanned,
                "a NEW list: the scan's own list is left as it was");
    }

    /** A key function that throws makes that candidate unpriced — it is never lost. */
    @Test
    void aThrowingCompoundKeyMakesThatCandidateUnpricedNotLost() {
        CompoundCandidate ten = readyAt("01".repeat(32), 10L);
        CompoundCandidate twenty = readyAt("02".repeat(32), 20L);

        List<CompoundCandidate> ordered = CompoundExecutor.mostProfitableFirst(List.of(twenty, ten),
                candidate -> {
                    if (candidate == twenty) {
                        throw new IllegalStateException("boom");
                    }
                    return CompoundExecutor.expectedFeeLovelace(candidate);
                });

        assertEquals(List.of(ten, twenty), ordered);
    }

    /**
     * {@link #readyAt} carrying an exclusion: structurally UNREADY, yet with everything else a price
     * needs — an {@code addedLiquidity}, a bond and an ada principal — so only readiness can refuse it.
     */
    private static CompoundCandidate unreadyButPriceableAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), base.addedLiquidity(),
                base.bond(), base.poolId(), base.pool(), base.poolManager(), base.feePerMille(),
                base.principalIsAda(), CompoundExclusion.POOL_NOT_LIVE, "the pool NFT is burned");
    }

    /**
     * FAB-144: a structurally unready candidate is unpriced BECAUSE it is unready. {@link #refused()}
     * cannot show that — it has no amount and no bond, so it is empty for those reasons too; this one
     * has both and an ada principal, and would be priced at the 1 ADA floor were readiness not checked.
     */
    @Test
    void aStructurallyUnreadyCompoundIsUnpricedEvenWithAnAmountABondAndAnAdaPrincipal() {
        CompoundCandidate unready = unreadyButPriceableAt("06".repeat(32));
        assertFalse(unready.structurallyReady(), "the fixture is unready");
        assertNotNull(unready.addedLiquidity(), "the fixture carries an amount");
        assertNotNull(unready.bond(), "the fixture carries a bond");
        assertTrue(unready.bond().datum().principalAsset().isAda(), "the fixture's principal is ada");

        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(unready),
                "unready: unpriced, whatever else it carries");
    }

    /**
     * FAB-144: the order is logged only when there was a choice to make. A cycle with exactly ONE
     * candidate logs no {@code compound order} line — and still reports it — while a cycle with TWO
     * logs exactly one.
     */
    @Test
    void theCompoundOrderIsNotLoggedForOneCandidateAndLoggedExactlyOnceForTwo() {
        CompoundCandidate only = readyAt("01".repeat(32), 10L);
        List<String> single = infoLinesOfOneCycle(wiring(List.of(only), false, 0L));
        assertEquals(1, single.stream().filter(line -> line.startsWith("compound READY but NOT ARMED")).count(),
                "the one candidate is still reported: " + single);
        assertEquals(List.of(), single.stream().filter(line -> line.startsWith("compound order")).toList(),
                "one candidate: no choice was made, so no order is logged");

        List<String> pair = infoLinesOfOneCycle(wiring(
                List.of(readyAt("01".repeat(32), 10L), readyAt("02".repeat(32), 20L)), false, 0L));
        assertEquals(1, pair.stream().filter(line -> line.startsWith("compound order")).count(),
                "two candidates: exactly one order line: " + pair);
    }

    // ---- fakes -------------------------------------------------------------------------------

    private static class FakeScanner extends CompoundCandidateScanner {
        private final List<CompoundCandidate> candidates;

        FakeScanner(List<CompoundCandidate> candidates) {
            super(null, REGISTRY, null, null);
            this.candidates = candidates;
        }

        @Override
        public Scan scan() {
            return new Scan(candidates);
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        FakeResolver() {
            super(null, null, null);
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.of(utxo("bond"));
        }

        @Override
        public Optional<Utxo> resolveConfigUtxo() {
            return Optional.of(utxo("config"));
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(utxo("lmConfig"));
        }
    }

    private static final class FakeAppUtxoService extends AppUtxoService {
        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
        }
    }
}
