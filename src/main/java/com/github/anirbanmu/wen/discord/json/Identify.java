package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

// opcode 2 identify payload - sent after receiving hello
@CompiledJson
public record Identify(String token, int intents, Properties properties, @JsonAttribute(nullable = true) Integer shard) {

    public static Identify create(String token, int intents) {
        return new Identify(token, intents, Properties.DEFAULT, null);
    }

    // connection properties for identify
    @CompiledJson
    public record Properties(String os, String browser, String device) {
        public static final Properties DEFAULT = new Properties("linux", "wen", "wen");
    }
}
