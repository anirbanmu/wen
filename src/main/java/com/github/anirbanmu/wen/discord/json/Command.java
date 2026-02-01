package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;
import java.util.List;

@CompiledJson
public record Command(String name, String description, @JsonAttribute(nullable = true) List<Option> options) {
    public static final int TYPE_SUB_COMMAND = 1;
    public static final int TYPE_SUB_COMMAND_GROUP = 2;
    public static final int TYPE_STRING = 3;
    public static final int TYPE_INTEGER = 4;
    public static final int TYPE_BOOLEAN = 5;

    @CompiledJson
    public record Option(String name, String description, int type, boolean required, @JsonAttribute(nullable = true) List<Choice> choices) {
    }

    @CompiledJson
    public record Choice(String name, String value) {
    }
}
