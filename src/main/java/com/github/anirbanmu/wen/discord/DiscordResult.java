package com.github.anirbanmu.wen.discord;

public sealed interface DiscordResult<T> {
    record Success<T>(T value) implements DiscordResult<T> {
    }

    record Failure<T>(String message, int statusCode, Throwable exception) implements DiscordResult<T> {
        public Failure(String message) {
            this(message, -1, null);
        }

        public Failure(String message, Throwable exception) {
            this(message, -1, exception);
        }

        public Failure(String message, int statusCode) {
            this(message, statusCode, null);
        }
    }
}
