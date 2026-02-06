package com.github.anirbanmu.wen.calendar;

import java.time.Instant;
import java.util.List;

public record CalendarEvent(String summary, Instant start, Instant end, String location, String description, List<String> categories, String lowerSummary, String lowerLocation, String lowerDescription, List<String> lowerCategories) {

    public static CalendarEvent create(String summary, Instant start, Instant end, String location, String description, List<String> categories) {
        return new CalendarEvent(
            summary, start, end, location, description, categories,
            summary != null ? summary.toLowerCase() : null,
            location != null ? location.toLowerCase() : null,
            description != null ? description.toLowerCase() : null,
            categories != null ? categories.stream().map(String::toLowerCase).toList() : null);
    }
}
