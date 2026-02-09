package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;
import java.util.List;

@CompiledJson
public record Command(String name, String description, @JsonAttribute(nullable = true) List<Option> options, @JsonAttribute(name = "integration_types", nullable = true) List<Integer> integrationTypes, @JsonAttribute(nullable = true) List<Integer> contexts) {

    // integration types
    public static final int INTEGRATION_GUILD_INSTALL = 0;
    public static final int INTEGRATION_USER_INSTALL = 1;

    // interaction context types
    public static final int CONTEXT_GUILD = 0;
    public static final int CONTEXT_BOT_DM = 1;
    public static final int CONTEXT_PRIVATE_CHANNEL = 2;

    public static final int TYPE_SUB_COMMAND = 1;
    public static final int TYPE_SUB_COMMAND_GROUP = 2;
    public static final int TYPE_STRING = 3;
    public static final int TYPE_INTEGER = 4;
    public static final int TYPE_BOOLEAN = 5;

    @CompiledJson
    public record Option(String name, String description, int type, boolean required, @JsonAttribute(nullable = true) List<Choice> choices, @JsonAttribute(nullable = true) Boolean autocomplete) {
    }

    @CompiledJson
    public record Choice(String name, String value) {
    }
}
