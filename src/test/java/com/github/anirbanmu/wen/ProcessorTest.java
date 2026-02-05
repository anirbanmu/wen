package com.github.anirbanmu.wen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.anirbanmu.wen.calendar.CalendarFeed;
import com.github.anirbanmu.wen.config.Calendar;
import com.github.anirbanmu.wen.discord.json.Interaction;
import com.github.anirbanmu.wen.discord.json.Interaction.Data;
import com.github.anirbanmu.wen.discord.json.Interaction.Option;
import com.github.anirbanmu.wen.discord.json.InteractionResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessorTest {

    @Test
    void testUnknownCalendar() {
        Processor processor = new Processor(Collections.emptyMap(), Collections.emptyMap());
        Interaction interaction = createWenInteraction("unknown");

        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);
        assertEquals(InteractionResponse.TYPE_CHANNEL_MESSAGE_WITH_SOURCE, response.type());
        assertTrue(response.data().content().startsWith("Unknown calendar:"));
    }

    @Test
    void testEmptyQueryShowsHelp() {
        Processor processor = new Processor(Collections.emptyMap(), Collections.emptyMap());
        Interaction interaction = createWenInteraction(null);

        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);
        assertNotNull(response.data().embeds());
        assertNull(response.data().content());
    }

    @Test
    void testValidCalendarEmptyFeed() {
        String name = "test-calendar";
        Calendar config = new Calendar(
            List.of("test"), name, "http://invalid.url", Duration.ofHours(1),
            Collections.emptyMap(), null, false);

        // feed that will fail to fetch but exist
        CalendarFeed feed = new CalendarFeed("http://invalid.url", Duration.ofHours(1));

        Map<String, Calendar> configs = Map.of("test", config);
        Map<String, CalendarFeed> feeds = Map.of("test", feed);

        Processor processor = new Processor(configs, feeds);

        Interaction interaction = createWenInteraction("test");
        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);

        assertEquals("No upcoming events found for " + name, response.data().content());
        assertNull(response.data().embeds());
    }

    private Interaction createWenInteraction(String query) {
        List<Option> options = new java.util.ArrayList<>();
        if (query != null) {
            options.add(new Option("query", Option.TYPE_STRING, query, null));
        }

        return new Interaction(
            "id", "appId", Interaction.TYPE_APPLICATION_COMMAND,
            new Data("id", "wen", Data.TYPE_CHAT_INPUT, options),
            "guild", "channel", "token");
    }
}
