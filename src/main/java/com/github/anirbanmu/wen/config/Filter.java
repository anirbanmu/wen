package com.github.anirbanmu.wen.config;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import java.util.function.Predicate;

public record Filter(String contains, MatchField field) {
    public Filter {
        contains = contains.toLowerCase();
    }

    public Predicate<CalendarEvent> toPredicate() {
        return event -> switch (field) {
            case SUMMARY -> event.lowerSummary() != null && event.lowerSummary().contains(contains);
            case LOCATION -> event.lowerLocation() != null && event.lowerLocation().contains(contains);
            case DESCRIPTION -> event.lowerDescription() != null && event.lowerDescription().contains(contains);
            case CATEGORIES -> event.lowerCategories() != null && event.lowerCategories().stream()
                .anyMatch(c -> c.contains(contains));
        };
    }
}
