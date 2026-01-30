package com.github.anirbanmu.wen.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void testLoadConfig() {
        String toml = """
            [[sources]]
            keywords = ["f1", "formula1"]
            name = "Formula 1"
            url = "https://example.com/f1.ics"
            isDefault = true

            [sources.matchers]
            r.contains = "grand prix"
            r.field = "description"
            q.contains = "qualifying"

            [[sources]]
            keywords = ["office"]
            name = "Office Calendar"
            url = "https://example.com/work.ics"
            """;

        WenConfig config = ConfigLoader.load(toml);

        assertNotNull(config);
        assertEquals(2, config.sources().size());

        // Check F1 Source
        CalendarSource f1 = config.sources().get(0);
        assertEquals("Formula 1", f1.name());
        assertTrue(f1.isDefault());
        assertEquals(2, f1.keywords().size());
        assertTrue(f1.keywords().contains("f1"));
        assertEquals("https://example.com/f1.ics", f1.url());

        assertEquals(2, f1.matchers().size());
        EventMatcher race = f1.matchers().get("r");
        assertEquals("grand prix", race.contains());
        assertEquals(Optional.of(MatchField.DESCRIPTION), race.field());

        EventMatcher quali = f1.matchers().get("q");
        assertEquals("qualifying", quali.contains());
        assertTrue(quali.field().isEmpty());

        // defaultMatcher should be empty when not specified
        assertTrue(f1.defaultMatcher().isEmpty());

        // Check Office Source
        CalendarSource office = config.sources().get(1);
        assertEquals("Office Calendar", office.name());
        assertFalse(office.isDefault());
        assertEquals(1, office.keywords().size());
        assertEquals("office", office.keywords().get(0));
        assertTrue(office.defaultMatcher().isEmpty());
    }

    @Test
    void testDefaultMatcher() {
        String toml = """
            [[sources]]
            keywords = ["moto2"]
            name = "Moto2"
            url = "https://example.com/motogp.ics"

            [sources.defaultMatcher]
            contains = "moto2"
            field = "summary"
            """;

        WenConfig config = ConfigLoader.load(toml);
        CalendarSource moto2 = config.sources().get(0);

        assertTrue(moto2.defaultMatcher().isPresent());
        assertEquals("moto2", moto2.defaultMatcher().get().contains());
        assertEquals(Optional.of(MatchField.SUMMARY), moto2.defaultMatcher().get().field());
    }

    @Test
    void testMissingRequiredFields() {
        String toml = """
            [[sources]]
            keywords = ["f1"]
            # name is missing
            url = "https://example.com/f1.ics"
            """;

        ConfigException exception = assertThrows(ConfigException.class, () -> {
            ConfigLoader.load(toml);
        });

        assertTrue(exception.getMessage().contains("missing required 'name'"),
            "Exception should mention missing name, got: " + exception.getMessage());
    }

    @Test
    void testInvalidMatcherFieldRejected() {
        String toml = """
            [[sources]]
            keywords = ["f1"]
            name = "Formula 1"
            url = "https://example.com/f1.ics"

            [sources.matchers]
            r.contains = "race"
            r.field = "foobar"
            """;

        ConfigException exception = assertThrows(ConfigException.class, () -> {
            ConfigLoader.load(toml);
        });

        assertTrue(exception.getMessage().contains("Unknown match field"),
            "Exception should mention unknown field, got: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("foobar"),
            "Exception should mention the invalid field value, got: " + exception.getMessage());
    }
}
