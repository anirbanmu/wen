package com.github.anirbanmu.wen.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record CalendarSource(List<String> keywords, String name, String url, Duration refreshInterval, Map<String, EventMatcher> matchers, boolean isDefault) {
}
