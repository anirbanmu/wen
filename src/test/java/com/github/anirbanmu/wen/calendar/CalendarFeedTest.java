package com.github.anirbanmu.wen.calendar;

import com.github.anirbanmu.wen.config.CalendarSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
                """.formatted(
                        pastStart, pastStart, pastEnd,
                        futureStart, futureStart, futureEnd,
                        recurringStart, recurringStart
                );

        List<CalendarEvent> events = CalendarFeed.parse(ics);

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

        assertFalse(firstRecurring.start().isBefore(now.minusSeconds(1)), "first recurring instance should not be in the past (allow 1s buffer for clock skew)");
    }
}
