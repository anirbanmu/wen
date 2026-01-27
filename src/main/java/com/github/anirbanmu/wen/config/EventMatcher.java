package com.github.anirbanmu.wen.config;

import java.util.Optional;

public record EventMatcher(String contains, Optional<String> field) {
}
