package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.ConfigLoader;
import com.github.anirbanmu.wen.config.WenConfig;
import com.github.anirbanmu.wen.discord.DiscordHttpClient;
import com.github.anirbanmu.wen.discord.DiscordResult;
import com.github.anirbanmu.wen.discord.Gateway;
import com.github.anirbanmu.wen.discord.json.Command;
import com.github.anirbanmu.wen.discord.json.Command.Option;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import com.github.anirbanmu.wen.log.Log;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class Main {
    public static void main(String[] args) {
        String token = System.getenv("DISCORD_TOKEN");
        String appId = System.getenv("DISCORD_APPLICATION_ID");

        if (token == null) {
            Log.error("startup.missing_token", "message", "DISCORD_TOKEN env var is required");
            System.exit(1);
        }
        if (appId == null) {
            Log.error("startup.missing_app_id", "message", "DISCORD_APPLICATION_ID env var is required");
            System.exit(1);
        }

        String configPathStr = System.getProperty("config", "config.toml");
        Path configPath = Path.of(configPathStr);
        if (!Files.exists(configPath)) {
            Log.error("startup.missing_config", "path", configPath.toAbsolutePath().toString());
            System.exit(1);
        }

        WenConfig config;
        try {
            config = ConfigLoader.load(configPath);
            Log.info("startup.config_loaded", "calendars", config.calendars().size());
        } catch (Exception e) {
            Log.error("startup.config_error", e);
            System.exit(1);
            return;
        }

        Log.info("bot_startup", "status", "starting", "version", "1.0.0");

        Map<String, CalendarFeed> feeds = new HashMap<>();
        Map<String, Calendar> calendarConfigs = new HashMap<>();
        for (Calendar calConfig : config.calendars()) {
            CalendarFeed feed = new CalendarFeed(calConfig.url(), calConfig.refreshInterval());
            for (String keyword : calConfig.keywords()) {
                feeds.put(keyword, feed);
                calendarConfigs.put(keyword, calConfig);
            }
        }

        DiscordHttpClient httpClient = new DiscordHttpClient(token);

        try {
            registerWenCommand(config, httpClient, appId);
        } catch (Exception e) {
            Log.error("startup.command_registration_failed", e);
            System.exit(1);
        }

        Processor processor = new Processor(calendarConfigs, feeds);

        Gateway gateway = new Gateway(token, interaction -> {
            long start = System.nanoTime();
            Log.info("interaction.received", "id", interaction.id());
            try {
                long procStart = System.nanoTime();
                InteractionResponse response = processor.process(interaction);
                long procMs = (System.nanoTime() - procStart) / 1_000_000;

                if (response != null) {
                    long netStart = System.nanoTime();
                    DiscordResult<Void> result = httpClient.respondToInteraction(interaction.id(), interaction.token(), response);
                    long netMs = (System.nanoTime() - netStart) / 1_000_000;
                    long totalMs = (System.nanoTime() - start) / 1_000_000;

                    if (result instanceof DiscordResult.Failure<Void> f) {
                        Log.error("interaction.response_failed", "error", f.message(), "proc_ms", procMs, "net_ms", netMs, "total_ms", totalMs);
                    } else {
                        Log.info("interaction.responded", "id", interaction.id(), "proc_ms", procMs, "net_ms", netMs, "total_ms", totalMs);
                    }
                }
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                Log.error("interaction.processing_error", e, "duration_ms", durationMs);
            }
        });

        int healthPort = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8080"));
        try {
            startHealthCheck(healthPort, gateway::isHealthy);
        } catch (Exception e) {
            Log.error("startup.health_server_failed", e);
            System.exit(1);
        }

        gateway.connect();

        // exit if unhealthy for a long time -- maybe something is really wrong?
        long unhealthyThresholdMs = Long.parseLong(System.getenv().getOrDefault("UNHEALTHY_THRESHOLD_MS", "600000")); // 10 min
        Thread.ofVirtual().name("watchdog").start(() -> {
            long unhealthySince = 0;
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (gateway.isHealthy()) {
                    unhealthySince = 0;
                } else {
                    long now = System.currentTimeMillis();
                    if (unhealthySince == 0) {
                        unhealthySince = now;
                        Log.warn("watchdog.unhealthy");
                    } else if (now - unhealthySince > unhealthyThresholdMs) {
                        Log.error("watchdog.exit", "unhealthy_ms", now - unhealthySince);
                        System.exit(1);
                    }
                }
            }
        });

        // keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void registerWenCommand(WenConfig config, DiscordHttpClient httpClient, String appId) {
        Option queryOption = new Option(
            "query",
            "Calendar and filter (e.g. 'f1 race')",
            Command.TYPE_STRING,
            false,
            null,
            true); // autocomplete enabled

        Command wenCommand = new Command(
            "wen",
            "When is the next event?",
            List.of(queryOption));

        DiscordResult<Void> result = httpClient.registerCommands(appId, List.of(wenCommand));

        switch (result) {
            case DiscordResult.Success<Void> _ -> Log.info("commands.registered");
            case DiscordResult.Failure<Void> f -> {
                Log.error("commands.registration_failed",
                    "message", f.message(),
                    "status", f.statusCode());
                if (f.exception() != null) {
                    throw new RuntimeException("Command registration failed", f.exception());
                } else {
                    throw new RuntimeException("Command registration failed: " + f.message());
                }
            }
        }
    }

    private static void startHealthCheck(int port, BooleanSupplier healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", exchange -> {
            boolean ok = healthy.getAsBoolean();
            int status = ok ? 200 : 503;
            byte[] body = (ok ? "ok" : "unhealthy").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        Log.info("health.started", "port", port);
    }
}
