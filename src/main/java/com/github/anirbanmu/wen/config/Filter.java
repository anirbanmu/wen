package com.github.anirbanmu.wen.config;

import com.github.anirbanmu.wen.calendar.CalendarEvent;
import java.util.function.Predicate;

public record Filter(String contains, MatchField field) {
    public Predicate<CalendarEvent> toPredicate() {
        String lowerQuery = contains.toLowerCase();
        return event -> switch (field) {
            case SUMMARY -> event.summary() != null && event.summary().toLowerCase().contains(lowerQuery);
            case LOCATION -> event.location() != null && event.location().toLowerCase().contains(lowerQuery);
            case DESCRIPTION -> event.description() != null && event.description().toLowerCase().contains(lowerQuery);
            case CATEGORIES -> event.categories() != null && event.categories().stream()
                .anyMatch(c -> c.toLowerCase().contains(lowerQuery));
        };
    }
}
