package com.github.anirbanmu.wen.calendar;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CalendarFeedTest {

    @Test
    public void testParseSanity() {
        // construct ics relative to "now"
        Instant now = Instant.now();
        DateTimeFormatter icalFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

        String pastStart = icalFmt.format(now.minus(Duration.ofDays(2)));
        String pastEnd = icalFmt.format(now.minus(Duration.ofDays(2)).plus(Duration.ofHours(1)));

        String futureStart = icalFmt.format(now.plus(Duration.ofDays(2)));
        String futureEnd = icalFmt.format(now.plus(Duration.ofDays(2)).plus(Duration.ofHours(1)));

        // recurring starts in past, expects future instances
        String recurringStart = icalFmt.format(now.minus(Duration.ofDays(10)));

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//wen//test
            BEGIN:VEVENT
            UID:past-1
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:past event
            END:VEVENT
            BEGIN:VEVENT
            UID:future-1
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:future event
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring-1
            DTSTAMP:%s
            DTSTART:%s
            DURATION:PT1H
            RRULE:FREQ=DAILY;COUNT=100
            SUMMARY:recurring event
            END:VEVENT
            END:VCALENDAR
            """.formatted(pastStart, pastStart, pastEnd,
            futureStart, futureStart, futureEnd,
            recurringStart, recurringStart);

        List<CalendarEvent> events = CalendarFeed.parse(ics, _ -> true);

        assertFalse(events.stream().anyMatch(e -> e.summary().equals("past event")),
            "past event (2 days ago) should be filtered");

        assertTrue(events.stream().anyMatch(e -> e.summary().equals("future event")),
            "future event (in 2 days) should be present");

        long recurringFutureCount = events.stream().filter(e -> e.summary().equals("recurring event")).count();
        assertTrue(recurringFutureCount > 0, "should have future instances of recurring event");

        // verify the first recurring event instances are actually in the future
        CalendarEvent firstRecurring = events.stream()
            .filter(e -> e.summary().equals("recurring event"))
            .min((a, b) -> a.start().compareTo(b.start()))
            .orElseThrow();

        assertFalse(firstRecurring.start().isBefore(now.minusSeconds(1)),
            "first recurring instance should not be in the past (allow 1s buffer for clock skew)");
    }

    @Test
    public void testQueryCurrentAndUpcoming() {
        Instant now = Instant.now();
        DateTimeFormatter icalFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

        // event happening RIGHT NOW (started 30 min ago, ends in 30 min)
        String currentStart = icalFmt.format(now.minus(Duration.ofMinutes(30)));
        String currentEnd = icalFmt.format(now.plus(Duration.ofMinutes(30)));

        // three future events at different times
        String future1Start = icalFmt.format(now.plus(Duration.ofHours(1)));
        String future1End = icalFmt.format(now.plus(Duration.ofHours(2)));
        String future2Start = icalFmt.format(now.plus(Duration.ofHours(3)));
        String future2End = icalFmt.format(now.plus(Duration.ofHours(4)));
        String future3Start = icalFmt.format(now.plus(Duration.ofHours(5)));
        String future3End = icalFmt.format(now.plus(Duration.ofHours(6)));

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//wen//test
            BEGIN:VEVENT
            UID:current-1
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:current event
            END:VEVENT
            BEGIN:VEVENT
            UID:future-1
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:first future
            END:VEVENT
            BEGIN:VEVENT
            UID:future-2
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:second future
            END:VEVENT
            BEGIN:VEVENT
            UID:future-3
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:third future
            END:VEVENT
            END:VCALENDAR
            """.formatted(
            currentStart, currentStart, currentEnd,
            future1Start, future1Start, future1End,
            future2Start, future2Start, future2End,
            future3Start, future3Start, future3End);

        List<CalendarEvent> events = CalendarFeed.parse(ics, _ -> true);
        assertEquals(4, events.size(), "should have 4 events (1 current + 3 future)");

        // test query with limit 1
        QueryResult result1 = CalendarFeed.query(events, _ -> true, 1);
        assertNotNull(result1.current(), "should detect current event");
        assertEquals("current event", result1.current().summary());
        assertEquals(1, result1.upcoming().size(), "should have 1 upcoming");
        assertEquals("first future", result1.upcoming().getFirst().summary());

        // test query with limit 2
        QueryResult result2 = CalendarFeed.query(events, _ -> true, 2);
        assertEquals(2, result2.upcoming().size());
        assertEquals("first future", result2.upcoming().get(0).summary());
        assertEquals("second future", result2.upcoming().get(1).summary());

        // test predicate filtering
        QueryResult filtered = CalendarFeed.query(events, e -> e.summary().contains("future"), 10);
        assertNull(filtered.current(), "current event doesn't match filter");
        assertEquals(3, filtered.upcoming().size(), "all 3 future events match");
    }

    @Test
    public void testQuerySortedOrder() {
        Instant now = Instant.now();
        DateTimeFormatter icalFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

        // add events OUT OF ORDER in ics
        String laterStart = icalFmt.format(now.plus(Duration.ofHours(5)));
        String laterEnd = icalFmt.format(now.plus(Duration.ofHours(6)));
        String soonerStart = icalFmt.format(now.plus(Duration.ofHours(1)));
        String soonerEnd = icalFmt.format(now.plus(Duration.ofHours(2)));

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//wen//test
            BEGIN:VEVENT
            UID:later
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:later event
            END:VEVENT
            BEGIN:VEVENT
            UID:sooner
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:sooner event
            END:VEVENT
            END:VCALENDAR
            """.formatted(laterStart, laterStart, laterEnd, soonerStart, soonerStart, soonerEnd);

        List<CalendarEvent> events = CalendarFeed.parse(ics, _ -> true);
        QueryResult result = CalendarFeed.query(events, _ -> true, 10);

        assertEquals(2, result.upcoming().size());
        assertEquals("sooner event", result.upcoming().get(0).summary(), "sooner event should be first");
        assertEquals("later event", result.upcoming().get(1).summary(), "later event should be second");
    }
}
