package com.github.anirbanmu.wen.calendar;

import com.github.anirbanmu.wen.log.Log;
import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class CalendarFeed {
    private final String url;
    private final Duration refreshInterval;
    private final Thread thread;
    private final HttpClient client;
    private volatile List<CalendarEvent> events = Collections.emptyList();

    public CalendarFeed(String url, Duration refreshInterval) {
        this.url = url;
        this.refreshInterval = refreshInterval;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.thread = Thread.ofVirtual().name("calendar-" + url.hashCode()).unstarted(this::runLoop);
        this.thread.start();
    }

    public List<CalendarEvent> getEvents() {
        return events;
    }

    private void runLoop() {
        while (true) {
            try {
                refresh();
                Thread.sleep(refreshInterval.toMillis());
            } catch (InterruptedException e) {
                Log.info("calendar_feed_interrupted", "url", url);
                break;
            } catch (Exception e) {
                Log.error("calendar_refresh_error", "url", url, "error", e.getMessage());
                try {
                    // backoff on error
                    Thread.sleep(Math.min(refreshInterval.toMillis(), 60000));
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void refresh() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            Log.error("calendar_fetch_failed", "url", url, "status", response.statusCode());
            return;
        }

        List<CalendarEvent> newEvents = parse(response.body());
        this.events = List.copyOf(newEvents);
        Log.info("calendar_refreshed", "url", url, "count", newEvents.size());
    }

    static List<CalendarEvent> parse(String body) {
        ICalendar ical = Biweekly.parse(body).first();
        if (ical == null) {
            return Collections.emptyList();
        }

        List<CalendarEvent> newEvents = new ArrayList<>();
        Instant now = Instant.now();
        Instant maxLookahead = now.plus(Duration.ofDays(365));
        TimeZone timeZone = TimeZone.getTimeZone("UTC");

        for (VEvent event : ical.getEvents()) {
            Date start = event.getDateStart().getValue();
            if (start == null) continue; // skip invalid events without start date

            Duration duration = calculateDuration(event);

            DateIterator iterator = event.getDateIterator(timeZone);
            if (iterator == null) {
                // non-recurring
                if (start.toInstant().isAfter(now)) {
                    newEvents.add(createEvent(event, start.toInstant(), duration));
                }
            } else {
                // recurring
                iterator.advanceTo(Date.from(now));
                boolean addedAny = false;
                while (iterator.hasNext()) {
                    Date nextStart = iterator.next();
                    if (nextStart.toInstant().isAfter(maxLookahead) && addedAny) {
                        break;
                    }
                    newEvents.add(createEvent(event, nextStart.toInstant(), duration));
                    addedAny = true;
                }
            }
        }
        return newEvents;
    }

    private static Duration calculateDuration(VEvent event) {
        if (event.getDuration() != null) {
            return Duration.ofMillis(event.getDuration().getValue().toMillis());
        }
        if (event.getDateEnd() != null && event.getDateStart() != null) {
            long start = event.getDateStart().getValue().getTime();
            long end = event.getDateEnd().getValue().getTime();
            return Duration.ofMillis(end - start);
        }
        // default duration if missing
        return Duration.ZERO;
    }

    private static CalendarEvent createEvent(VEvent event, Instant start, Duration duration) {
        Instant end = start.plus(duration);
        String summary = event.getSummary() != null ? event.getSummary().getValue() : "No Title";
        String location = event.getLocation() != null ? event.getLocation().getValue() : null;
        String description = event.getDescription() != null ? event.getDescription().getValue() : null;

        return new CalendarEvent(summary, start, end, location, description);
    }
}
