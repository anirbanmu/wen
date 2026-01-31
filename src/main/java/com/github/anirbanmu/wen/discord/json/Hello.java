package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

// opcode 10 hello payload data
@CompiledJson
public record Hello(@JsonAttribute(name = "heartbeat_interval") int heartbeatInterval) {
}
