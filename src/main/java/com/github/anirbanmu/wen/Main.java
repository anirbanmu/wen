package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.ConfigLoader;
import com.github.anirbanmu.wen.config.WenConfig;
import com.github.anirbanmu.wen.discord.CommandRegistrar;
import com.github.anirbanmu.wen.discord.DiscordHttpClient;
import com.github.anirbanmu.wen.discord.Gateway;
import com.github.anirbanmu.wen.log.Log;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
        for (Calendar calConfig : config.calendars()) {
            CalendarFeed feed = new CalendarFeed(calConfig.url(), calConfig.refreshInterval());
            for (String keyword : calConfig.keywords()) {
                feeds.put(keyword, feed);
            }
        }

        DiscordHttpClient httpClient = new DiscordHttpClient(token);

        // Register commands synchronously
        CommandRegistrar registrar = new CommandRegistrar(httpClient, appId);
        try {
            registrar.registerCommands(config);
        } catch (Exception e) {
            Log.error("startup.command_registration_failed", e);
            System.exit(1);
        }

        Gateway gateway = new Gateway(token, interaction -> {
            Log.info("interaction.received", "id", interaction.id());
        });

        gateway.connect();
        // Gateway.connect is async, but logs "gateway.connecting" inside.
        // We log started here.

        // keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
