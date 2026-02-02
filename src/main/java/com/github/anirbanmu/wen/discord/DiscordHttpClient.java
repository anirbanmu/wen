package com.github.anirbanmu.wen.discord;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import com.github.anirbanmu.wen.discord.json.Command;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import com.github.anirbanmu.wen.log.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Executors;

public class DiscordHttpClient {
    private static final String BASE_URL = "https://discord.com/api/v10";
    private final String token;
    private final HttpClient httpClient;
    private final DslJson<Object> json;

    public DiscordHttpClient(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        this.json = new DslJson<>(Settings.withRuntime().includeServiceLoader());
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
            json.serialize(data, os);
            return HttpRequest.BodyPublishers.ofByteArray(os.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    private DiscordResult<Void> sendRequest(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                Log.error("http.request_failed",
                    "status", response.statusCode(),
                    "body", response.body());
                return new DiscordResult.Failure<>("Discord API error", response.statusCode());
            }
            return new DiscordResult.Success<>(null);
        } catch (IOException | InterruptedException e) {
            return new DiscordResult.Failure<>("HTTP request failed", e);
        }
    }
}
