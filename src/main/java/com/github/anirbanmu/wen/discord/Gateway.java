package com.github.anirbanmu.wen.discord;

import com.github.anirbanmu.wen.discord.json.GatewayEvent;
import com.github.anirbanmu.wen.discord.json.GatewayEventParser;
import com.github.anirbanmu.wen.discord.json.GatewayEventParser.ParseResult;
import com.github.anirbanmu.wen.discord.json.Identify;
import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.log.Log;
import com.github.anirbanmu.wen.util.Http;
import com.github.anirbanmu.wen.util.Json;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class Gateway {
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int GATEWAY_INTENTS = 0;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private final String token;
    private final GatewayEventParser parser;
    private final Consumer<Interaction> interactionHandler;
    private final ExecutorService handlerExecutor;

    // persists across reconnects
    private volatile boolean running;
    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private final AtomicInteger lastSequence = new AtomicInteger(-1);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

    // session management
    private final ReentrantLock sessionLock = new ReentrantLock();
    private Session currentSession;

    public Gateway(String token, Consumer<Interaction> interactionHandler) {
        this.token = token;
        this.interactionHandler = interactionHandler;
        this.parser = new GatewayEventParser();
        this.handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void connect() {
        running = true;
        doConnect(GATEWAY_URL);
    }

    private void doConnect(String url) {
        sessionLock.lock();
        try {
            if (currentSession != null) {
                currentSession.close();
                currentSession = null;
            }

            Log.info("gateway.connecting", "url", url);
            Session session = new Session() {
                @Override
                void onSessionClosed() {
                    if (running) {
                        scheduleReconnect();
                    }
                }
            };
            currentSession = session;

            Http.CLIENT.newWebSocketBuilder()
                .buildAsync(URI.create(url), session)
                .exceptionally(ex -> {
                    Log.error("gateway.connect_failed", ex);
                    // connection failed, clear resume state so next attempt uses default URL
                    sessionId = null;
                    resumeGatewayUrl = null;
                    if (running) {
                        scheduleReconnect();
                    }
                    return null;
                });
        } finally {
            sessionLock.unlock();
        }
    }

    public void disconnect() {
        running = false;

        sessionLock.lock();
        try {
            if (currentSession != null) {
                currentSession.close();
                currentSession = null;
            }
        } finally {
            sessionLock.unlock();
        }

        handlerExecutor.shutdown();
        try {
            if (!handlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.warn("gateway.handler_timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        // exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 30s
        long delay = Math.min(1000L * (1L << Math.min(attempt - 1, 4)), MAX_RECONNECT_DELAY_MS);
        Log.info("gateway.reconnect_scheduled", "attempt", attempt, "delay_ms", delay);

        Thread.ofVirtual().name("reconnect").start(() -> {
            try {
                Thread.sleep(delay);
                if (running) {
                    String url = resumeGatewayUrl != null
                        ? resumeGatewayUrl + "?v=10&encoding=json"
                        : GATEWAY_URL;
                    doConnect(url);
                }
            } catch (InterruptedException ex) {
                // shutdown
            }
        });
    }

    public CompletableFuture<Void> awaitReady() {
        return readyFuture;
    }

    public boolean isHealthy() {
        Session s = currentSession;
        return running && s != null && !s.closed.get();
    }

    // per-connection state and websocket handler
    private abstract class Session implements WebSocket.Listener {
        private volatile WebSocket socket;
        private volatile Thread heartbeatThread;
        private volatile long heartbeatInterval;
        private volatile CompletableFuture<Void> heartbeatAck;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final StringBuilder messageBuffer = new StringBuilder();

        abstract void onSessionClosed();

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            Thread hb = heartbeatThread;
            if (hb != null) {
                hb.interrupt();
            }

            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
                } catch (Exception ex) {
                    // already closed
                }
            }

            onSessionClosed();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            Log.info("gateway.connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                handleMessage(messageBuffer.toString());
                messageBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Log.info("gateway.closed", "code", statusCode, "reason", reason);
            close();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Log.error("gateway.error", error);
            close();
        }

        private void handleMessage(String raw) {
            try {
                ParseResult result = parser.parse(raw);

                if (result.sequence() != null) {
                    lastSequence.updateAndGet(current -> Math.max(current, result.sequence()));
                }

                GatewayEvent event = result.event();
                if (event == null) {
                    return;
                }

                switch (event) {
                    case GatewayEvent.Hello hello -> {
                        heartbeatInterval = hello.heartbeatInterval();
                        Log.info("gateway.hello", "interval_ms", hello.heartbeatInterval());
                        startHeartbeat();
                        if (sessionId != null && resumeGatewayUrl != null) {
                            sendResume();
                        } else {
                            sendIdentify();
                        }
                    }
                    case GatewayEvent.Ready ready -> {
                        sessionId = ready.sessionId();
                        resumeGatewayUrl = ready.resumeGatewayUrl();
                        reconnectAttempts.set(0); // reset backoff on successful connection
                        readyFuture.complete(null);
                        String redacted = sessionId.length() > 4
                            ? "..." + sessionId.substring(sessionId.length() - 4)
                            : "REDACTED";
                        Log.info("gateway.ready", "session", redacted);
                    }
                    case GatewayEvent.InteractionCreate ic -> {
                        if (interactionHandler != null) {
                            Interaction interaction = ic.interaction();
                            handlerExecutor.execute(() -> interactionHandler.accept(interaction));
                        }
                    }
                    case GatewayEvent.HeartbeatRequest _ -> sendHeartbeat();
                    case GatewayEvent.HeartbeatAck _ -> {
                        CompletableFuture<Void> ack = heartbeatAck;
                        if (ack != null) {
                            ack.complete(null);
                        }
                    }
                    case GatewayEvent.Reconnect _ -> {
                        Log.info("gateway.reconnect_requested");
                        close();
                    }
                    case GatewayEvent.InvalidSession invalid -> {
                        Log.info("gateway.invalid_session", "resumable", invalid.resumable());
                        if (!invalid.resumable()) {
                            sessionId = null;
                            resumeGatewayUrl = null;
                        }
                        close();
                    }
                }
            } catch (Exception ex) {
                Log.error("gateway.message_error", ex);
                close(); // close session on parse error
            }
        }

        private void sendIdentify() {
            Identify identify = Identify.create(token, GATEWAY_INTENTS);
            if (!sendOpcode(OP_IDENTIFY, identify)) {
                close();
                return;
            }
            Log.info("gateway.identify_sent");
        }

        private void sendResume() {
            String resume = String.format(
                "{\"op\":%d,\"d\":{\"token\":\"%s\",\"session_id\":\"%s\",\"seq\":%d}}",
                OP_RESUME, token, sessionId, lastSequence.get());
            if (closed.get()) {
                return;
            }
            try {
                socket.sendText(resume, true);
                Log.info("gateway.resume_sent");
            } catch (Exception ex) {
                Log.error("gateway.resume_send_failed", ex);
                close();
            }
        }

        private void sendHeartbeat() {
            WebSocket ws = socket;
            if (ws == null || closed.get()) {
                return;
            }
            int seq = lastSequence.get();
            String hb = seq < 0 ? "{\"op\":1,\"d\":null}" : "{\"op\":1,\"d\":" + seq + "}";
            try {
                ws.sendText(hb, true);
            } catch (Exception ex) {
                Log.error("gateway.heartbeat_send_failed", ex);
                close();
            }
        }

        private boolean sendOpcode(int op, Object data) {
            if (closed.get()) {
                return false;
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Json.DSL.serialize(data, out);
                String payload = "{\"op\":" + op + ",\"d\":" + out.toString(StandardCharsets.UTF_8) + "}";
                socket.sendText(payload, true);
                return true;
            } catch (Exception ex) {
                Log.error("gateway.send_failed", ex);
                return false;
            }
        }

        private void startHeartbeat() {
            heartbeatThread = Thread.ofVirtual().name("heartbeat").start(() -> {
                try {
                    // initial jitter per discord docs
                    Thread.sleep((long) (heartbeatInterval * Math.random()));

                    while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                        heartbeatAck = new CompletableFuture<>();
                        sendHeartbeat();

                        Thread.sleep(heartbeatInterval);

                        // check if ack arrived during sleep
                        if (!heartbeatAck.isDone()) {
                            Log.warn("gateway.heartbeat_timeout");
                            close();
                            return;
                        }
                    }
                } catch (InterruptedException ex) {
                    // shutdown
                } catch (Exception ex) {
                    Log.error("gateway.heartbeat_error", ex);
                    close();
                }
            });
        }
    }
}
