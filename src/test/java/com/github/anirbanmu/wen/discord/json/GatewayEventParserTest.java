package com.github.anirbanmu.wen.discord.json;

import static org.junit.jupiter.api.Assertions.*;

import com.github.anirbanmu.wen.discord.json.GatewayEventParser.ParseResult;
import com.github.anirbanmu.wen.util.Json;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GatewayEventParserTest {

    private final GatewayEventParser parser = new GatewayEventParser();

    @Test
    void parseHello() throws Exception {
        String json = """
            {"op": 10, "t": null, "s": null, "d": {"heartbeat_interval": 41250}}
            """;

        ParseResult result = parser.parse(json);

        assertNull(result.sequence());
        assertInstanceOf(GatewayEvent.Hello.class, result.event());
        assertEquals(41250, ((GatewayEvent.Hello) result.event()).heartbeatInterval());
    }

    @Test
    void parseDispatchWithSequence() throws Exception {
        // dispatch for an event we don't handle - should return null event but preserve
        // sequence
        String json = """
            {"op": 0, "t": "SOME_OTHER_EVENT", "s": 42, "d": {}}
            """;

        ParseResult result = parser.parse(json);

        assertEquals(42, result.sequence());
        assertNull(result.event());
    }

    @Test
    void parseHeartbeatRequest() throws Exception {
        String json = """
            {"op": 1, "d": null}
            """;

        ParseResult result = parser.parse(json);
        assertInstanceOf(GatewayEvent.HeartbeatRequest.class, result.event());
    }

    @Test
    void parseHeartbeatAck() throws Exception {
        String json = """
            {"op": 11}
            """;

        ParseResult result = parser.parse(json);
        assertInstanceOf(GatewayEvent.HeartbeatAck.class, result.event());
    }

    @Test
    void parseReconnect() throws Exception {
        String json = """
            {"op": 7}
            """;

        ParseResult result = parser.parse(json);
        assertInstanceOf(GatewayEvent.Reconnect.class, result.event());
    }

    @Test
    void parseInvalidSession() throws Exception {
        String json = """
            {"op": 9, "d": false}
            """;

        ParseResult result = parser.parse(json);

        assertInstanceOf(GatewayEvent.InvalidSession.class, result.event());
        assertFalse(((GatewayEvent.InvalidSession) result.event()).resumable());
    }

    @Test
    void parseReady() throws Exception {
        String json = """
            {"op": 0, "t": "READY", "s": 1, "d": {"session_id": "abc123", "resume_gateway_url": "wss://resume.discord.gg"}}
            """;

        ParseResult result = parser.parse(json);

        assertEquals(1, result.sequence());
        assertInstanceOf(GatewayEvent.Ready.class, result.event());
        GatewayEvent.Ready ready = (GatewayEvent.Ready) result.event();
        assertEquals("abc123", ready.sessionId());
        assertEquals("wss://resume.discord.gg", ready.resumeGatewayUrl());
    }

    @Test
    void parseInteractionCreate() throws Exception {
        String json = """
            {
                "op": 0,
                "t": "INTERACTION_CREATE",
                "s": 5,
                "d": {
                    "id": "12345",
                    "application_id": "67890",
                    "type": 2,
                    "token": "interaction-token",
                    "data": {
                        "id": "cmd-1",
                        "name": "wen",
                        "type": 1,
                        "options": [
                            {"name": "calendar", "type": 3, "value": "f1"}
                        ]
                    }
                }
            }
            """;

        ParseResult result = parser.parse(json);

        assertEquals(5, result.sequence());
        assertInstanceOf(GatewayEvent.InteractionCreate.class, result.event());
        Interaction interaction = ((GatewayEvent.InteractionCreate) result.event()).interaction();
        assertEquals("12345", interaction.id());
        assertEquals("67890", interaction.applicationId());
        assertEquals(2, interaction.type());
        assertEquals("interaction-token", interaction.token());
        assertEquals("wen", interaction.data().name());
        assertEquals(1, interaction.data().options().size());
        assertEquals("calendar", interaction.data().options().getFirst().name());
        assertEquals("f1", interaction.data().options().getFirst().value());
    }

    @Test
    void serializeIdentify() throws Exception {
        Identify identify = Identify.create("test-token", 513);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Json.DSL.serialize(identify, out);
        String json = out.toString(StandardCharsets.UTF_8);

        assertTrue(json.contains("\"token\":\"test-token\""));
        assertTrue(json.contains("\"intents\":513"));
        assertTrue(json.contains("\"$os\":\"linux\""));
    }
}
