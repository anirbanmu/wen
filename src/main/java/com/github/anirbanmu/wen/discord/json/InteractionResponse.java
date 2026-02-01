package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;
import java.util.List;

@CompiledJson
public record InteractionResponse(int type, @JsonAttribute(nullable = true) Data data) {
    public static final int TYPE_CHANNEL_MESSAGE_WITH_SOURCE = 4;
    public static final int TYPE_DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE = 5;

    @CompiledJson
    public record Data(@JsonAttribute(nullable = true) String content, @JsonAttribute(nullable = true) List<Embed> embeds, @JsonAttribute(nullable = true) Integer flags) {
        public static final int FLAG_EPHEMERAL = 64;
    }

    @CompiledJson
    public record Embed(@JsonAttribute(nullable = true) String title, @JsonAttribute(nullable = true) String description, @JsonAttribute(nullable = true) Integer color, @JsonAttribute(nullable = true) List<Field> fields) {
    }

    @CompiledJson
    public record Field(String name, String value, boolean inline) {
    }
}
