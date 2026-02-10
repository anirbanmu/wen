package com.github.anirbanmu.wen.discord;

import com.github.anirbanmu.wen.discord.json.Command;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import com.github.anirbanmu.wen.log.Log;
import com.github.anirbanmu.wen.util.Http;
import com.github.anirbanmu.wen.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

public class DiscordHttpClient {
    private static final String BASE_URL = "https://discord.com/api/v10";
    private static final int MAX_BURST = 45;
    private static final int REFILL_MS = 22; // ~45 req/s
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(2500);
    private static final long KEEPALIVE_INTERVAL_MS = 270_000; // 4.5 min

    private static final HttpRequest KEEPALIVE_REQUEST = HttpRequest.newBuilder()
        .uri(URI.create(BASE_URL + "/gateway"))
        .timeout(REQUEST_TIMEOUT)
        .GET()
        .build();

    private final String token;
    private final Semaphore limiter;

    public DiscordHttpClient(String token) {
        this.token = token;
        this.limiter = new Semaphore(MAX_BURST);
        startRefillThread();
        startKeepaliveThread();
    }

    private void startRefillThread() {
        Thread.ofVirtual().name("rate-limiter-refill").start(() -> {
            while (true) {
                try {
                    Thread.sleep(REFILL_MS);
                    if (limiter.availablePermits() < MAX_BURST) {
                        limiter.release();
                    }
                } catch (InterruptedException e) {
                    // JVM shutting down or thread interrupted
                    break;
                } catch (Exception e) {
                    Log.error("rate_limiter.refill_error", e);
                }
            }
        });
    }

    private void startKeepaliveThread() {
        Thread.ofVirtual().name("http-keepalive").start(() -> {
            while (true) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                    int status = Http.CLIENT.send(KEEPALIVE_REQUEST, HttpResponse.BodyHandlers.discarding()).statusCode();
                    Log.info("http.keepalive", "status", status);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // keepalive failure is non-fatal
                }
            }
        });
    }

    public DiscordResult<Void> registerCommands(String applicationId, List<Command> commands) {
        String url = BASE_URL + "/applications/" + applicationId + "/commands";
        Log.info("http.register_commands", "url", url, "count", commands.size());

        return sendRequest(HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bot " + token)
            .header("Content-Type", "application/json")
            .PUT(bodyPublisher(commands)));
    }

    public DiscordResult<Void> respondToInteraction(String interactionId, String interactionToken, InteractionResponse response) {
        String url = BASE_URL + "/interactions/" + interactionId + "/" + interactionToken + "/callback";

        return sendRequest(HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(bodyPublisher(response)));
    }

    private HttpRequest.BodyPublisher bodyPublisher(Object data) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            Json.DSL.serialize(data, os);
            return HttpRequest.BodyPublishers.ofByteArray(os.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    private DiscordResult<Void> sendRequest(HttpRequest.Builder builder) {
        try {
            limiter.acquire();
            HttpResponse<Void> response = Http.CLIENT.send(
                builder.timeout(REQUEST_TIMEOUT).build(),
                HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                Log.error("http.request_failed", "status", response.statusCode());
                return new DiscordResult.Failure<>("Discord API error", response.statusCode());
            }
            return new DiscordResult.Success<>(null);
        } catch (IOException | InterruptedException e) {
            return new DiscordResult.Failure<>("HTTP request failed", e);
        }
    }
}
