package com.github.anirbanmu.wen.discord.json;

import static org.junit.jupiter.api.Assertions.*;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GatewayPayloadTest {
    private final DslJson<Object> dsl = new DslJson<>(Settings.withRuntime().includeServiceLoader());

    @Test
    void deserializePayloadExtractsOpAndT() throws Exception {
        String json = """
            {"op": 10, "t": null, "s": null, "d": {"heartbeat_interval": 41250}}
            """;

        var payload = dsl.deserialize(
            GatewayPayload.class,
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(10, payload.op());
        assertNull(payload.sequence());
        assertNull(payload.eventType());
    }

    @Test
    void deserializeDispatchPayload() throws Exception {
        String json = """
            {"op": 0, "t": "INTERACTION_CREATE", "s": 42, "d": {}}
            """;

        var payload = dsl.deserialize(
            GatewayPayload.class,
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, payload.op());
        assertEquals(42, payload.sequence());
        assertEquals("INTERACTION_CREATE", payload.eventType());
    }

    @Test
    void deserializeHello() throws Exception {
        String json = """
            {"heartbeat_interval": 41250}
            """;

        var hello = dsl.deserialize(
            Hello.class,
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(41250, hello.heartbeatInterval());
    }

    @Test
    void serializeIdentify() throws Exception {
        var identify = Identify.create("test-token", 513);

        var out = new ByteArrayOutputStream();
        dsl.serialize(identify, out);
        String json = out.toString(StandardCharsets.UTF_8);

        assertTrue(json.contains("\"token\":\"test-token\""));
        assertTrue(json.contains("\"intents\":513"));
        assertTrue(json.contains("\"os\":\"linux\""));
    }

    @Test
    void deserializeInteraction() throws Exception {
        String json = """
            {
                "id": "12345",
                "application_id": "67890",
                "type": 2,
                "token": "interaction-token",
                "data": {
                    "id": "cmd-1",
                    "name": "wen",
                    "type": 1,
                    "options": [
                        {"name": "source", "type": 3, "value": "f1"}
                    ]
                }
            }
            """;

        var interaction = dsl.deserialize(
            Interaction.class,
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals("12345", interaction.id());
        assertEquals("67890", interaction.applicationId());
        assertEquals(2, interaction.type());
        assertEquals("interaction-token", interaction.token());
        assertNotNull(interaction.data());
        assertEquals("wen", interaction.data().name());
        assertEquals(1, interaction.data().options().size());
        assertEquals("source", interaction.data().options().getFirst().name());
        assertEquals("f1", interaction.data().options().getFirst().value());
    }
}
