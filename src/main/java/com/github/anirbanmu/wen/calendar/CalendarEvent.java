package com.github.anirbanmu.wen.calendar;

import java.time.Instant;

public record CalendarEvent(
    String summary,
    Instant start,
    Instant end,
    String location,
    String description
) {}
