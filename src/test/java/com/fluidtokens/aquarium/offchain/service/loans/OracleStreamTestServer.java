package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.cip.cip8.COSESign1;
import com.bloxbean.cardano.client.util.HexUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import reactor.netty.NettyPipeline;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Test support, no tests: an in-process stand-in for FluidTokens' oracle WebSocket (FAB-174), on Reactor
 * Netty at {@code 127.0.0.1:0}.
 *
 * <p>Every request goes through one {@code .handle(...)}, never a path route, so a query string can never
 * decide whether a request matches. Three behaviours, set before the client connects:
 * <ul>
 *   <li><b>refuse</b> — answer every handshake with a status and a body ({@code content-type: application/json},
 *       {@code Content-Length} always set), the way the real authorizer refuses (probe log item 36);</li>
 *   <li><b>authorize</b> — verify the query as the authorizer does: {@code stakeAddress} is the expected one,
 *       {@code ts} is within ±120 s of this server's clock, the CIP-30 signature verifies, and the COSE
 *       payload equals THIS class's own literal of the signed text (not production's). A failure is the
 *       measured 401;</li>
 *   <li><b>accept</b> — open the WebSocket with {@code handlePing(true)}, so every ping reaches the handler
 *       and is counted. When pings are answered the handler pongs them itself; when ignored, no pong is
 *       ever sent. With {@link #ignoreClientClose(boolean)}, the client's Close frame is recorded and
 *       swallowed before Reactor Netty can answer it, so the client's closing handshake never completes and
 *       only its own abort ends the connection (FAB-174-3 audit residue R-1).</li>
 * </ul>
 * Per connection it records the open and close instants, the raw query and the pings received, and can push a
 * text message as one frame or as a {@code TextWebSocketFrame(false, ...)} followed by continuation frames,
 * push a binary message, close, or drop the TCP connection with no close frame. Its own logs are never asserted
 * on.
 *
 * <p>{@link Hanging} is a raw {@link ServerSocket} that accepts and never answers — the handshake-timeout case.
 */
final class OracleStreamTestServer implements AutoCloseable {

    /** This class's own literal of the signed text, so a production change to it cannot pass by agreeing with itself. */
    static String expectedSignedText(String stakeAddress, String ts) {
        return "FluidTokens oracle websocket\nstake: " + stakeAddress + "\nts: " + ts;
    }

    static final String REFUSED_401 = "{\"message\":\"invalid signature or ts older than 2 minutes\"}";

    private final AtomicInteger requests = new AtomicInteger();
    private final List<Instant> requestTimes = new CopyOnWriteArrayList<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> authFailures = new CopyOnWriteArrayList<>();

    private volatile int refuseStatus;
    private volatile String refuseBody;
    private volatile String expectedStakeAddress;
    private volatile boolean answerPings = true;
    private volatile boolean ignoreClientClose;
    private DisposableServer server;

    /** Refuse every handshake with this status and body. */
    OracleStreamTestServer refuse(int status, String body) {
        this.refuseStatus = status;
        this.refuseBody = body;
        return this;
    }

    /** Verify each handshake's signed query for this stake address; refuse with the measured 401 otherwise. */
    OracleStreamTestServer authorize(String stakeAddress) {
        this.expectedStakeAddress = stakeAddress;
        return this;
    }

    OracleStreamTestServer answerPings(boolean answer) {
        this.answerPings = answer;
        return this;
    }

    /**
     * Never answer the client's Close frame: record when it arrived ({@link Session#clientCloseAt}) and drop it,
     * so the connection stays open until the client aborts it. Read per session at its handshake.
     */
    OracleStreamTestServer ignoreClientClose(boolean ignore) {
        this.ignoreClientClose = ignore;
        return this;
    }

    OracleStreamTestServer start() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(this::handle)
                .bindNow();
        return this;
    }

    URI uri() {
        return URI.create("ws://127.0.0.1:" + server.port() + "/dev");
    }

    /** Every HTTP request seen, refused or not. */
    int requests() {
        return requests.get();
    }

    /** When each HTTP request reached this server, in order. */
    List<Instant> requestTimes() {
        return requestTimes;
    }

    List<Session> sessions() {
        return sessions;
    }

    /** Every raw query string received, in order. */
    List<String> queries() {
        return queries;
    }

    /** Why each authorize-mode refusal happened. */
    List<String> authFailures() {
        return authFailures;
    }

    Session awaitSession(int index, Duration timeout) {
        await(() -> sessions.size() > index, timeout, "connection #" + (index + 1));
        return sessions.get(index);
    }

    @Override
    public void close() {
        if (server != null) {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    private org.reactivestreams.Publisher<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        requestTimes.add(Instant.now());
        requests.incrementAndGet();
        String uri = request.uri();
        int q = uri.indexOf('?');
        String query = q < 0 ? "" : uri.substring(q + 1);
        queries.add(query);
        if (refuseStatus != 0) {
            return answer(response, refuseStatus, refuseBody);
        }
        String expected = expectedStakeAddress;
        if (expected != null) {
            String failure = verify(query, expected);
            if (failure != null) {
                authFailures.add(failure);
                return answer(response, 401, REFUSED_401);
            }
        }
        Session session = new Session(query);
        boolean pong = answerPings;
        boolean silentOnClose = ignoreClientClose;
        return response.sendWebsocket((in, out) -> session.run(in, out, pong, silentOnClose),
                WebsocketServerSpec.builder().handlePing(true).build());
    }

    private static Mono<Void> answer(HttpServerResponse response, int status, String body) {
        byte[] bytes = body.getBytes(UTF_8);
        return response.status(status)
                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bytes.length))
                .sendByteArray(Mono.just(bytes))
                .then();
    }

    /** Null when the query is what the authorizer accepts; otherwise why not. */
    private static String verify(String query, String expectedStakeAddress) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), UTF_8));
            }
        }
        String stakeAddress = params.get("stakeAddress");
        String ts = params.get("ts");
        String key = params.get("key");
        String signature = params.get("signature");
        if (stakeAddress == null || ts == null || key == null || signature == null) {
            return "missing parameter";
        }
        if (!stakeAddress.equals(expectedStakeAddress)) {
            return "stakeAddress";
        }
        long millis;
        try {
            millis = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return "ts not a number";
        }
        if (Math.abs(System.currentTimeMillis() - millis) > 120_000) {
            return "ts outside 120 s";
        }
        try {
            DataSignature dataSignature = new DataSignature(signature, key);
            if (!CIP30DataSigner.INSTANCE.verify(dataSignature)) {
                return "signature does not verify";
            }
            if (!Arrays.equals(new Address(stakeAddress).getBytes(), dataSignature.address())) {
                return "signed address";
            }
            byte[] payload = COSESign1.deserialize(HexUtil.decodeHexString(signature)).payload();
            if (!Arrays.equals(expectedSignedText(stakeAddress, ts).getBytes(UTF_8), payload)) {
                return "payload";
            }
        } catch (Exception e) {
            return "signature unreadable: " + e.getClass().getSimpleName();
        }
        return null;
    }

    static void await(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what);
            }
        }
    }

    /** One accepted WebSocket. */
    final class Session {

        final String query;
        final Instant openedAt = Instant.now();
        volatile Instant closedAt;
        /** When the client's Close frame arrived; recorded only in {@link #ignoreClientClose(boolean)} mode. */
        volatile Instant clientCloseAt;
        final AtomicInteger pings = new AtomicInteger();
        final AtomicInteger framesSent = new AtomicInteger();
        private final Sinks.Many<WebSocketFrame> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private volatile WebsocketOutbound out;
        private volatile reactor.netty.Connection connection;

        Session(String query) {
            this.query = query;
        }

        private org.reactivestreams.Publisher<Void> run(WebsocketInbound in, WebsocketOutbound out, boolean pong,
                                                        boolean silentOnClose) {
            this.out = out;
            in.withConnection(connection -> {
                this.connection = connection;
                connection.onDispose(() -> {
                    if (closedAt == null) {
                        closedAt = Instant.now();
                    }
                });
                if (silentOnClose) {
                    // Ahead of Reactor Netty's bridge, which would answer the Close frame and end the connection.
                    connection.channel().pipeline().addBefore(NettyPipeline.ReactiveBridge, "ignore-client-close",
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof CloseWebSocketFrame) {
                                        if (clientCloseAt == null) {
                                            clientCloseAt = Instant.now();
                                        }
                                        ReferenceCountUtil.release(msg);
                                        return;
                                    }
                                    ctx.fireChannelRead(msg);
                                }
                            });
                }
            });
            sessions.add(this);
            Mono<Void> inbound = in.receiveFrames()
                    .doOnNext(frame -> {
                        if (frame instanceof PingWebSocketFrame ping) {
                            pings.incrementAndGet();
                            if (pong) {
                                emit(new PongWebSocketFrame(Unpooled.copiedBuffer(ping.content())));
                            }
                        }
                    })
                    .doFinally(signal -> {
                        if (closedAt == null) {
                            closedAt = Instant.now();
                        }
                        outbound.tryEmitComplete();
                    })
                    .then();
            return Mono.when(inbound, out.sendObject(outbound.asFlux()).then());
        }

        boolean open() {
            return closedAt == null;
        }

        void sendText(String text) {
            emit(new TextWebSocketFrame(text));
        }

        /** The message as UTF-8, in frames of at most {@code maxBytes}: one text frame, then continuations. */
        void sendFragmented(String text, int maxBytes) {
            byte[] bytes = text.getBytes(UTF_8);
            for (int offset = 0; offset < bytes.length; offset += maxBytes) {
                int length = Math.min(maxBytes, bytes.length - offset);
                boolean last = offset + length >= bytes.length;
                ByteBuf part = Unpooled.copiedBuffer(bytes, offset, length);
                emit(offset == 0 ? new TextWebSocketFrame(last, 0, part) : new ContinuationWebSocketFrame(last, 0, part));
            }
        }

        void sendBinary(byte[] bytes) {
            emit(new BinaryWebSocketFrame(Unpooled.copiedBuffer(bytes)));
        }

        void close(int code) {
            WebsocketOutbound current = out;
            if (current != null) {
                current.sendClose(code, "server close").subscribe(null, error -> { });
            }
        }

        /**
         * Resets the TCP connection under the WebSocket — no close frame — so the client sees the connection fail,
         * not close. ({@code Connection.dispose()} would send a close frame first.)
         */
        void drop() {
            reactor.netty.Connection current = connection;
            if (current != null) {
                current.channel().config().setOption(io.netty.channel.ChannelOption.SO_LINGER, 0);
                current.channel().close();
            }
        }

        private void emit(WebSocketFrame frame) {
            framesSent.incrementAndGet();
            outbound.emitNext(frame, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
        }
    }

    /** A TCP listener that accepts and never answers: the opening handshake never completes. */
    static final class Hanging implements AutoCloseable {

        private final ServerSocket socket;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        Hanging() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        accepted.add(socket.accept());
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/dev");
        }

        int accepted() {
            return accepted.size();
        }

        @Override
        public void close() throws IOException {
            for (Socket s : accepted) {
                s.close();
            }
            socket.close();
        }
    }
}
