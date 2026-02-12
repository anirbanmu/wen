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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// single-use discord gateway connection. connects once, runs until dead.
// resume state can be read after death and passed into a new instance.
public class Gateway {
    private static final int GATEWAY_INTENTS = 0;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;

    private final String token;
    private final String url;
    private final GatewayEventParser parser = new GatewayEventParser();
    private final Consumer<Interaction> interactionHandler;
    private final ExecutorService handlerExecutor;

    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private volatile int lastSequence = -1;

    private volatile WebSocket socket;
    private volatile Thread heartbeatThread;
    private volatile long heartbeatInterval;
    private volatile long lastHeartbeatSentAt;
    private volatile long lastAckAt = System.nanoTime(); // init to now so first heartbeat check doesn't false-positive
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final StringBuilder messageBuffer = new StringBuilder();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private volatile boolean gotReady;

    public record ResumeState(String sessionId, String resumeGatewayUrl, int lastSequence) {
        public boolean canResume() {
            return sessionId != null && resumeGatewayUrl != null;
        }
    }

    public Gateway(String token, String url, Consumer<Interaction> interactionHandler, ExecutorService handlerExecutor, ResumeState resume) {
        this.token = token;
        this.url = url;
        this.interactionHandler = interactionHandler;
        this.handlerExecutor = handlerExecutor;
        if (resume != null) {
            this.sessionId = resume.sessionId();
            this.resumeGatewayUrl = resume.resumeGatewayUrl();
            this.lastSequence = resume.lastSequence();
        }
    }

    // blocks until websocket handshake completes (or throws)
    public void connect() {
        Log.info("gateway.connecting", "url", url);
        Http.CLIENT.newWebSocketBuilder()
            .buildAsync(URI.create(url), new Listener())
            .join();
    }

    // blocks until this gateway dies
    public void awaitClosed() {
        closedFuture.join();
    }

    public void disconnect() {
        closeAndInvalidate();
    }

    public boolean isHealthy() {
        return !closed.get() && socket != null;
    }

    // true if we got a READY event (successful fresh connect or resume)
    public boolean wasReady() {
        return gotReady;
    }

    public ResumeState resumeState() {
        return new ResumeState(sessionId, resumeGatewayUrl, lastSequence);
    }

    // session is dead or we're shutting down — send 1000 so discord knows, clear resume state
    private void closeAndInvalidate() {
        close(true);
    }

    // connection died but session might still be valid — abort TCP, keep resume state
    private void closeForReconnect() {
        close(false);
    }

    private void close(boolean sendCloseFrame) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (sendCloseFrame) {
            sessionId = null;
            resumeGatewayUrl = null;
        }

        Thread hb = heartbeatThread;
        if (hb != null) {
            hb.interrupt();
        }

        WebSocket ws = socket;
        if (ws != null) {
            try {
                if (sendCloseFrame) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
                } else {
                    ws.abort();
                }
            } catch (Exception ex) {
                // already closed
            }
        }

        closedFuture.complete(null);
    }

    private void handleMessage(String raw) {
        try {
            ParseResult result = parser.parse(raw);

            if (result.sequence() != null) {
                int seq = result.sequence();
                if (seq > lastSequence) {
                    lastSequence = seq;
                }
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
                    gotReady = true;
                    String redacted = sessionId.length() > 4
                        ? "..." + sessionId.substring(sessionId.length() - 4)
                        : "REDACTED";
                    Log.info("gateway.ready", "session", redacted);
                }
                case GatewayEvent.Resumed _ -> {
                    gotReady = true;
                    Log.info("gateway.resumed");
                }
                case GatewayEvent.InteractionCreate ic -> {
                    if (closed.get()) {
                        return; // shutting down
                    }
                    Interaction interaction = ic.interaction();
                    handlerExecutor.execute(() -> interactionHandler.accept(interaction));
                }
                case GatewayEvent.HeartbeatRequest _ -> sendHeartbeat();
                case GatewayEvent.HeartbeatAck _ -> lastAckAt = System.nanoTime();
                case GatewayEvent.Reconnect _ -> {
                    Log.info("gateway.reconnect_requested");
                    closeForReconnect();
                }
                case GatewayEvent.InvalidSession invalid -> {
                    Log.info("gateway.invalid_session", "resumable", invalid.resumable());
                    if (invalid.resumable())
                        closeForReconnect();
                    else
                        closeAndInvalidate();
                }
            }
        } catch (Exception ex) {
            Log.error("gateway.message_error", ex);
            closeForReconnect();
        }
    }

    private void sendIdentify() {
        Identify identify = Identify.create(token, GATEWAY_INTENTS);
        if (!sendOpcode(OP_IDENTIFY, identify)) {
            // network dead, but no session established yet — don't invalidate a previous one
            closeForReconnect();
            return;
        }
        Log.info("gateway.identify_sent");
    }

    private void sendResume() {
        WebSocket ws = socket;
        if (ws == null || closed.get()) {
            return;
        }
        String payload = String.format(
            "{\"op\":%d,\"d\":{\"token\":\"%s\",\"session_id\":\"%s\",\"seq\":%d}}",
            OP_RESUME, token, sessionId, lastSequence);
        try {
            ws.sendText(payload, true);
            Log.info("gateway.resume_sent");
        } catch (Exception ex) {
            Log.error("gateway.resume_send_failed", ex);
            closeForReconnect();
        }
    }

    private void sendHeartbeat() {
        WebSocket ws = socket;
        if (ws == null || closed.get()) {
            return;
        }
        String hb = lastSequence < 0 ? "{\"op\":1,\"d\":null}" : "{\"op\":1,\"d\":" + lastSequence + "}";
        try {
            ws.sendText(hb, true);
            lastHeartbeatSentAt = System.nanoTime();
        } catch (Exception ex) {
            Log.error("gateway.heartbeat_send_failed", ex);
            closeForReconnect();
        }
    }

    private boolean sendOpcode(int op, Object data) {
        WebSocket ws = socket;
        if (ws == null || closed.get()) {
            return false;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Json.DSL.serialize(data, out);
            String payload = "{\"op\":" + op + ",\"d\":" + out.toString(StandardCharsets.UTF_8) + "}";
            ws.sendText(payload, true);
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
                    sendHeartbeat();
                    Thread.sleep(heartbeatInterval);

                    if (lastAckAt < lastHeartbeatSentAt) {
                        Log.warn("gateway.heartbeat_timeout");
                        closeForReconnect();
                        return;
                    }
                }
            } catch (InterruptedException ex) {
                // shutdown
            } catch (Exception ex) {
                Log.error("gateway.heartbeat_error", ex);
                closeForReconnect();
            }
        });
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            Log.info("gateway.connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                handleMessage(messageBuffer.toString());
                messageBuffer.setLength(0);
                messageBuffer.trimToSize();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Log.info("gateway.closed", "code", statusCode, "reason", reason);
            closeForReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Log.error("gateway.error", error);
            closeForReconnect();
        }
    }
}
