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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Processor {
    private record CalendarContext(Calendar config, CalendarFeed feed, int color) {
    }

    private final Map<String, CalendarContext> contexts;

    public Processor(Map<String, Calendar> calendarConfigs, Map<String, CalendarFeed> feeds) {
        this.contexts = new HashMap<>();

        for (Map.Entry<String, Calendar> entry : calendarConfigs.entrySet()) {
            String key = entry.getKey();
            Calendar config = entry.getValue();
            CalendarFeed feed = feeds.get(key);

            if (feed != null) {
                int color = generateColor(config.name(), config.url());
                contexts.put(key, new CalendarContext(config, feed, color));
            }
        }
    }

    public InteractionResponse process(Interaction interaction) {
        if (interaction.type() != Interaction.TYPE_APPLICATION_COMMAND || interaction.data() == null) {
            return null;
        }

        Data data = interaction.data();
        if (!"wen".equals(data.name())) {
            return null;
        }

        String calendarKey = getOptionValue(data.options(), "calendar");
        String filterKey = getOptionValue(data.options(), "filter");

        CalendarContext ctx = null;

        if (calendarKey == null) {
            ctx = contexts.values().stream()
                .filter(c -> c.config().fallback())
                .findFirst()
                .orElse(null);

            if (ctx == null) {
                return InteractionResponse.message("Error: Missing calendar argument.");
            }
        } else {
            ctx = contexts.get(calendarKey.toLowerCase());
        }

        if (ctx == null) {
            return InteractionResponse.message("Unknown calendar: " + calendarKey);
        }

        Predicate<CalendarEvent> predicate;
        if (filterKey != null && !filterKey.isBlank()) {
            Filter namedFilter = ctx.config().filters().get(filterKey.toLowerCase());
            if (namedFilter != null) {
                predicate = namedFilter.toPredicate();
            } else {
                predicate = new Filter(filterKey, MatchField.SUMMARY).toPredicate();
            }
        } else {
            predicate = _ -> true;
        }

        QueryResult result = ctx.feed().query(predicate, 2);
        return formatResponse(ctx, result);
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

    private InteractionResponse formatResponse(CalendarContext ctx, QueryResult result) {
        if (result.current() == null && result.upcoming().isEmpty()) {
            return InteractionResponse.message("No upcoming events found for " + ctx.config().name());
        }

        List<String> keywords = ctx.config().keywords();
        StringBuilder desc = new StringBuilder();

        if (result.current() != null) {
            desc.append(formatLive(result.current(), keywords));
            if (!result.upcoming().isEmpty()) {
                desc.append("\n\n");
            }
        }

        for (int i = 0; i < result.upcoming().size(); i++) {
            if (i > 0) {
                desc.append("\n\n");
            }
            desc.append(formatUpcoming(result.upcoming().get(i), keywords));
        }

        String timestamp = java.time.Instant.now().toString();
        InteractionResponse.Footer footer = new InteractionResponse.Footer("wen?", null);
        InteractionResponse.Author author = new InteractionResponse.Author(ctx.config().name(), null);

        InteractionResponse.Embed embed = new InteractionResponse.Embed(
            null,
            desc.toString(),
            ctx.color(),
            null,
            timestamp,
            footer,
            author);

        return InteractionResponse.embeds(List.of(embed));
    }

    private static String formatLive(CalendarEvent event, List<String> keywords) {
        long endEpoch = event.end().getEpochSecond();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(cleanSummary(event.summary(), keywords)).append("**");

        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" · ").append(event.location());
        }

        sb.append("\nNow · ends <t:").append(endEpoch).append(":R>");

        return sb.toString();
    }

    // some calendars like to prefix with the keywords (hacky but oh well)
    private static String cleanSummary(String summary, List<String> keywords) {
        if (summary == null || keywords == null) {
            return summary;
        }
        String lower = summary.toLowerCase();
        for (String keyword : keywords) {
            String prefix = keyword.toLowerCase();
            // check for "keyword: " or "keyword " at start
            if (lower.startsWith(prefix + ": ")) {
                return summary.substring(prefix.length() + 2).trim();
            }
            if (lower.startsWith(prefix + " ")) {
                return summary.substring(prefix.length() + 1).trim();
            }
        }
        return summary;
    }

    private static final long SECONDS_IN_WEEK = 7 * 24 * 60 * 60;

    private static String formatUpcoming(CalendarEvent event, List<String> keywords) {
        long startEpoch = event.start().getEpochSecond();
        long secondsUntil = startEpoch - java.time.Instant.now().getEpochSecond();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(cleanSummary(event.summary(), keywords)).append("**");

        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" · ").append(event.location());
        }

        sb.append("\n<t:").append(startEpoch).append(":R>");

        if (secondsUntil > SECONDS_IN_WEEK) {
            sb.append(" · <t:").append(startEpoch).append(":f>");
        } else {
            sb.append(" · <t:").append(startEpoch).append(":t>");
        }

        return sb.toString();
    }

    private static int generateColor(String name, String url) {
        if (name == null || url == null) {
            return 0x00FF00;
        }

        int h = name.hashCode() ^ url.hashCode();

        int x = (h >>> 4) & 0xFF; // variable component

        return switch (Math.abs(h % 6)) {
            case 0 -> (0xFF << 16) | (x << 8) | 0x00;
            case 1 -> (0xFF << 16) | 0x00 | x;
            case 2 -> (x << 16) | (0xFF << 8) | 0x00;
            case 3 -> 0x00 | (0xFF << 8) | x;
            case 4 -> 0x00 | (x << 8) | 0xFF;
            default -> (x << 16) | 0x00 | 0xFF;
        };
    }
}
