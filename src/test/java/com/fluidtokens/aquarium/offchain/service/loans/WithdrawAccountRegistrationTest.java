package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.fluidtokens.aquarium.offchain.AcquariumOffchainApp;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.controller.LiquidationReadinessController;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WithdrawAccountRegistrationTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    @Configuration
    @Import(WithdrawAccountRegistration.class)
    static class RegistrationContext {
    }

    @Test
    void pathAsksForNewestEventOnly() {
        assertEquals("/accounts/stake17abc/registrations?order=desc&count=1",
                WithdrawAccountRegistration.registrationsPath("stake17abc"));
    }

    @Test
    void classifyAcceptsOnlyACurrentRegisteredEvent() {
        assertEquals(WithdrawAccountRegistration.Status.REGISTERED,
                WithdrawAccountRegistration.classify(200, "[{\"action\":\"registered\"}]"));
        assertEquals(WithdrawAccountRegistration.Status.NOT_REGISTERED,
                WithdrawAccountRegistration.classify(200, "[]"));
        assertEquals(WithdrawAccountRegistration.Status.NOT_REGISTERED,
                WithdrawAccountRegistration.classify(200, "[{\"action\":\"deregistered\"}]"));
        assertEquals(WithdrawAccountRegistration.Status.NOT_REGISTERED,
                WithdrawAccountRegistration.classify(404, "anything"));
        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN,
                WithdrawAccountRegistration.classify(403, "[]"));
        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN,
                WithdrawAccountRegistration.classify(500, "[]"));
        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN,
                WithdrawAccountRegistration.classify(200, "{}"));
        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN,
                WithdrawAccountRegistration.classify(200, "[{}]"));
        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN,
                WithdrawAccountRegistration.classify(200, "not json"));
    }

    @Test
    void fetcherExceptionIsUnknownAndNeverRegistered() {
        var service = service(path -> {
            throw new IllegalStateException("outer", new java.io.IOException("socket closed"));
        }, () -> 0L);

        var check = service.check("loan", reward(REGISTRY.getLoanPolicyId()));

        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN, check.status());
        assertFalse(check.confirmed());
        assertTrue(check.detail().contains("IllegalStateException: outer"), check.detail());
        assertTrue(check.detail().contains("IOException: socket closed"), check.detail());
        assertTrue(check.detail().contains("not read as registered"), check.detail());
    }

    @Test
    void registeredCacheUsesTheLongTtl() {
        AtomicLong now = new AtomicLong(10L);
        AtomicInteger calls = new AtomicInteger();
        var service = service(path -> {
            calls.incrementAndGet();
            return new WithdrawAccountRegistration.Fetched(200, "[{\"action\":\"registered\"}]");
        }, now::get);
        String stake = reward(REGISTRY.getLoanPolicyId());

        service.check("loan", stake);
        now.addAndGet(WithdrawAccountRegistration.REGISTERED_TTL_MILLIS - 1);
        service.check("loan", stake);
        assertEquals(1, calls.get());
        now.incrementAndGet();
        service.check("loan", stake);
        assertEquals(2, calls.get());
    }

    @Test
    void notRegisteredAndUnknownCachesUseTheShortTtl() {
        for (int status : List.of(404, 500)) {
            AtomicLong now = new AtomicLong(20L);
            AtomicInteger calls = new AtomicInteger();
            var service = service(path -> {
                calls.incrementAndGet();
                return new WithdrawAccountRegistration.Fetched(status, "[]");
            }, now::get);
            String stake = reward(REGISTRY.getLoanClaimActionScriptHash());

            var fresh = service.check("claim", stake);
            now.addAndGet(WithdrawAccountRegistration.UNCONFIRMED_TTL_MILLIS - 1);
            var cached = service.check("claim", stake);
            assertEquals(1, calls.get(), "status " + status + " was re-fetched before its TTL");
            // The cache hit must hand back the answer it stored — never promote it to REGISTERED.
            var expected = status == 404 ? WithdrawAccountRegistration.Status.NOT_REGISTERED
                    : WithdrawAccountRegistration.Status.UNKNOWN;
            assertEquals(expected, fresh.status(), "fresh answer for status " + status);
            assertEquals(fresh.status(), cached.status(), "cached answer for status " + status);
            assertFalse(cached.confirmed(), "a cached " + status + " was read as registered");
            assertEquals(fresh.detail(), cached.detail(), "cached detail for status " + status);
            now.incrementAndGet();
            service.check("claim", stake);
            assertEquals(2, calls.get(), "status " + status + " was not re-fetched at its TTL");
        }
    }

    @Test
    void routesUseTheRegistryGettersAndSkipANullConvertHash() {
        var registered = service(path -> registered(), () -> 0L);
        assertHashes(registered.routeChecks(WithdrawAccountRegistration.Route.PLAIN),
                REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                REGISTRY.getLenderManagerWithdrawScriptHash(), REGISTRY.getLmLiquidateActionScriptHash());
        assertHashes(registered.routeChecks(WithdrawAccountRegistration.Route.PAY_IN_ADVANCE),
                REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                REGISTRY.getLenderManagerWithdrawScriptHash(),
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        assertHashes(registered.routeChecks(WithdrawAccountRegistration.Route.CONVERT),
                REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                REGISTRY.getLenderManagerWithdrawScriptHash(),
                REGISTRY.getLmLiquidateAndConvertActionScriptHash());

        LoansContractRegistry withoutConvert = mock(LoansContractRegistry.class);
        when(withoutConvert.getLoanPolicyId()).thenReturn(REGISTRY.getLoanPolicyId());
        when(withoutConvert.getLoanClaimActionScriptHash()).thenReturn(REGISTRY.getLoanClaimActionScriptHash());
        when(withoutConvert.getLenderManagerWithdrawScriptHash())
                .thenReturn(REGISTRY.getLenderManagerWithdrawScriptHash());
        when(withoutConvert.getLmLiquidateAndConvertActionScriptHash()).thenReturn(null);
        var service = new WithdrawAccountRegistration(withoutConvert, Networks.preview(),
                path -> registered(), () -> 0L);
        assertHashes(service.routeChecks(WithdrawAccountRegistration.Route.CONVERT),
                REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                REGISTRY.getLenderManagerWithdrawScriptHash());
    }

    @Test
    void stakeAddressUsesTheScriptRewardHeaderForEachNetwork() {
        String hash = REGISTRY.getLoanPolicyId();
        byte mainnetHeader = new Address(WithdrawAccountRegistration.stakeAddress(hash, Networks.mainnet()))
                .getBytes()[0];
        byte previewHeader = new Address(WithdrawAccountRegistration.stakeAddress(hash, Networks.preview()))
                .getBytes()[0];
        assertEquals(0xf1, mainnetHeader & 0xff);
        assertEquals(0xf0, previewHeader & 0xff);
    }

    @Test
    void transactionChecksReportsExactlyTheOneUnconfirmedWithdrawal() {
        String unconfirmed = REGISTRY.getLmLiquidateActionScriptHash();
        var service = service(path -> path.contains(reward(unconfirmed))
                ? new WithdrawAccountRegistration.Fetched(404, "[]") : registered(), () -> 0L);
        Transaction transaction = transaction(REGISTRY.getLoanPolicyId(),
                REGISTRY.getLoanClaimActionScriptHash(), unconfirmed,
                REGISTRY.getLenderManagerWithdrawScriptHash());

        List<WithdrawAccountRegistration.Check> failed = service.transactionChecks(transaction).stream()
                .filter(check -> !check.confirmed()).toList();

        assertEquals(1, failed.size());
        assertEquals(unconfirmed, failed.getFirst().scriptHash());
    }

    @Test
    void productionWiringUsesBackendUrlAndHeaderAndKeepsTheKeySecret() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> projectId = new AtomicReference<>();
        String registeredStake = reward(REGISTRY.getLoanPolicyId());
        server.createContext("/api/v0/accounts", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getQuery());
            projectId.set(exchange.getRequestHeaders().getFirst("project_id"));
            boolean success = exchange.getRequestURI().getPath().contains(registeredStake);
            byte[] body = (success ? "[{\"action\":\"registered\"}]" : "backend fault")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(success ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Logger logger = (Logger) LoggerFactory.getLogger(WithdrawAccountRegistration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BFBackendService backend = new BFBackendService(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0/",
                    "SECRET-KEY-MARKER");
            new ApplicationContextRunner()
                    .withUserConfiguration(RegistrationContext.class)
                    .withBean(LoansContractRegistry.class, () -> REGISTRY)
                    .withBean(AppConfig.Network.class, () -> network("preview"))
                    .withBean(BFBackendService.class, () -> backend)
                    .run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        WithdrawAccountRegistration service =
                                context.getBean(WithdrawAccountRegistration.class);
                        var confirmed = service.check("loan", registeredStake);
                        assertEquals(WithdrawAccountRegistration.Status.REGISTERED, confirmed.status());
                        assertEquals("/api/v0/accounts/" + registeredStake + "/registrations", path.get());
                        assertEquals("order=desc&count=1", query.get());
                        assertEquals("SECRET-KEY-MARKER", projectId.get());

                        var unknown = service.check("claim", reward(REGISTRY.getLoanClaimActionScriptHash()));
                        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN, unknown.status());
                        assertFalse(unknown.detail().contains("SECRET-KEY-MARKER"), unknown.detail());
                    });
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("SECRET-KEY-MARKER")));
        } finally {
            logger.detachAppender(appender);
            server.stop(0);
        }
    }

    /**
     * The production fetcher's EXCEPTION path, not its status path: a real {@code BFBackendService}
     * pointed at a port nothing listens on, so the HTTP client itself throws. That message is built
     * by our catch, which is exactly where a project id could be concatenated in by accident.
     */
    @Test
    void productionWiringConnectionFailureIsUnknownAndKeepsTheKeySecret() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            BFBackendService backend = new BFBackendService(
                    "http://127.0.0.1:" + closedPort + "/api/v0/", "SECRET-KEY-MARKER");
            new ApplicationContextRunner()
                    .withUserConfiguration(RegistrationContext.class)
                    .withBean(LoansContractRegistry.class, () -> REGISTRY)
                    .withBean(AppConfig.Network.class, () -> network("preview"))
                    .withBean(BFBackendService.class, () -> backend)
                    .run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        WithdrawAccountRegistration service =
                                context.getBean(WithdrawAccountRegistration.class);
                        var unknown = service.check("loan", reward(REGISTRY.getLoanPolicyId()));
                        assertEquals(WithdrawAccountRegistration.Status.UNKNOWN, unknown.status());
                        assertFalse(unknown.confirmed());
                        assertTrue(unknown.detail().contains("not read as registered"), unknown.detail());
                        assertFalse(unknown.detail().contains("SECRET-KEY-MARKER"), unknown.detail());
                    });
            assertTrue(appender.list.stream()
                    .noneMatch(event -> event.getFormattedMessage().contains("SECRET-KEY-MARKER")
                            || (event.getThrowableProxy() != null
                                    && String.valueOf(event.getThrowableProxy().getMessage())
                                            .contains("SECRET-KEY-MARKER"))));
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void serviceAndRequiredSettersArePinnedForContainerWiring() throws Exception {
        assertTrue(WithdrawAccountRegistration.class.isAnnotationPresent(Service.class));
        assertTrue(WithdrawAccountRegistration.class.getPackageName()
                .startsWith(AcquariumOffchainApp.class.getPackageName()));
        for (Class<?> type : List.of(LiquidationExecutor.class, LiquidationReadinessController.class)) {
            var setter = type.getMethod("setWithdrawAccountRegistration",
                    WithdrawAccountRegistration.class);
            Autowired annotation = setter.getAnnotation(Autowired.class);
            assertNotNull(annotation, type.getSimpleName() + " setter must be @Autowired");
            assertTrue(annotation.required(), type.getSimpleName() + " setter must be required");
        }
    }

    private static WithdrawAccountRegistration service(
            WithdrawAccountRegistration.RegistrationsFetcher fetcher,
            java.util.function.LongSupplier clock) {
        return new WithdrawAccountRegistration(REGISTRY, Networks.preview(), fetcher, clock);
    }

    private static WithdrawAccountRegistration.Fetched registered() {
        return new WithdrawAccountRegistration.Fetched(200, "[{\"action\":\"registered\"}]");
    }

    private static String reward(String hash) {
        return WithdrawAccountRegistration.stakeAddress(hash, Networks.preview());
    }

    private static void assertHashes(List<WithdrawAccountRegistration.Check> checks, String... hashes) {
        assertEquals(java.util.Arrays.stream(hashes).filter(java.util.Objects::nonNull).toList(), checks.stream()
                .map(WithdrawAccountRegistration.Check::scriptHash).toList());
        assertTrue(checks.stream().allMatch(WithdrawAccountRegistration.Check::confirmed));
    }

    private static Transaction transaction(String... hashes) {
        List<Withdrawal> withdrawals = new ArrayList<>();
        for (String hash : hashes) {
            withdrawals.add(Withdrawal.builder().rewardAddress(reward(hash)).coin(BigInteger.ZERO).build());
        }
        return Transaction.builder().body(TransactionBody.builder().withdrawals(withdrawals).build()).build();
    }

    private static AppConfig.Network network(String name) {
        AppConfig.Network network = new AppConfig.Network();
        network.setNetworkForTest(name);
        return network;
    }
}
