package com.github.anirbanmu.wen.discord;

import com.github.anirbanmu.wen.config.WenConfig;
import com.github.anirbanmu.wen.discord.json.Command;
import com.github.anirbanmu.wen.discord.json.Command.Choice;
import com.github.anirbanmu.wen.discord.json.Command.Option;
import com.github.anirbanmu.wen.log.Log;
import java.util.List;
import java.util.stream.Stream;

public class CommandRegistrar {
    private final DiscordHttpClient httpClient;
    private final String applicationId;

    public CommandRegistrar(DiscordHttpClient httpClient, String applicationId) {
        this.httpClient = httpClient;
        this.applicationId = applicationId;
    }

    public void registerCommands(WenConfig config) {
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

        httpClient.registerCommands(applicationId, List.of(wenCommand));
        Log.info("commands.registered");
    }
}
