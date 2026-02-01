package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

@CompiledJson
public record InteractionResponse(int type, @JsonAttribute(nullable = true) Data data) {
    public static final int TYPE_CHANNEL_MESSAGE_WITH_SOURCE = 4;
    public static final int TYPE_DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE = 5;

    @CompiledJson
    public record Data(@JsonAttribute(nullable = true) String content, @JsonAttribute(nullable = true) Integer flags) {
        public static final int FLAG_EPHEMERAL = 64;
    }
}
