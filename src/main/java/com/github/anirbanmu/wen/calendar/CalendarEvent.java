package com.github.anirbanmu.wen.calendar;

import java.time.Instant;
import java.util.List;

public record CalendarEvent(String summary, Instant start, Instant end, String location, String description, List<String> categories) {
}
