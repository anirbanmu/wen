package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.calendar.QueryResult;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.Filter;
import com.github.anirbanmu.wen.config.MatchField;
import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.discord.json.Interaction.Data;
import com.github.anirbanmu.wen.discord.json.Interaction.Option;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import com.github.anirbanmu.wen.discord.json.InteractionResponse.Embed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Processor {
    private final Map<String, Calendar> calendarConfigs;
    private final Map<String, CalendarFeed> feeds;

    public Processor(Map<String, Calendar> calendarConfigs, Map<String, CalendarFeed> feeds) {
        this.calendarConfigs = calendarConfigs;
        this.feeds = feeds;
    }

    public InteractionResponse process(Interaction interaction) {
        if (interaction.type() != Interaction.TYPE_APPLICATION_COMMAND || interaction.data() == null) {
            return null; // or error response
        }

        Data data = interaction.data();
        if (!"wen".equals(data.name())) {
            return null;
        }

        String calendarKey = getOptionValue(data.options(), "calendar");
        String filterKey = getOptionValue(data.options(), "filter");

        Calendar calendarConfig = null;
        CalendarFeed feed = null;

        if (calendarKey == null) {
            // check for fallback calendar
            calendarConfig = calendarConfigs.values().stream()
                .filter(Calendar::fallback)
                .findFirst()
                .orElse(null);

            if (calendarConfig != null) {
                // use first keyword to find feed
                if (!calendarConfig.keywords().isEmpty()) {
                    feed = feeds.get(calendarConfig.keywords().getFirst().toLowerCase());
                }
            }

            if (calendarConfig == null || feed == null) {
                return createMessageResponse("Error: Missing calendar argument.");
            }
        } else {
            calendarConfig = calendarConfigs.get(calendarKey.toLowerCase());
            feed = feeds.get(calendarKey.toLowerCase());
        }

        if (calendarConfig == null || feed == null) {
            return createMessageResponse("Unknown calendar: " + calendarKey);
        }

        Predicate<CalendarEvent> predicate;
        if (filterKey != null && !filterKey.isBlank()) {
            Filter namedFilter = calendarConfig.filters().get(filterKey.toLowerCase());
            if (namedFilter != null) {
                predicate = namedFilter.toPredicate();
            } else {
                // fallback: substring search on summary
                predicate = new Filter(filterKey, MatchField.SUMMARY).toPredicate();
            }
        } else {
            // no filter, show all
            predicate = _ -> true;
        }

        QueryResult result = feed.query(predicate, 3);
        return formatResponse(calendarConfig, result);
    }

    private String getOptionValue(List<Option> options, String name) {
        if (options == null) {
            return null;
        }
        return options.stream()
            .filter(o -> o.name().equals(name))
            .findFirst()
            .map(Option::value)
            .orElse(null);
    }

    private InteractionResponse createMessageResponse(String content) {
        return new InteractionResponse(
            InteractionResponse.TYPE_CHANNEL_MESSAGE_WITH_SOURCE,
            new InteractionResponse.Data(content, null, null));
    }

    private InteractionResponse formatResponse(Calendar config, QueryResult result) {
        List<Embed> embeds = new ArrayList<>();
        StringBuilder description = new StringBuilder();

        if (result.current() == null && result.upcoming().isEmpty()) {
            description.append("No upcoming events found.");
        } else {
            if (result.current() != null) {
                description.append("**Happening Now:** ").append(result.current().summary()).append("\n");
            }

            if (!result.upcoming().isEmpty()) {
                description.append("**Upcoming:**\n");
                for (CalendarEvent e : result.upcoming()) {
                    long timestamp = e.start().getEpochSecond();
                    // <t:TIMESTAMP:F> (full date time) or <t:TIMESTAMP:R> (relative)
                    description
                        .append(String.format("- **%s** <t:%d:R> (<t:%d:f>)\n", e.summary(), timestamp, timestamp));
                }
            }
        }

        embeds.add(new InteractionResponse.Embed(
            config.name(),
            description.toString(),
            0x00FF00, // greenish
            null));

        return new InteractionResponse(
            InteractionResponse.TYPE_CHANNEL_MESSAGE_WITH_SOURCE,
            new InteractionResponse.Data(null, embeds, null));
    }
}
