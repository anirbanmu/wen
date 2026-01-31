package com.github.anirbanmu.wen.discord;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import com.github.anirbanmu.wen.discord.json.GatewayEvent;
import com.github.anirbanmu.wen.discord.json.GatewayEventParser;
import com.github.anirbanmu.wen.discord.json.GatewayEventParser.ParseResult;
import com.github.anirbanmu.wen.discord.json.Identify;
import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.log.Log;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class Gateway implements WebSocket.Listener {
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int GATEWAY_INTENTS = 0; // no privileged intents needed for slash commands
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;

    private final String token;
    private final HttpClient httpClient;
    private final DslJson<Object> json;
    private final GatewayEventParser parser;
    private final Consumer<Interaction> interactionHandler;
    private final ExecutorService handlerExecutor;

    // cross-thread state
    private volatile WebSocket socket;
    private volatile Thread heartbeatThread;
    private volatile long heartbeatInterval;
    private volatile boolean heartbeatAcked = true;
    private volatile boolean running;
    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private final AtomicInteger lastSequence = new AtomicInteger(-1);

    // only accessed from websocket callback thread
    private final StringBuilder messageBuffer = new StringBuilder();

    public Gateway(String token, Consumer<Interaction> interactionHandler) {
        this.token = token;
        this.interactionHandler = interactionHandler;
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        this.json = new DslJson<>(Settings.withRuntime().includeServiceLoader());
        this.parser = new GatewayEventParser(json);
        this.handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void connect() {
        running = true;
        doConnect(GATEWAY_URL);
    }

    private void doConnect(String url) {
        Log.info("gateway.connecting", "url", url);
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(url), this)
            .thenAccept(ws -> {
                socket = ws;
                Log.info("gateway.connected");
            })
            .exceptionally(ex -> {
                Log.error("gateway.connect_failed", ex);
                scheduleReconnect();
                return null;
            });
    }

    public void disconnect() {
        running = false;

        // stop heartbeat
        Thread hb = heartbeatThread;
        if (hb != null) {
            hb.interrupt();
        }

        // wait for in-flight handlers
        handlerExecutor.shutdown();
        try {
            if (!handlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.warn("gateway.handler_timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        // close socket
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
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
        if (running) {
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        Log.error("gateway.error", error);
        if (running) {
            scheduleReconnect();
        }
    }

    private void handleMessage(String raw) {
        try {
            ParseResult result = parser.parse(raw);

            // only update sequence if higher (monotonically increasing)
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
                    Log.info("gateway.ready", "session", sessionId);
                }
                case GatewayEvent.InteractionCreate ic -> {
                    if (interactionHandler != null) {
                        Interaction interaction = ic.interaction();
                        handlerExecutor.execute(() -> interactionHandler.accept(interaction));
                    }
                }
                case GatewayEvent.HeartbeatRequest _ -> sendHeartbeat();
                case GatewayEvent.HeartbeatAck _ -> heartbeatAcked = true;
                case GatewayEvent.Reconnect _ -> {
                    Log.info("gateway.reconnect_requested");
                    WebSocket ws = socket;
                    if (ws != null) {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                    }
                }
                case GatewayEvent.InvalidSession invalid -> {
                    Log.info("gateway.invalid_session", "resumable", invalid.resumable());
                    if (!invalid.resumable()) {
                        sessionId = null;
                        resumeGatewayUrl = null;
                    }
                    scheduleReconnect();
                }
            }
        } catch (Exception ex) {
            Log.error("gateway.message_error", ex);
        }
    }

    private void sendIdentify() {
        Identify identify = Identify.create(token, GATEWAY_INTENTS);
        sendOpcode(OP_IDENTIFY, identify);
        Log.info("gateway.identify_sent");
    }

    private void sendResume() {
        String resume = String.format(
            "{\"op\":%d,\"d\":{\"token\":\"%s\",\"session_id\":\"%s\",\"seq\":%d}}",
            OP_RESUME, token, sessionId, lastSequence.get());
        socket.sendText(resume, true);
        Log.info("gateway.resume_sent");
    }

    private void sendHeartbeat() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        int seq = lastSequence.get();
        String hb = seq < 0 ? "{\"op\":1,\"d\":null}" : "{\"op\":1,\"d\":" + seq + "}";
        try {
            ws.sendText(hb, true);
        } catch (Exception ex) {
            Log.error("gateway.heartbeat_send_failed", ex);
            throw new RuntimeException(ex);
        }
    }

    private <T> void sendOpcode(int op, T data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            json.serialize(data, out);
            String payload = "{\"op\":" + op + ",\"d\":" + out.toString(StandardCharsets.UTF_8) + "}";
            socket.sendText(payload, true);
        } catch (Exception ex) {
            Log.error("gateway.send_failed", ex);
        }
    }

    private void startHeartbeat() {
        Thread hb = heartbeatThread;
        if (hb != null) {
            hb.interrupt();
        }

        heartbeatThread = Thread.ofVirtual().name("heartbeat").start(() -> {
            try {
                // initial jitter per discord docs
                Thread.sleep((long) (heartbeatInterval * Math.random()));
                while (running && !Thread.currentThread().isInterrupted()) {
                    if (!heartbeatAcked) {
                        Log.warn("gateway.heartbeat_timeout");
                        WebSocket ws = socket;
                        if (ws != null) {
                            try {
                                ws.sendClose(WebSocket.NORMAL_CLOSURE, "zombie");
                            } catch (Exception ex) {
                                Log.error("gateway.heartbeat_close_failed", ex);
                            }
                        }
                        return;
                    }
                    heartbeatAcked = false;
                    sendHeartbeat();
                    Thread.sleep(heartbeatInterval);
                }
            } catch (InterruptedException ex) {
                // shutdown
            }
        });
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }

        Thread.ofVirtual().name("reconnect").start(() -> {
            try {
                Thread.sleep(5000); // backoff
                if (running) {
                    String url = resumeGatewayUrl != null ? resumeGatewayUrl + "?v=10&encoding=json" : GATEWAY_URL;
                    doConnect(url);
                }
            } catch (InterruptedException ex) {
                // shutdown
            }
        });
    }

    public CompletableFuture<Void> awaitReady() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            while (sessionId == null && running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    future.completeExceptionally(ex);
                    return;
                }
            }
            future.complete(null);
        });
        return future;
    }
}
