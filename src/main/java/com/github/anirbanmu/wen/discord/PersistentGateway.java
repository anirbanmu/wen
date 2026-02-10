package com.github.anirbanmu.wen.discord;

import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.log.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// reconnect loop around Gateway. creates a fresh gateway each iteration,
// carries resume state across.
public class PersistentGateway {
    private static final String DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final long BASE_RECONNECT_DELAY_MS = 200;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private final String token;
    private final Consumer<Interaction> interactionHandler;
    private final ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Gateway current;

    public PersistentGateway(String token, Consumer<Interaction> interactionHandler) {
        this.token = token;
        this.interactionHandler = interactionHandler;
    }

    public void connect() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("gateway-loop").start(this::connectionLoop);
    }

    public void disconnect() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // close gateway first so no new interactions arrive
        Gateway gw = current;
        if (gw != null) {
            gw.disconnect();
        }
        // now drain any in-flight handlers
        handlerExecutor.shutdown();
        try {
            if (!handlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.warn("gateway.handler_timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isHealthy() {
        Gateway gw = current;
        return running.get() && gw != null && gw.isHealthy();
    }

    private void connectionLoop() {
        int attempt = 0;
        Gateway.ResumeState resume = null;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            String url = (resume != null && resume.canResume())
                ? resume.resumeGatewayUrl() + "?v=10&encoding=json"
                : DEFAULT_GATEWAY_URL;

            Gateway gw = new Gateway(token, url, interactionHandler, handlerExecutor, resume);
            current = gw;

            try {
                gw.connect();
                gw.awaitClosed();
                resume = gw.resumeState();
            } catch (Exception ex) {
                Log.error("gateway.connect_failed", ex);
                resume = null;
            } finally {
                gw.disconnect();
                current = null;
            }

            if (gw.wasReady()) {
                attempt = 0;
            }

            if (!running.get()) {
                break;
            }

            attempt++;
            long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << Math.min(attempt - 1, 8)), MAX_RECONNECT_DELAY_MS);
            Log.info("gateway.reconnect_scheduled", "attempt", attempt, "delay_ms", delay);

            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                break;
            }
        }
    }
}
