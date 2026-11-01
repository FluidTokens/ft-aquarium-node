package com.fluidtokens.aquarium.offchain.service.loans;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every line the oracle stream logs (FAB-174), and nothing else logs for it: no other {@code OracleStream*}
 * class touches an SLF4J logger.
 *
 * <p>One shape: {@code [oracle-stream] <event> boot=<bootId> ev=<n>[ <key>=<value>]*[ -- <free text>]}.
 * <ul>
 *   <li>{@code boot} is 8 hex characters drawn once per instance, so the lines of one process can be told
 *       apart from a restart's.</li>
 *   <li>{@code ev} counts the INFO and WARN lines the logger actually emits — and only those. A DEBUG line
 *       carries the current value without advancing it. So a gap in {@code ev} means lines were LOST between
 *       here and the reader, never that they were filtered by level.</li>
 *   <li>Values carry no spaces. The one exception is {@link #msg}, which quotes server text, sanitised.</li>
 * </ul>
 *
 * <p>⛔ <b>Nothing secret is ever passed in.</b> The callers hand this class event names, numbers, reason
 * tokens, a host and a server's refusal message — never the signed query, the key, the signature or the
 * stake address — and exceptions only as their simple class name.
 */
public final class OracleStreamLog {

    static final String PREFIX = "[oracle-stream] ";
    static final int MSG_LIMIT = 200;

    private static final Logger DEFAULT = LoggerFactory.getLogger(OracleStreamLog.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Logger logger;
    private final String bootId;
    private final AtomicLong ev = new AtomicLong();
    private final Map<String, String> latches = new ConcurrentHashMap<>();

    public OracleStreamLog() {
        this(DEFAULT);
    }

    OracleStreamLog(Logger logger) {
        this.logger = logger;
        this.bootId = "%08x".formatted(RANDOM.nextInt());
    }

    public String bootId() {
        return bootId;
    }

    /** The last {@code ev} an emitted INFO or WARN line carried; 0 before the first. */
    long ev() {
        return ev.longValue();
    }

    public void info(String event, String fields) {
        info(event, fields, null);
    }

    public void info(String event, String fields, String text) {
        if (logger.isInfoEnabled()) {
            logger.info(line(event, ev.incrementAndGet(), fields, text));
        }
    }

    public void warn(String event, String fields) {
        warn(event, fields, null);
    }

    public void warn(String event, String fields, String text) {
        if (logger.isWarnEnabled()) {
            logger.warn(line(event, ev.incrementAndGet(), fields, text));
        }
    }

    public void debug(String event, String fields) {
        if (logger.isDebugEnabled()) {
            logger.debug(line(event, ev.longValue(), fields, null));
        }
    }

    /**
     * WARN when {@code state} differs from the last state recorded under {@code latchKey} (the first time
     * included), DEBUG otherwise — so a condition that repeats on every reconnect is said once, and again
     * only when it CHANGES.
     */
    public void warnOnChange(String latchKey, String state, String event, String fields) {
        String previous = latches.put(latchKey, String.valueOf(state));
        if (String.valueOf(state).equals(previous)) {
            debug(event, fields);
        } else {
            warn(event, fields);
        }
    }

    /**
     * {@code msg="<text>"} for server text: {@code "} becomes {@code '}, control characters are dropped, and
     * it is capped at {@value #MSG_LIMIT} characters. Null is an empty message.
     */
    public static String msg(String text) {
        StringBuilder out = new StringBuilder();
        String source = text == null ? "" : text;
        for (int i = 0; i < source.length() && out.length() < MSG_LIMIT; i++) {
            char c = source.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            out.append(c == '"' ? '\'' : c);
        }
        return "msg=\"" + out + "\"";
    }

    /**
     * A value that may carry no space: truncated to {@code limit} characters, then every whitespace, control
     * or quote character replaced by {@code _}. Null is {@code null}.
     */
    public static String token(String value, int limit) {
        if (value == null) {
            return "null";
        }
        String cut = value.length() <= limit ? value : value.substring(0, limit);
        StringBuilder out = new StringBuilder(cut.length());
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            out.append(Character.isWhitespace(c) || Character.isISOControl(c) || c == '"' ? '_' : c);
        }
        return out.toString();
    }

    /** The simple class name of a throwable — ⛔ never its message — or its full name for an anonymous class. */
    public static String simpleName(Throwable t) {
        if (t == null) {
            return "null";
        }
        String name = t.getClass().getSimpleName();
        return name.isEmpty() ? t.getClass().getName() : name;
    }

    private String line(String event, long n, String fields, String text) {
        StringBuilder line = new StringBuilder(PREFIX).append(event)
                .append(" boot=").append(bootId)
                .append(" ev=").append(n);
        if (fields != null && !fields.isEmpty()) {
            line.append(' ').append(fields);
        }
        if (text != null && !text.isEmpty()) {
            line.append(" -- ").append(text);
        }
        return line.toString();
    }
}
