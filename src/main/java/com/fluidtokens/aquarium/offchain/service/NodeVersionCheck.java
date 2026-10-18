package com.fluidtokens.aquarium.offchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.AcquariumOffchainApp;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tells the operator, in one log line at startup and then once a day, whether the build this node runs is
 * behind {@code main} — GitHub issue #55. Informational only: it never updates, restarts, pulls or changes
 * anything.
 *
 * <p>The source is GitHub's compare API, unauthenticated:
 * {@code GET https://api.github.com/repos/FluidTokens/ft-aquarium-node/compare/<own sha>...main?per_page=1}.
 * ⚠ GitHub reports the HEAD side ({@code main}) relative to the BASE side (this build), so a node BEHIND main
 * is answered {@code status: "ahead"} with its count in {@code ahead_by}, and {@code status: "behind"} means
 * this build is ahead of main. Measured against the live API on 2026-10-18.
 *
 * <p>⛔ <b>Its own daemon thread, never Spring's scheduler.</b> Spring's scheduled-task pool
 * ({@code spring.task.scheduling.pool.size}) is shared by the transaction processor, liquidation, compound
 * and the oracle refresh, and an operator may size it to 1. So this class uses no Spring scheduling
 * annotation and no Spring task scheduler: a slow, hanging or failing GitHub occupies only the
 * {@code version-check} thread, and each run is bounded by a 5 s connect timeout and a 10 s total deadline.
 *
 * <p>⛔ <b>A run never throws.</b> A task that throws out of a {@link ScheduledExecutorService} silently
 * cancels every later run, so one bad answer would end the daily check for the life of the process.
 *
 * <p>⚠ <b>A shape this class does not recognise is NOT an answer</b>: a 404 (a fork or a local build), any
 * other non-200, a missing or inconsistent count is "unavailable", never "behind". And no text from GitHub's
 * response body or headers is placed in a log line — the compare link is built from the validated sha.
 */
@Slf4j
@Service
public class NodeVersionCheck {

    static final String API_BASE = "https://api.github.com";
    static final String REPOSITORY = "FluidTokens/ft-aquarium-node";
    static final String THREAD_NAME = "version-check";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration TOTAL_DEADLINE = Duration.ofSeconds(10);
    static final Duration INTERVAL = Duration.ofHours(24);

    private static final String PREFIX = "version check: ";
    private static final String UNAVAILABLE = "version check unavailable: ";
    private static final String USER_AGENT = "ft-aquarium-node-version-check";
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> ON = Set.of("true", "on", "yes", "1");
    private static final Set<String> OFF = Set.of("false", "off", "no", "0");
    private static final int FLAG_ECHO = 40;
    private static final int REASON_LIMIT = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The seam: one compare request for one commit. Package-private so a test can answer it. */
    @FunctionalInterface
    interface CompareSource {
        Answer fetch(String commit) throws IOException, InterruptedException, TimeoutException;
    }

    /** What GitHub said: the HTTP status code, then the body. */
    record Answer(int status, String body) {
    }

    enum Severity { INFO, WARN }

    /** One run's single line. */
    record Outcome(Severity severity, String message) {
    }

    private final String enabled;
    private final Supplier<Map<String, Object>> provenance;
    private final CompareSource source;
    private final Duration interval;
    private final Duration deadline;

    private volatile Map<String, Object> build;
    private ScheduledExecutorService executor;

    /**
     * The commit and the dirty flag come from {@link AcquariumOffchainApp#buildProvenance()} — the jar's own
     * {@code build-info.properties} — and deliberately NOT from the {@code aquarium.build.*} properties, which
     * an environment variable could override. Read on first use, not here: the constructor does no I/O.
     */
    @Autowired
    public NodeVersionCheck(@Value("${aquarium.version-check.enabled:true}") String enabled) {
        this(enabled, AcquariumOffchainApp::buildProvenance, new GitHubCompareSource(API_BASE, TOTAL_DEADLINE),
                INTERVAL, TOTAL_DEADLINE);
    }

    /** For tests only. */
    NodeVersionCheck(String enabled, String commit, String dirty, CompareSource source,
                     Duration interval, Duration deadline) {
        this(enabled, () -> Map.of("aquarium.build.commit", String.valueOf(commit),
                "aquarium.build.dirty", String.valueOf(dirty)), source, interval, deadline);
    }

    private NodeVersionCheck(String enabled, Supplier<Map<String, Object>> provenance, CompareSource source,
                             Duration interval, Duration deadline) {
        this.enabled = enabled;
        this.provenance = provenance;
        this.source = source;
        this.interval = interval;
        this.deadline = deadline;
    }

    // ======================================================================================
    // Lifecycle
    // ======================================================================================

    /**
     * Off: one INFO line, no thread, no request. On: one daemon thread runs the check now and every
     * {@link #INTERVAL}; this returns at once, without waiting for GitHub.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        String off = offLine();
        if (off != null) {
            log.info(off);
            return;
        }
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** The startup line for a node that will not check, or {@code null} when the check is on. */
    private String offLine() {
        String value = enabled == null ? "" : enabled.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || ON.contains(value)) {
            return null;
        }
        if (OFF.contains(value)) {
            return PREFIX + "off (AQUARIUM_VERSION_CHECK_ENABLED=" + value + ") — this node will not contact GitHub";
        }
        return PREFIX + "off — AQUARIUM_VERSION_CHECK_ENABLED=\"" + clean(enabled.trim(), FLAG_ECHO)
                + "\" was not understood (expected true or false), so this node will not contact GitHub";
    }

    // ======================================================================================
    // One run
    // ======================================================================================

    /** One check, one line. Never throws. */
    void runOnce() {
        Outcome outcome;
        try {
            outcome = decide();
        } catch (Throwable unexpected) {
            outcome = unavailable("unexpected " + unexpected.getClass().getSimpleName());
        }
        String message = outcome.message() + dirtySuffix();
        if (outcome.severity() == Severity.WARN) {
            log.warn(message);
        } else {
            log.info(message);
        }
    }

    Outcome decide() {
        String commit = buildValue("aquarium.build.commit");
        if (AcquariumOffchainApp.UNKNOWN.equals(commit)) {
            return unavailable("this build does not record the commit it was built from");
        }
        if (commit == null || !FULL_SHA.matcher(commit).matches()) {
            return unavailable("this build's recorded commit is not a full 40-character commit id");
        }
        String shortSha = commit.substring(0, 12);

        Answer answer;
        try {
            answer = source.fetch(commit);
        } catch (HttpTimeoutException | TimeoutException e) {
            return unavailable("GitHub did not answer within " + seconds(deadline));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("interrupted while waiting for GitHub (the node is stopping)");
        } catch (Exception e) {
            return unavailable("could not reach GitHub — " + transportFailure(e));
        }
        if (answer == null) {
            return unavailable("unrecognised answer from GitHub");
        }

        // ⚠ The HTTP status code FIRST: the 404 body carries a `status` field too ("404", a string).
        int code = answer.status();
        if (code == 404) {
            return unavailable("commit " + shortSha + " is unknown to GitHub — a fork or a local build");
        }
        if (code == 403 || code == 429) {
            return unavailable("GitHub refused the request (HTTP " + code
                    + ") — usually its limit of 60 unauthenticated requests per hour per IP");
        }
        if (code != 200) {
            return unavailable("GitHub answered HTTP " + code);
        }

        Comparison comparison = parse(answer.body());
        if (comparison == null) {
            return unavailable("unrecognised answer from GitHub");
        }
        String link = "https://github.com/" + REPOSITORY + "/compare/" + commit + "...main";
        return switch (comparison.status()) {
            case "identical" -> new Outcome(Severity.INFO,
                    PREFIX + "up to date — this build (" + shortSha + ") is the head of main");
            case "ahead" -> new Outcome(Severity.WARN, PREFIX + "this node is " + commits(comparison.aheadBy())
                    + " behind main (built from " + shortSha + "). Changes since this build: " + link);
            case "diverged" -> new Outcome(Severity.WARN, PREFIX + "this node is " + commits(comparison.aheadBy())
                    + " behind main and carries " + commits(comparison.behindBy())
                    + (comparison.behindBy() == 1 ? " that is" : " that are") + " not on main (built from "
                    + shortSha + "). Changes: " + link);
            case "behind" -> new Outcome(Severity.INFO, PREFIX + "this build (" + shortSha + ") is "
                    + commits(comparison.behindBy()) + " ahead of main — not a build of main");
            default -> unavailable("unrecognised answer from GitHub");
        };
    }

    private record Comparison(String status, int aheadBy, int behindBy) {
    }

    /**
     * The answer, only when {@code status} is one of the four strings and both counts are present, integral,
     * non-negative and consistent with it; {@code null} otherwise. No exception text escapes: a parser
     * message can quote the body.
     */
    private static Comparison parse(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode status = root.get("status");
        Integer ahead = count(root.get("ahead_by"));
        Integer behind = count(root.get("behind_by"));
        if (status == null || !status.isTextual() || ahead == null || behind == null) {
            return null;
        }
        boolean consistent = switch (status.asText()) {
            case "identical" -> ahead == 0 && behind == 0;
            case "ahead" -> ahead >= 1 && behind == 0;
            case "behind" -> ahead == 0 && behind >= 1;
            case "diverged" -> ahead >= 1 && behind >= 1;
            default -> false;
        };
        return consistent ? new Comparison(status.asText(), ahead, behind) : null;
    }

    private static Integer count(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            return null;
        }
        return node.intValue();
    }

    /**
     * The exception's simple name and message, control characters stripped and truncated. ⚠ Except a
     * {@link ProtocolException}: the JDK's HTTP/1.1 parser raises one for a malformed status line or header
     * and QUOTES the offending response text in its message, so for that one the name alone is logged.
     */
    private static String transportFailure(Exception e) {
        String name = e.getClass().getSimpleName();
        if (e instanceof ProtocolException || e.getMessage() == null || e.getMessage().isBlank()) {
            return name;
        }
        return clean(name + ": " + e.getMessage(), REASON_LIMIT);
    }

    private static Outcome unavailable(String reason) {
        return new Outcome(Severity.INFO, UNAVAILABLE + reason);
    }

    private String dirtySuffix() {
        String dirty;
        try {
            dirty = buildValue("aquarium.build.dirty");
        } catch (RuntimeException e) {
            return "";
        }
        return "true".equals(dirty) ? " — this build also carries uncommitted changes on top of that commit" : "";
    }

    private String buildValue(String key) {
        Map<String, Object> values = build;
        if (values == null) {
            values = provenance.get();
            build = values;
        }
        Object value = values == null ? null : values.get(key);
        return value == null ? null : value.toString();
    }

    private static String commits(int count) {
        return count + (count == 1 ? " commit" : " commits");
    }

    private static String seconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }

    /** Control characters stripped, then truncated. */
    private static String clean(String text, int limit) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.length() <= limit ? out.toString() : out.substring(0, limit) + "…";
    }

    // ======================================================================================
    // The real adapter
    // ======================================================================================

    /**
     * The compare request on the JDK's own {@link HttpClient}: no token, no {@code Authorization} header, a
     * {@code User-Agent} (GitHub rejects requests without one), and the WHOLE exchange bounded by the total
     * deadline — {@code HttpRequest.timeout} alone may not bound reading the body.
     *
     * <p>The client is built on first use, not in the constructor: building one starts its selector thread,
     * and a node with the check off must create no thread at all.
     */
    static final class GitHubCompareSource implements CompareSource {

        private final String baseUrl;
        private final Duration deadline;
        private HttpClient http;

        GitHubCompareSource(String baseUrl, Duration deadline) {
            this.baseUrl = baseUrl;
            this.deadline = deadline;
        }

        private synchronized HttpClient http() {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return http;
        }

        @Override
        public Answer fetch(String commit) throws IOException, InterruptedException, TimeoutException {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/repos/" + REPOSITORY + "/compare/" + commit + "...main?per_page=1"))
                    .timeout(deadline)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> future =
                    http().sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
                return new Answer(response.statusCode(), response.body());
            } catch (TimeoutException e) {
                future.cancel(true);
                throw e;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IOException(cause == null ? "request failed" : cause.getClass().getSimpleName());
            }
        }
    }
}
