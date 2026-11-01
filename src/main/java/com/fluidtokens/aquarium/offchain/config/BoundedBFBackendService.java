package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.AccountService;
import com.bloxbean.cardano.client.backend.api.AddressService;
import com.bloxbean.cardano.client.backend.api.AssetService;
import com.bloxbean.cardano.client.backend.api.BlockService;
import com.bloxbean.cardano.client.backend.api.EpochService;
import com.bloxbean.cardano.client.backend.api.MetadataService;
import com.bloxbean.cardano.client.backend.api.NetworkInfoService;
import com.bloxbean.cardano.client.backend.api.PoolService;
import com.bloxbean.cardano.client.backend.api.ScriptService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFAccountService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFAddressService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFAssetService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBlockService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFEpochService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFMetadataService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFNetworkService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFPoolService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFScriptService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFTransactionService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFUtxoService;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The one Blockfrost backend used by the node. Every CCL service is cached over the same bounded
 * Retrofit transport, including {@link BFUtxoService#getTxOutput(String, int)}'s otherwise hidden
 * transaction client.
 */
public final class BoundedBFBackendService extends BFBackendService {

    public interface PassDeadline {
        boolean isExpired();

        long remainingNanos();
    }

    private enum Stage { DISCOVERY, EVALUATION, SUBMISSION }

    private final LongSupplier nanoTime;
    private final long discoveryNanos;
    private final long evaluationNanos;
    private final long submissionNanos;
    private final long passNanos;
    private final ThreadLocal<Deadline> activePass = new ThreadLocal<>();
    private final OkHttpClient baseClient;
    private final OkHttpClient submissionClient;
    private final Retrofit retrofit;

    private final AssetService assetService;
    private final BlockService blockService;
    private final NetworkInfoService networkInfoService;
    private final PoolService poolService;
    private final TransactionService transactionService;
    private final UtxoService utxoService;
    private final AddressService addressService;
    private final AccountService accountService;
    private final EpochService epochService;
    private final MetadataService metadataService;
    private final ScriptService scriptService;

    public BoundedBFBackendService(
            String baseUrl,
            BlockfrostKey blockfrostKey,
            long connectSeconds,
            long readSeconds,
            long writeSeconds,
            long discoverySeconds,
            long evaluationSeconds,
            long submissionSeconds,
            long passSeconds) {
        this(baseUrl, blockfrostKey, connectSeconds, readSeconds, writeSeconds,
                discoverySeconds, evaluationSeconds, submissionSeconds, passSeconds,
                System::nanoTime, call -> EventListener.NONE);
    }

    BoundedBFBackendService(
            String baseUrl,
            BlockfrostKey blockfrostKey,
            long connectSeconds,
            long readSeconds,
            long writeSeconds,
            long discoverySeconds,
            long evaluationSeconds,
            long submissionSeconds,
            long passSeconds,
            LongSupplier nanoTime) {
        this(baseUrl, blockfrostKey, connectSeconds, readSeconds, writeSeconds,
                discoverySeconds, evaluationSeconds, submissionSeconds, passSeconds,
                nanoTime, call -> EventListener.NONE);
    }

    BoundedBFBackendService(
            String baseUrl,
            BlockfrostKey blockfrostKey,
            long connectSeconds,
            long readSeconds,
            long writeSeconds,
            long discoverySeconds,
            long evaluationSeconds,
            long submissionSeconds,
            long passSeconds,
            LongSupplier nanoTime,
            EventListener.Factory eventListenerFactory) {
        super(baseUrl, Objects.requireNonNull(blockfrostKey, "blockfrostKey").value());
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");

        validatedNanos("blockfrost.timeout.connect-seconds", connectSeconds);
        int connectMillis = validatedMillis("blockfrost.timeout.connect-seconds", connectSeconds);
        validatedNanos("blockfrost.timeout.read-seconds", readSeconds);
        int readMillis = validatedMillis("blockfrost.timeout.read-seconds", readSeconds);
        validatedNanos("blockfrost.timeout.write-seconds", writeSeconds);
        int writeMillis = validatedMillis("blockfrost.timeout.write-seconds", writeSeconds);
        this.discoveryNanos = validatedNanos("blockfrost.timeout.discovery-call-seconds", discoverySeconds);
        validatedMillis("blockfrost.timeout.discovery-call-seconds", discoverySeconds);
        this.evaluationNanos = validatedNanos("blockfrost.timeout.evaluation-call-seconds", evaluationSeconds);
        validatedMillis("blockfrost.timeout.evaluation-call-seconds", evaluationSeconds);
        this.submissionNanos = validatedNanos("blockfrost.timeout.submission-call-seconds", submissionSeconds);
        validatedMillis("blockfrost.timeout.submission-call-seconds", submissionSeconds);
        this.passNanos = validatedNanos("wallet-coordinator.pass-timeout-seconds", passSeconds);
        validatedMillis("wallet-coordinator.pass-timeout-seconds", passSeconds);

        baseClient = new OkHttpClient.Builder()
                .connectTimeout(connectMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(writeMillis, TimeUnit.MILLISECONDS)
                .eventListenerFactory(Objects.requireNonNull(eventListenerFactory, "eventListenerFactory"))
                .build();
        submissionClient = baseClient.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        retrofit = super.getRetrofit().newBuilder()
                .callFactory(new ClassifyingCallFactory())
                .build();

        assetService = new BFAssetService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        blockService = new BFBlockService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        networkInfoService = new BFNetworkService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        poolService = new BFPoolService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        transactionService = new BFTransactionService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        addressService = new BFAddressService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        utxoService = new BFUtxoService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }

            @Override
            public Result<Utxo> getTxOutput(String txHash, int outputIndex) throws ApiException {
                return transactionService.getTransactionOutput(txHash, outputIndex);
            }
        };
        accountService = new BFAccountService(baseUrl, getProjectId(), addressService) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        epochService = new BFEpochService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        metadataService = new BFMetadataService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
        scriptService = new BFScriptService(baseUrl, getProjectId()) {
            @Override protected Retrofit getRetrofit() { return retrofit; }
        };
    }

    public void runWithinPassDeadline(Consumer<PassDeadline> body) {
        Objects.requireNonNull(body, "body");
        if (activePass.get() != null) {
            throw new IllegalStateException("nested Blockfrost pass deadlines are not supported");
        }
        Deadline deadline = new Deadline(nanoTime.getAsLong(), passNanos);
        activePass.set(deadline);
        try {
            body.accept(deadline);
        } finally {
            activePass.remove();
        }
    }

    @Override public AssetService getAssetService() { return assetService; }
    @Override public BlockService getBlockService() { return blockService; }
    @Override public NetworkInfoService getNetworkInfoService() { return networkInfoService; }
    @Override public PoolService getPoolService() { return poolService; }
    @Override public TransactionService getTransactionService() { return transactionService; }
    @Override public UtxoService getUtxoService() { return utxoService; }
    @Override public AddressService getAddressService() { return addressService; }
    @Override public AccountService getAccountService() { return accountService; }
    @Override public EpochService getEpochService() { return epochService; }
    @Override public MetadataService getMetadataService() { return metadataService; }
    @Override public ScriptService getScriptService() { return scriptService; }

    OkHttpClient baseClient() { return baseClient; }
    OkHttpClient submissionClient() { return submissionClient; }

    private static int validatedMillis(String property, long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        long millis;
        try {
            millis = Math.multiplyExact(seconds, 1_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(property + " is too large in milliseconds", overflow);
        }
        if (millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(property + " exceeds the supported millisecond range");
        }
        return (int) millis;
    }

    private static long validatedNanos(String property, long seconds) {
        try {
            return Math.multiplyExact(seconds, 1_000_000_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(property + " is too large in nanoseconds", overflow);
        }
    }

    private Stage stage(Request request) {
        String path = request.url().encodedPath();
        if (path.endsWith("/utils/txs/evaluate")) {
            return Stage.EVALUATION;
        }
        if (path.endsWith("/tx/submit")) {
            return Stage.SUBMISSION;
        }
        return Stage.DISCOVERY;
    }

    private long timeoutFor(Stage stage) {
        long stageNanos = switch (stage) {
            case DISCOVERY -> discoveryNanos;
            case EVALUATION -> evaluationNanos;
            case SUBMISSION -> submissionNanos;
        };
        Deadline deadline = activePass.get();
        if (deadline == null) {
            return stageNanos;
        }
        long remaining = deadline.remainingNanos();
        if (remaining == 0) {
            throw new IllegalStateException("Blockfrost pass deadline expired before request start");
        }
        return Math.min(stageNanos, remaining);
    }

    private final class ClassifyingCallFactory implements Call.Factory {
        @Override
        public Call newCall(Request request) {
            Stage stage = stage(request);
            long timeoutNanos = timeoutFor(stage);
            Call call = (stage == Stage.SUBMISSION ? submissionClient : baseClient).newCall(request);
            call.timeout().timeout(timeoutNanos, TimeUnit.NANOSECONDS);
            return call;
        }
    }

    private final class Deadline implements PassDeadline {
        private final long deadlineNanos;

        private Deadline(long startedNanos, long durationNanos) {
            // nanoTime is a wrapping counter. Modular addition/subtraction is overflow-safe while
            // durations remain below half its range (startup validation keeps ours far below it).
            this.deadlineNanos = startedNanos + durationNanos;
        }

        @Override
        public boolean isExpired() {
            return remainingNanos() == 0;
        }

        @Override
        public long remainingNanos() {
            long remaining = deadlineNanos - nanoTime.getAsLong();
            return remaining <= 0 ? 0 : remaining;
        }
    }
}
