package com.github.anirbanmu.wen.config;

import java.util.List;
import java.util.Map;

public record CalendarSource(
    List<String> keywords,
    String name,
    String url,
    Map<String, EventMatcher> matchers,
    boolean isDefault
) {}
