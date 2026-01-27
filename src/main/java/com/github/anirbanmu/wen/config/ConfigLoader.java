package com.github.anirbanmu.wen.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        List<CalendarSource> sources = new ArrayList<>();
        if (!result.isArray("sources")) {
            throw new ConfigException("Configuration must contain a 'sources' array.");
        }

        for (Object obj : result.getArray("sources").toList()) {
            if (obj instanceof TomlTable table) {
                sources.add(parseSource(table));
            }
        }
        return new WenConfig(List.copyOf(sources));
    }

    private static CalendarSource parseSource(TomlTable table) {
        if (!table.isArray("keywords")) {
            throw new ConfigException("Source '" + getContext(table) + "' missing required 'keywords' array.");
        }

        List<String> keywords = new ArrayList<>();
        for (Object k : table.getArray("keywords").toList()) {
            keywords.add(k.toString());
        }

        if (keywords.isEmpty()) {
            throw new ConfigException("Source '" + getContext(table) + "' must have at least one keyword.");
        }

        String name = table.getString("name");
        if (name == null || name.isBlank()) {
            throw new ConfigException("Source missing required 'name' field (keywords=" + keywords + ")");
        }

        String url = table.getString("url");
        if (url == null || url.isBlank()) {
            throw new ConfigException("Source '" + name + "' missing required 'url' field.");
        }

        boolean isDefault = table.getBoolean("isDefault") != null && table.getBoolean("isDefault");

        String refreshStr = table.getString("refreshInterval");
        java.time.Duration refreshInterval = java.time.Duration.ofHours(6); // Default
        if (refreshStr != null) {
            try {
                // simple parsing for "PT1H", "PT30M" etc. Standard ISO-8601 duration
                refreshInterval = java.time.Duration.parse(refreshStr);
            } catch (Exception e) {
                throw new ConfigException("Source '" + name + "' has invalid 'refreshInterval': " + refreshStr);
            }
        }

        Map<String, EventMatcher> matchers = new HashMap<>();
        if (table.isTable("matchers")) {
            TomlTable matchersTable = table.getTable("matchers");
            for (String key : matchersTable.keySet()) {
                if (matchersTable.isTable(key)) {
                    matchers.put(key, parseMatcher(matchersTable.getTable(key), name + "." + key));
                }
            }
        }

        return new CalendarSource(List.copyOf(keywords), name, url, refreshInterval, Map.copyOf(matchers), isDefault);
    }

    private static EventMatcher parseMatcher(TomlTable table, String context) {
        String contains = table.getString("contains");
        if (contains == null) {
            throw new ConfigException("Matcher '" + context + "' missing required 'contains' field.");
        }

        String field = table.getString("field");
        return new EventMatcher(contains, Optional.ofNullable(field));
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
