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
        Interaction interaction = createWenInteraction("test", null);

        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);
        assertEquals(InteractionResponse.TYPE_CHANNEL_MESSAGE_WITH_SOURCE, response.type());
        assertTrue(response.data().content().startsWith("Unknown calendar:"));
    }

    @Test
    void testMissingCalendarArg() {
        Processor processor = new Processor(Collections.emptyMap(), Collections.emptyMap());
        Interaction interaction = new Interaction(
            "id", "appId", Interaction.TYPE_APPLICATION_COMMAND,
            new Data("id", "wen", 1, Collections.emptyList()),
            "guild", "channel", "token");

        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);
        assertTrue(response.data().content().startsWith("Error:"));
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

        Interaction interaction = createWenInteraction("test", null);
        InteractionResponse response = processor.process(interaction);

        assertNotNull(response);
        // expecting embed with "No upcoming events"
        assertNull(response.data().content());
        assertEquals(1, response.data().embeds().size());
        assertEquals(name, response.data().embeds().getFirst().title());
        assertTrue(response.data().embeds().getFirst().description().startsWith("No upcoming events"));
    }

    private Interaction createWenInteraction(String calendarVal, String filterVal) {
        List<Option> options = new java.util.ArrayList<>();
        if (calendarVal != null) {
            options.add(new Option("calendar", Option.TYPE_STRING, calendarVal));
        }
        if (filterVal != null) {
            options.add(new Option("filter", Option.TYPE_STRING, filterVal));
        }

        return new Interaction(
            "id", "appId", Interaction.TYPE_APPLICATION_COMMAND,
            new Data("id", "wen", Data.TYPE_CHAT_INPUT, options),
            "guild", "channel", "token");
    }
}
