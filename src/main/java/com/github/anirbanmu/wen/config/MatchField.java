package com.github.anirbanmu.wen.config;

public enum MatchField {
    SUMMARY, LOCATION, DESCRIPTION;

    public static MatchField fromString(String value) {
        return switch (value.toLowerCase()) {
            case "summary" -> SUMMARY;
            case "location" -> LOCATION;
            case "description" -> DESCRIPTION;
            default -> throw new IllegalArgumentException(
                "Unknown match field: '" + value + "'. Valid fields: summary, location, description");
        };
    }
}
