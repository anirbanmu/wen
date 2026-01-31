package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

// gateway payload wrapper - extracts op/sequence/eventType, ignores d
// d field type varies by opcode, so caller parses it separately from raw json
@CompiledJson
public record GatewayPayload(int op, @JsonAttribute(name = "s", nullable = true) Integer sequence, @JsonAttribute(name = "t", nullable = true) String eventType) {
}
