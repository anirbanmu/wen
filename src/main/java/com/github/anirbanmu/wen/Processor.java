package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.calendar.QueryResult;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.Filter;
import com.github.anirbanmu.wen.config.MatchField;
import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.discord.json.Interaction.Option;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class Processor {
    private record CalendarContext(Calendar config, CalendarFeed feed, int color) {
    }

    private record ParsedQuery(CalendarContext calendar, Predicate<CalendarEvent> filter, String error) {
        static ParsedQuery success(CalendarContext cal, Predicate<CalendarEvent> filter) {
            return new ParsedQuery(cal, filter, null);
        }

        static ParsedQuery error(String message) {
            return new ParsedQuery(null, null, message);
        }

        boolean isHelp() {
            return calendar == null && "help".equals(error);
        }
    }

    private final Map<String, CalendarContext> contexts;
    private final CalendarContext fallback;
    private final List<String> allSuggestions; // sorted: shortest first, then alpha
    private final InteractionResponse helpResponse;

    public Processor(Map<String, Calendar> calendarConfigs, Map<String, CalendarFeed> feeds) {
        this.contexts = new HashMap<>();
        CalendarContext foundFallback = null;
        Set<String> suggestions = new HashSet<>();

        for (Map.Entry<String, Calendar> entry : calendarConfigs.entrySet()) {
            Calendar config = entry.getValue();
            CalendarFeed feed = feeds.get(entry.getKey());

            if (feed != null) {
                int color = generateColor(config.name(), config.url());
                CalendarContext ctx = new CalendarContext(config, feed, color);

                for (String keyword : config.keywords()) {
                    String key = keyword.toLowerCase();
                    contexts.put(key, ctx);
                    suggestions.add(key);
                    // keyword + filter combos
                    for (String filterKey : config.filters().keySet()) {
                        suggestions.add(key + " " + filterKey);
                    }
                }

                if (config.name() != null) {
                    String nameKey = config.name().toLowerCase();
                    contexts.put(nameKey, ctx);
                    suggestions.add(nameKey);
                    // name + filter combos
                    for (String filterKey : config.filters().keySet()) {
                        suggestions.add(nameKey + " " + filterKey);
                    }
                }

                if (config.fallback() && foundFallback == null) {
                    foundFallback = ctx;
                }
            }
        }

        this.fallback = foundFallback;

        List<String> sorted = new ArrayList<>(suggestions);
        sorted.sort((a, b) -> {
            if (a.length() != b.length()) {
                return a.length() - b.length();
            }
            return a.compareTo(b);
        });
        this.allSuggestions = List.copyOf(sorted);
        this.helpResponse = buildHelpResponse(contexts.values());
    }

    private static InteractionResponse buildHelpResponse(Collection<CalendarContext> contexts) {
        // dedupe calendars (same calendar indexed by multiple keywords)
        Set<Calendar> seen = new HashSet<>();
        List<CalendarContext> unique = new ArrayList<>();
        for (CalendarContext ctx : contexts) {
            if (seen.add(ctx.config())) {
                unique.add(ctx);
            }
        }

        StringBuilder desc = new StringBuilder();
        desc.append("**Available calendars:**\n\n");

        for (CalendarContext ctx : unique) {
            Calendar config = ctx.config();
            desc.append("**").append(config.name()).append("**");
            if (!config.keywords().isEmpty()) {
                desc.append(" · `").append(String.join("`, `", config.keywords())).append("`");
            }
            desc.append("\n");

            if (!config.filters().isEmpty()) {
                desc.append("filters: `").append(String.join("`, `", config.filters().keySet())).append("`\n");
            }
            desc.append("\n");
        }

        desc.append("**Usage:** `/wen <calendar> [filter]`\n");
        desc.append("Example: `/wen f1 race`");

        InteractionResponse.Embed embed = new InteractionResponse.Embed(
            "wen help",
            desc.toString(),
            0x5865F2,
            null,
            null,
            null,
            null);

        return InteractionResponse.embeds(List.of(embed));
    }

    public InteractionResponse process(Interaction interaction) {
        if (interaction.data() == null || !"wen".equals(interaction.data().name())) {
            return null;
        }

        return switch (interaction.type()) {
            case Interaction.TYPE_APPLICATION_COMMAND -> processCommand(interaction);
            case Interaction.TYPE_APPLICATION_COMMAND_AUTOCOMPLETE -> processAutocomplete(interaction);
            default -> null;
        };
    }

    private InteractionResponse processCommand(Interaction interaction) {
        String query = getOptionValue(interaction.data().options(), "query");

        ParsedQuery parsed = parseQuery(query);

        if (parsed.isHelp()) {
            return helpResponse;
        }

        if (parsed.error() != null) {
            return InteractionResponse.message(parsed.error());
        }

        QueryResult result = parsed.calendar().feed().query(parsed.filter(), 2);
        return formatResponse(parsed.calendar(), result);
    }

    private String getOptionValue(List<Option> options, String name) {
        if (options == null) {
            return null;
        }
        for (Option o : options) {
            if (o.name().equals(name)) {
                return o.value();
            }
        }
        return null;
    }

    private ParsedQuery parseQuery(String query) {
        // empty -> fallback or help
        if (query == null || query.isBlank()) {
            return fallback != null ? ParsedQuery.success(fallback, _ -> true) : ParsedQuery.error("help");
        }

        String q = query.strip().toLowerCase();

        if ("help".equals(q)) {
            return ParsedQuery.error("help");
        }

        // exact match (calendar keyword or name)
        CalendarContext ctx = contexts.get(q);
        if (ctx != null) {
            return ParsedQuery.success(ctx, _ -> true);
        }

        // prefix match (calendar + filter)
        for (String key : contexts.keySet()) {
            if (q.startsWith(key + " ")) {
                ctx = contexts.get(key);
                String filterPart = q.substring(key.length() + 1);
                return ParsedQuery.success(ctx, resolveFilter(ctx, filterPart));
            }
        }

        return ParsedQuery.error("Unknown calendar: " + query.strip());
    }

    private Predicate<CalendarEvent> resolveFilter(CalendarContext ctx, String filterText) {
        if (filterText == null || filterText.isBlank()) {
            return _ -> true;
        }
        Filter namedFilter = ctx.config().filters().get(filterText.toLowerCase());
        if (namedFilter != null) {
            return namedFilter.toPredicate();
        }
        return new Filter(filterText, MatchField.SUMMARY).toPredicate();
    }

    private InteractionResponse processAutocomplete(Interaction interaction) {
        String query = getFocusedOptionValue(interaction.data().options());
        String q = query == null ? "" : query.strip().toLowerCase();

        List<InteractionResponse.Choice> choices = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // pass 1: prefix matches
        for (String s : allSuggestions) {
            if (s.startsWith(q) && seen.add(s)) {
                choices.add(new InteractionResponse.Choice(s, s));
                if (choices.size() >= 25) {
                    break;
                }
            }
        }

        // pass 2: substring matches
        if (choices.size() < 25) {
            for (String s : allSuggestions) {
                if (s.contains(q) && seen.add(s)) {
                    choices.add(new InteractionResponse.Choice(s, s));
                    if (choices.size() >= 25) {
                        break;
                    }
                }
            }
        }

        return InteractionResponse.autocomplete(choices);
    }

    private String getFocusedOptionValue(List<Option> options) {
        if (options == null) {
            return null;
        }
        for (Option o : options) {
            if (Boolean.TRUE.equals(o.focused())) {
                return o.value();
            }
        }
        return null;
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

    // strip keyword prefix from summary (some calendars prefix events with their name)
    private static String cleanSummary(String summary, List<String> keywords) {
        if (summary == null || keywords == null) {
            return summary;
        }
        String lower = summary.toLowerCase();
        for (String keyword : keywords) {
            String prefix = keyword.toLowerCase();
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
        int x = (h >>> 4) & 0xFF;

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
