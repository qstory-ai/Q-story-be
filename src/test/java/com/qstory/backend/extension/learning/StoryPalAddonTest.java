package com.qstory.backend.extension.learning;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.companionchat.DialogueInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryPalAddonTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @AfterEach void reset(){System.clearProperty("QSTORY_STORYPAL_ENABLED");}
    @Test void disabledDoesNotModifyPromptOrPayload(){
        System.setProperty("QSTORY_STORYPAL_ENABLED","false");
        assertEquals("original",StoryPalAddon.prompt("original"));
        ObjectNode payload=mapper.createObjectNode();
        StoryPalAddon.payload(payload,LearningState.initial("5-6"));
        assertTrue(payload.isEmpty());
    }
    @Test void enabledAddsBoundedSchemaAndState(){
        System.setProperty("QSTORY_STORYPAL_ENABLED","true");
        ObjectNode schema=mapper.createObjectNode();
        schema.putObject("properties");schema.putArray("required");
        StoryPalAddon.schema(schema);
        assertTrue(schema.path("properties").has("learningFeedback"));
        assertEquals(3,schema.path("properties").path("learningFeedback").path("required").size());
        ObjectNode p=mapper.createObjectNode();
        StoryPalAddon.payload(p,LearningState.initial("7-9"));
        assertEquals("7-9",p.path("learningContext").path("ageBand").asText());
        assertTrue(StoryPalAddon.prompt("existing safety").startsWith("existing safety"));
    }
    @Test void oldDialogueRequestAndConstructorsStillWork() throws Exception {
        var old=DialogueInput.fromBody(mapper.readTree("{\"transcript\":\"test\"}"));
        assertEquals("UNKNOWN",old.learning().ageBand());
        var explicit=new DialogueInput(java.util.List.of(),null,java.util.List.of(),null,"NONE",null,DialogueInput.Attempt.NONE);
        assertNotNull(explicit.learning());
    }
    @Test void untrustedStateCannotIntroduceNewFields() throws Exception {
        var s=StoryPalAddon.readState(mapper.readTree("{\"ageBand\":\"admin\",\"difficulty\":999,\"hintLevel\":-4}"));
        assertEquals("UNKNOWN",s.ageBand());assertEquals(3,s.difficulty());assertEquals(0,s.hintLevel());
    }
}
