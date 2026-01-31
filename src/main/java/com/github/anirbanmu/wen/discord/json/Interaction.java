package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;
import java.util.List;

// INTERACTION_CREATE event data
@CompiledJson
public record Interaction(String id, @JsonAttribute(name = "application_id") String applicationId, int type, @JsonAttribute(nullable = true) Data data, @JsonAttribute(name = "guild_id", nullable = true) String guildId, @JsonAttribute(name = "channel_id", nullable = true) String channelId, String token) {

    // interaction types
    public static final int TYPE_APPLICATION_COMMAND = 2;

    // data field within an interaction (for slash commands)
    @CompiledJson
    public record Data(String id, String name, int type, @JsonAttribute(nullable = true) List<Option> options) {

        // application command types
        public static final int TYPE_CHAT_INPUT = 1;
    }

    // individual command option (e.g., source="f1", filter="r")
    @CompiledJson
    public record Option(String name, int type, @JsonAttribute(nullable = true) String value) {

        // option types we care about
        public static final int TYPE_STRING = 3;
    }
}
