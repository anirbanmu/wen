package com.github.anirbanmu.wen.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigFilterTest {

    private static final CalendarEvent F1_RACE = new CalendarEvent(
        "f1 grand Prix",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        "monaco",
        "monaco grand prix",
        List.of("racing", "f1"));

    @Test
    void testSummaryMatch() {
        Filter filter = new Filter("grand prix", MatchField.SUMMARY);
        assertTrue(filter.toPredicate().test(F1_RACE));
    }

    @Test
    void testSummaryNoMatch() {
        Filter filter = new Filter("nascar", MatchField.SUMMARY);
        assertFalse(filter.toPredicate().test(F1_RACE));
    }

    @Test
    void testLocationMatch() {
        Filter filter = new Filter("monaco", MatchField.LOCATION);
        assertTrue(filter.toPredicate().test(F1_RACE));
    }

    @Test
    void testCategoryMatch() {
        Filter filter = new Filter("f1", MatchField.CATEGORIES);
        assertTrue(filter.toPredicate().test(F1_RACE));
    }

    @Test
    void testCaseInsensitivity() {
        Filter filter = new Filter("GRAND", MatchField.SUMMARY);
        assertTrue(filter.toPredicate().test(F1_RACE));
    }

    @Test
    void testNullHandling() {
        CalendarEvent nullEvent = new CalendarEvent(null, Instant.now(), Instant.now(), null, null,
            Collections.emptyList());
        Filter filter = new Filter("anything", MatchField.SUMMARY);
        assertFalse(filter.toPredicate().test(nullEvent));
    }
}
