package com.github.anirbanmu.wen.calendar;

import java.util.List;

public record QueryResult(CalendarEvent current, List<CalendarEvent> upcoming) {
}
