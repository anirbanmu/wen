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
import java.awt.Color;
import java.util.ArrayList;
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
                int color = generateColor(config.name());
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

        QueryResult result = ctx.feed().query(predicate, 3);
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

        List<InteractionResponse.Field> fields = new ArrayList<>();

        if (result.current() != null) {
            fields.add(new InteractionResponse.Field(
                "**Happening Now: " + result.current().summary() + "**",
                formatLive(result.current()),
                false));
        }

        for (CalendarEvent e : result.upcoming()) {
            fields.add(new InteractionResponse.Field(
                "**" + e.summary() + "**",
                formatUpcoming(e),
                false));
        }

        String timestamp = java.time.Instant.now().toString();
        InteractionResponse.Footer footer = new InteractionResponse.Footer("wen?", null);

        InteractionResponse.Embed embed = new InteractionResponse.Embed(
            ctx.config().name(),
            null,
            ctx.color(),
            fields,
            timestamp,
            footer);

        return InteractionResponse.embeds(List.of(embed));
    }

    private static String formatLive(CalendarEvent event) {
        StringBuilder sb = new StringBuilder();
        long endEpoch = event.end().getEpochSecond();

        sb.append("Ends <t:").append(endEpoch).append(":R>");
        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" • ").append(event.location());
        }
        return sb.toString();
    }

    private static String formatUpcoming(CalendarEvent event) {
        StringBuilder sb = new StringBuilder();
        long startEpoch = event.start().getEpochSecond();

        sb.append("<t:").append(startEpoch).append(":R>")
            .append(" • ")
            .append("<t:").append(startEpoch).append(":f>");

        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" • ").append(event.location());
        }
        return sb.toString();
    }

    private static int generateColor(String seed) {
        if (seed == null) {
            return 0x00FF00;
        }
        int hash = seed.hashCode();
        float hue = Math.abs(hash % 360) / 360f;
        return Color.HSBtoRGB(hue, 0.8f, 0.9f) & 0xFFFFFF;
    }
}
