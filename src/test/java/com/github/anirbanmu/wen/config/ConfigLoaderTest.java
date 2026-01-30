package com.github.anirbanmu.wen.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void testLoadConfig() {
        String toml = """
            [[calendars]]
            keywords = ["f1", "formula1"]
            name = "Formula 1"
            url = "https://example.com/f1.ics"
            fallback = true

            [calendars.filters]
            r.contains = "grand prix"
            r.field = "description"
            q.contains = "qualifying"

            [[calendars]]
            keywords = ["office"]
            name = "Office Calendar"
            url = "https://example.com/work.ics"
            """;

        WenConfig config = ConfigLoader.load(toml);

        assertNotNull(config);
        assertEquals(2, config.calendars().size());

        // Check F1 Calendar
        Calendar f1 = config.calendars().get(0);
        assertEquals("Formula 1", f1.name());
        assertTrue(f1.fallback());
        assertEquals(2, f1.keywords().size());
        assertTrue(f1.keywords().contains("f1"));
        assertEquals("https://example.com/f1.ics", f1.url());

        assertEquals(2, f1.filters().size());
        Filter race = f1.filters().get("r");
        assertEquals("grand prix", race.contains());
        assertEquals(MatchField.DESCRIPTION, race.field());

        Filter quali = f1.filters().get("q");
        assertEquals("qualifying", quali.contains());
        assertEquals(MatchField.SUMMARY, quali.field()); // defaults to summary when not specified

        // prefilter should be null when not specified
        assertNull(f1.prefilter());

        // Check Office Calendar
        Calendar office = config.calendars().get(1);
        assertEquals("Office Calendar", office.name());
        assertFalse(office.fallback());
        assertEquals(1, office.keywords().size());
        assertEquals("office", office.keywords().get(0));
        assertNull(office.prefilter());
    }

    @Test
    void testPrefilter() {
        String toml = """
            [[calendars]]
            keywords = ["moto2"]
            name = "Moto2"
            url = "https://example.com/motogp.ics"

            [calendars.prefilter]
            contains = "moto2"
            field = "summary"
            """;

        WenConfig config = ConfigLoader.load(toml);
        Calendar moto2 = config.calendars().get(0);

        assertNotNull(moto2.prefilter());
        assertEquals("moto2", moto2.prefilter().contains());
        assertEquals(MatchField.SUMMARY, moto2.prefilter().field());
    }

    @Test
    void testMissingRequiredFields() {
        String toml = """
            [[calendars]]
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
    void testInvalidFilterFieldRejected() {
        String toml = """
            [[calendars]]
            keywords = ["f1"]
            name = "Formula 1"
            url = "https://example.com/f1.ics"

            [calendars.filters]
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
