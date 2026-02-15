package com.github.anirbanmu.wen;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.calendar.QueryResult;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.config.Filter;
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
    private record CalendarContext(Calendar config, CalendarFeed feed, int color, List<String> summaryPrefixes) {
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

    private static final String[] SUMMARY_SEPARATORS = {" | ", ": ", " - ", " "};

    private final Map<String, CalendarContext> contexts;
    private final CalendarContext fallback;
    private final List<String> allSuggestions;
    private final InteractionResponse helpResponse;

    public Processor(Map<String, Calendar> calendarConfigs, Map<String, CalendarFeed> feeds) {
        this.contexts = new HashMap<>();
        CalendarContext foundFallback = null;
        Set<String> suggestions = new HashSet<>();

        for (Map.Entry<String, Calendar> entry : calendarConfigs.entrySet()) {
            Calendar config = entry.getValue();
            CalendarFeed feed = feeds.get(entry.getKey());

            if (feed == null) {
                continue;
            }

            int color = generateColor(config.name(), config.url());
            List<String> summaryPrefixes = buildSummaryPrefixes(config);
            CalendarContext ctx = new CalendarContext(config, feed, color, summaryPrefixes);

            // index by keyword (slugified) and slugified name
            for (String keyword : config.keywords()) {
                String key = slugify(keyword);
                contexts.put(key, ctx);
                suggestions.add(key);
                for (String filterKey : config.filters().keySet()) {
                    suggestions.add(key + " " + filterKey);
                }
            }

            if (config.name() != null) {
                String nameSlug = slugify(config.name());
                if (!contexts.containsKey(nameSlug)) {
                    contexts.put(nameSlug, ctx);
                    suggestions.add(nameSlug);
                    for (String filterKey : config.filters().keySet()) {
                        suggestions.add(nameSlug + " " + filterKey);
                    }
                }
            }

            if (config.fallback() && foundFallback == null) {
                foundFallback = ctx;
            }
        }

        this.fallback = foundFallback;

        suggestions.add("help");

        // shortest first, then alphabetical — best autocomplete UX
        List<String> sorted = new ArrayList<>(suggestions);
        sorted.sort((a, b) -> a.length() != b.length() ? a.length() - b.length() : a.compareTo(b));
        this.allSuggestions = List.copyOf(sorted);
        this.helpResponse = buildHelpResponse(contexts.values());
    }

    private static InteractionResponse buildHelpResponse(Collection<CalendarContext> contexts) {
        Set<Calendar> seen = new HashSet<>();
        List<CalendarContext> unique = new ArrayList<>();
        for (CalendarContext ctx : contexts) {
            if (seen.add(ctx.config())) {
                unique.add(ctx);
            }
        }

        StringBuilder desc = new StringBuilder();
        desc.append("**Usage:** `/wen <calendar> [filter]`\n");
        desc.append("Examples: `/wen f1 sprint` · `/wen wrc monaco`\n\n");
        desc.append("───\n\n");
        desc.append("**Available calendars:**\n");

        for (CalendarContext ctx : unique) {
            Calendar config = ctx.config();
            desc.append("\n**").append(config.name()).append("**");
            if (!config.keywords().isEmpty()) {
                desc.append(" · `").append(String.join("`, `", config.keywords())).append("`");
            }
            desc.append("\n");

            if (!config.filters().isEmpty()) {
                desc.append("filters: `").append(String.join("`, `", config.filters().keySet())).append("`\n");
            }

            if (config.source() != null) {
                String domain = config.source().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
                desc.append("calendar from [").append(domain).append("](").append(config.source()).append(")\n");
            }

            desc.append("\n");
        }

        desc.append("───\n");
        desc.append("bot source · [github.com/anirbanmu/wen](https://github.com/anirbanmu/wen)");

        return InteractionResponse.ephemeralEmbeds(List.of(new InteractionResponse.Embed(
            "wen help", desc.toString(), 0x5865F2, null, null, null, null)));
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

    private static String getOptionValue(List<Option> options, String name) {
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
        // empty -> fallback calendar or help
        if (query == null || query.isBlank()) {
            return fallback != null ? ParsedQuery.success(fallback, _ -> true) : ParsedQuery.error("help");
        }

        String q = query.strip().toLowerCase();

        if ("help".equals(q)) {
            return ParsedQuery.error("help");
        }

        CalendarContext ctx = contexts.get(q);
        if (ctx != null) {
            return ParsedQuery.success(ctx, _ -> true);
        }

        // split on first space: "f1 sprint" -> key="f1", filter="sprint"
        // works because all context keys are spaceless (slugified)
        int space = q.indexOf(' ');
        if (space > 0) {
            ctx = contexts.get(q.substring(0, space));
            if (ctx != null) {
                String filterPart = q.substring(space + 1);
                return ParsedQuery.success(ctx, resolveFilter(ctx, filterPart));
            }
        }

        return ParsedQuery.error("Unknown calendar: " + query.strip());
    }

    // named filter -> free-text fallback
    private static Predicate<CalendarEvent> resolveFilter(CalendarContext ctx, String filterText) {
        if (filterText == null || filterText.isBlank()) {
            return _ -> true;
        }
        Filter namedFilter = ctx.config().filters().get(filterText.toLowerCase());
        if (namedFilter != null) {
            return namedFilter.toPredicate();
        }
        String q = filterText.toLowerCase();
        return event -> (event.lowerSummary() != null && event.lowerSummary().contains(q)) ||
            (event.lowerLocation() != null && event.lowerLocation().contains(q)) ||
            (event.lowerDescription() != null && event.lowerDescription().contains(q));
    }

    // autocomplete: prefix matches -> substring matches, shortest first
    private InteractionResponse processAutocomplete(Interaction interaction) {
        String query = getFocusedOptionValue(interaction.data().options());
        String q = query == null ? "" : query.toLowerCase();

        List<InteractionResponse.Choice> choices = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // prefix matches
        for (String s : allSuggestions) {
            if (s.startsWith(q) && seen.add(s)) {
                choices.add(new InteractionResponse.Choice(s, s));
                if (choices.size() >= 25) {
                    return InteractionResponse.autocomplete(choices);
                }
            }
        }

        // substring matches
        for (String s : allSuggestions) {
            if (s.contains(q) && seen.add(s)) {
                choices.add(new InteractionResponse.Choice(s, s));
                if (choices.size() >= 25) {
                    return InteractionResponse.autocomplete(choices);
                }
            }
        }

        return InteractionResponse.autocomplete(choices);
    }

    private static String getFocusedOptionValue(List<Option> options) {
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

        List<String> prefixes = ctx.summaryPrefixes();
        StringBuilder desc = new StringBuilder();

        if (result.current() != null) {
            desc.append(formatLive(result.current(), prefixes));
            if (!result.upcoming().isEmpty()) {
                desc.append("\n\n");
            }
        }

        for (int i = 0; i < result.upcoming().size(); i++) {
            if (i > 0) {
                desc.append("\n\n");
            }
            desc.append(formatUpcoming(result.upcoming().get(i), prefixes));
        }

        String timestamp = java.time.Instant.now().toString();

        return InteractionResponse.embeds(List.of(new InteractionResponse.Embed(
            null, desc.toString(), ctx.color(), null, timestamp,
            new InteractionResponse.Footer("wen?", null),
            new InteractionResponse.Author(ctx.config().name(), null))));
    }

    private static String formatLive(CalendarEvent event, List<String> prefixes) {
        long endEpoch = event.end().getEpochSecond();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(cleanSummary(event.summary(), event.lowerSummary(), prefixes)).append("**");

        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" · ").append(event.location());
        }

        sb.append("\nNow · ends <t:").append(endEpoch).append(":R>");

        return sb.toString();
    }

    private static final long SECONDS_IN_WEEK = 7 * 24 * 60 * 60;

    private static String formatUpcoming(CalendarEvent event, List<String> prefixes) {
        long startEpoch = event.start().getEpochSecond();
        long secondsUntil = startEpoch - java.time.Instant.now().getEpochSecond();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(cleanSummary(event.summary(), event.lowerSummary(), prefixes)).append("**");

        if (event.location() != null && !event.location().isBlank()) {
            sb.append(" · ").append(event.location());
        }

        sb.append("\n<t:").append(startEpoch).append(":R>");
        sb.append(" · <t:").append(startEpoch).append(secondsUntil > SECONDS_IN_WEEK ? ":f>" : ":t>");

        return sb.toString();
    }

    // strip calendar prefix from summary, longest match first
    static String cleanSummary(String summary, String lowerSummary, List<String> prefixes) {
        if (summary == null || prefixes == null) {
            return summary;
        }
        for (String prefix : prefixes) {
            for (String sep : SUMMARY_SEPARATORS) {
                String full = prefix + sep;
                if (lowerSummary.startsWith(full)) {
                    return summary.substring(full.length()).trim();
                }
            }
        }
        return summary;
    }

    // "🏎️ NASCAR Cup Series" -> "nascar-cup-series", "f1" -> "f1"
    static String slugify(String value) {
        return value.toLowerCase()
            .replaceAll("[^\\p{L}\\p{N}\\s-]", "")
            .strip()
            .replaceAll("\\s+", "-");
    }

    // prefixes for summary stripping, longest first
    // "🏁 NASCAR Cup Series" keywords=["nascar"] -> ["nascar cup series", "nascar cup", "nascar"]
    static List<String> buildSummaryPrefixes(Calendar config) {
        List<String> prefixes = new ArrayList<>();

        if (config.name() != null) {
            String cleaned = config.name().toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "")
                .strip();

            if (!cleaned.isEmpty()) {
                String[] words = cleaned.split("\\s+");
                for (int end = words.length; end >= 1; end--) {
                    prefixes.add(String.join(" ", List.of(words).subList(0, end)));
                }
            }
        }

        // keywords too — "wsbk" matches "WSBK | ..." even though name is "World Superbikes"
        for (String kw : config.keywords()) {
            prefixes.add(kw.toLowerCase());
        }

        // dedupe and sort longest first for greedy matching
        return prefixes.stream().distinct()
            .sorted((a, b) -> b.length() - a.length())
            .toList();
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
