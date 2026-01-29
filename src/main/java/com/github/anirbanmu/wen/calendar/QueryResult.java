package com.github.anirbanmu.wen.calendar;

import java.util.List;
import java.util.Optional;

public record QueryResult(Optional<CalendarEvent> current, List<CalendarEvent> upcoming) {
}
