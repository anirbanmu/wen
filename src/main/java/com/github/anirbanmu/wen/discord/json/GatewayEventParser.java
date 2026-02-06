package com.github.anirbanmu.wen.discord.json;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;
import com.github.anirbanmu.wen.util.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

// parses raw gateway json into typed GatewayEvent
public final class GatewayEventParser {
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    public GatewayEventParser() {
    }

    public record ParseResult(GatewayEvent event, Integer sequence) {
    }

    public ParseResult parse(String raw) throws Exception {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);

        // first pass: get op, s, t
        Envelope envelope = Json.DSL.deserialize(Envelope.class, bais);

        // second pass: reuse stream for typed deserialization
        GatewayEvent event = switch (envelope.op()) {
            case OP_HELLO -> {
                bais.reset();
                HelloMsg msg = Json.DSL.deserialize(HelloMsg.class, bais);
                yield new GatewayEvent.Hello(msg.d().heartbeatInterval());
            }
            case OP_HEARTBEAT -> new GatewayEvent.HeartbeatRequest();
            case OP_HEARTBEAT_ACK -> new GatewayEvent.HeartbeatAck();
            case OP_RECONNECT -> new GatewayEvent.Reconnect();
            case OP_INVALID_SESSION -> {
                bais.reset();
                InvalidSessionMsg msg = Json.DSL.deserialize(InvalidSessionMsg.class, bais);
                yield new GatewayEvent.InvalidSession(msg.d());
            }
            case OP_DISPATCH -> {
                bais.reset();
                yield parseDispatch(envelope.t(), bais);
            }
            default -> null;
        };

        return new ParseResult(event, envelope.s());
    }

    private GatewayEvent parseDispatch(String eventType, ByteArrayInputStream bais) throws Exception {
        return switch (eventType) {
            case "READY" -> {
                ReadyMsg msg = Json.DSL.deserialize(ReadyMsg.class, bais);
                yield new GatewayEvent.Ready(msg.d().sessionId(), msg.d().resumeGatewayUrl());
            }
            case "INTERACTION_CREATE" -> {
                InteractionMsg msg = Json.DSL.deserialize(InteractionMsg.class, bais);
                yield new GatewayEvent.InteractionCreate(msg.d());
            }
            default -> null;
        };
    }

    // wire format records - private implementation details

    @CompiledJson
    record Envelope(int op, @JsonAttribute(nullable = true) Integer s, @JsonAttribute(nullable = true) String t) {
    }

    @CompiledJson
    record HelloMsg(int op, HelloData d) {
    }

    @CompiledJson
    record HelloData(@JsonAttribute(name = "heartbeat_interval") int heartbeatInterval) {
    }

    @CompiledJson
    record InvalidSessionMsg(int op, @JsonAttribute(name = "d") boolean d) {
    }

    @CompiledJson
    record ReadyMsg(int op, ReadyData d) {
    }

    @CompiledJson
    record ReadyData(@JsonAttribute(name = "session_id") String sessionId, @JsonAttribute(name = "resume_gateway_url") String resumeGatewayUrl) {
    }

    @CompiledJson
    record InteractionMsg(int op, Interaction d) {
    }
}
