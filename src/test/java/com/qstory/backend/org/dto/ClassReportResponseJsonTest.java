package com.qstory.backend.org.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FE 계약: 요약 필드가 최상위에 펼쳐지고 tutorName, studentNames가 더해진다. */
class ClassReportResponseJsonTest {

    @Test
    void summaryFieldsAreFlattenedNextToTutorNameAndStudentNames() throws Exception {
        AppUser tutor = AppUser.builder().id(UUID.randomUUID()).displayName("김선생").build();
        StoryCompletion completion = StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).storyId("HG")
                .groupSession(true).endStatus("EXITED").completedAt(Instant.now()).build();

        JsonNode json = new ObjectMapper().registerModule(new JavaTimeModule())
                .valueToTree(ClassReportResponse.of(completion));

        assertEquals(completion.getId().toString(), json.get("id").asText());
        assertEquals("EXITED", json.get("endStatus").asText());
        assertEquals("CLASS", json.get("sessionKind").asText());
        assertEquals("김선생", json.get("tutorName").asText());
        assertTrue(json.get("studentNames").isArray());
        assertTrue(!json.has("summary"));
    }
}
