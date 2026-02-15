package com.github.anirbanmu.wen.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public class ConfigLoader {
    public static WenConfig load(Path path) throws IOException {
        TomlParseResult result = Toml.parse(path);
        return parse(result);
    }

    public static WenConfig load(InputStream stream) throws IOException {
        TomlParseResult result = Toml.parse(stream);
        return parse(result);
    }

    public static WenConfig load(String content) {
        TomlParseResult result = Toml.parse(content);
        return parse(result);
    }

    private static WenConfig parse(TomlParseResult result) {
        if (result.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse TOML configuration:\n");
            result.errors().forEach(error -> sb.append("- ").append(error.toString()).append("\n"));
            throw new ConfigException(sb.toString());
        }

        List<Calendar> calendars = new ArrayList<>();
        if (!result.isArray("calendars")) {
            throw new ConfigException("Configuration must contain a 'calendars' array.");
        }

        for (Object obj : result.getArray("calendars").toList()) {
            if (obj instanceof TomlTable table) {
                calendars.add(parseCalendar(table));
            }
        }
        return new WenConfig(List.copyOf(calendars));
    }

    private static Calendar parseCalendar(TomlTable table) {
        if (!table.isArray("keywords")) {
            throw new ConfigException("Calendar '" + getContext(table) + "' missing required 'keywords' array.");
        }

        List<String> keywords = new ArrayList<>();
        for (Object k : table.getArray("keywords").toList()) {
            keywords.add(k.toString());
        }

        if (keywords.isEmpty()) {
            throw new ConfigException("Calendar '" + getContext(table) + "' must have at least one keyword.");
        }

        String name = table.getString("name");
        if (name == null || name.isBlank()) {
            throw new ConfigException("Calendar missing required 'name' field (keywords=" + keywords + ")");
        }

        String url = table.getString("url");
        if (url == null || url.isBlank()) {
            throw new ConfigException("Calendar '" + name + "' missing required 'url' field.");
        }

        boolean fallback = table.getBoolean("fallback") != null && table.getBoolean("fallback");

        String refreshStr = table.getString("refreshInterval");
        java.time.Duration refreshInterval = java.time.Duration.ofHours(6); // Default
        if (refreshStr != null) {
            try {
                // simple parsing for "PT1H", "PT30M" etc. Standard ISO-8601 duration
                refreshInterval = java.time.Duration.parse(refreshStr);
            } catch (Exception e) {
                throw new ConfigException("Calendar '" + name + "' has invalid 'refreshInterval': " + refreshStr);
            }
        }

        Map<String, Filter> filters = new HashMap<>();
        if (table.isTable("filters")) {
            TomlTable filtersTable = table.getTable("filters");
            for (String key : filtersTable.keySet()) {
                if (filtersTable.isTable(key)) {
                    filters.put(key, parseFilter(filtersTable.getTable(key), name + "." + key));
                }
            }
        }

        Filter prefilter = null;
        if (table.isTable("prefilter")) {
            prefilter = parseFilter(table.getTable("prefilter"), name + ".prefilter");
        }

        String source = table.getString("source");
        if (source != null && source.isBlank()) {
            source = null;
        }

        return new Calendar(List.copyOf(keywords), name, url, refreshInterval, Map.copyOf(filters),
            prefilter, fallback, source);
    }

    private static Filter parseFilter(TomlTable table, String context) {
        String contains = table.getString("contains");
        if (contains == null) {
            throw new ConfigException("Filter '" + context + "' missing required 'contains' field.");
        }

        String fieldStr = table.getString("field");
        MatchField field = MatchField.SUMMARY; // default to summary if not specified
        if (fieldStr != null) {
            try {
                field = MatchField.fromString(fieldStr);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("Filter '" + context + "': " + e.getMessage());
            }
        }
        return new Filter(contains.toLowerCase(), field);
    }

    private static String getContext(TomlTable table) {
        // best effort to identify the table for error messages
        String name = table.getString("name");
        if (name != null) {
            return name;
        }
        return "unknown";
    }
}
