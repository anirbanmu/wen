package com.github.anirbanmu.wen.discord.json;

// sealed type for all gateway events we handle
public sealed interface GatewayEvent {

    record Hello(int heartbeatInterval) implements GatewayEvent {
    }

    record Ready(String sessionId, String resumeGatewayUrl) implements GatewayEvent {
    }

    record Resumed() implements GatewayEvent {
    }

    record InteractionCreate(Interaction interaction) implements GatewayEvent {
    }

    record HeartbeatRequest() implements GatewayEvent {
    }

    record Reconnect() implements GatewayEvent {
    }

    record InvalidSession(boolean resumable) implements GatewayEvent {
    }

    record HeartbeatAck() implements GatewayEvent {
    }
}
