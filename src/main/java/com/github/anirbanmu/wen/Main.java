package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.ConfigLoader;
import com.github.anirbanmu.wen.config.WenConfig;
import com.github.anirbanmu.wen.discord.DiscordHttpClient;
import com.github.anirbanmu.wen.discord.DiscordResult;
import com.github.anirbanmu.wen.discord.Gateway;
import com.github.anirbanmu.wen.discord.json.Command;
import com.github.anirbanmu.wen.discord.json.Command.Choice;
import com.github.anirbanmu.wen.discord.json.Command.Option;
import com.github.anirbanmu.wen.log.Log;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

        try {
            registerWenCommand(config, httpClient, appId);
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

    private static void registerWenCommand(WenConfig config, DiscordHttpClient httpClient, String appId) {
        // generate choices:
        // 1. calendar name -> primary keyword ("Formula 1" -> "f1")
        // 2. all keywords -> primary keyword ("f1" -> "f1")
        Stream<Choice> nameChoices = config.calendars().stream()
            .map(c -> new Choice(c.name(), c.keywords().getFirst()));

        Stream<Choice> keywordChoices = config.calendars().stream()
            .flatMap(c -> c.keywords().stream().map(k -> new Choice(k, c.keywords().getFirst())));

        List<Choice> allChoices = Stream.concat(nameChoices, keywordChoices)
            .distinct() // remove duplicates
            .sorted((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()))
            .toList();

        // discord limit is 25 choices
        if (allChoices.size() > 25) {
            Log.warn("commands.too_many_choices", "count", allChoices.size(), "limit", 25);
            allChoices = allChoices.subList(0, 25);
        }

        Option calendarOption = new Option(
            "calendar",
            "Calendar to query (e.g. Formula 1)",
            Command.TYPE_STRING,
            true,
            allChoices);

        Option filterOption = new Option(
            "filter",
            "Filter events (e.g. race, gp, sprint, practice)",
            Command.TYPE_STRING,
            false,
            null);

        Command wenCommand = new Command(
            "wen",
            "When is the next event?",
            List.of(calendarOption, filterOption));

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
}
