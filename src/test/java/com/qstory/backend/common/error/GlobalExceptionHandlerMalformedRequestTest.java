package com.qstory.backend.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Q-41: 형식이 틀린 요청(필수 값·파트 누락, 숫자 자리에 글자, 깨진 JSON)은 500이 아니라 4xx로 답한다. */
class GlobalExceptionHandlerMalformedRequestTest {

    @RestController
    static class UploadController {
        @PostMapping(value = "/upload", consumes = "multipart/form-data")
        void upload(@RequestParam("sample_id") String sampleId, @RequestParam("question_round") int round,
                @RequestPart("audio") MultipartFile audio) {
        }

        @PostMapping(value = "/json", consumes = "application/json")
        void json(@RequestBody Payload payload) {
        }

        record Payload(String name) {
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UploadController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingFieldIs400() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("audio", new byte[] {1})).param("question_round", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failure.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingPartIs400() throws Exception {
        mvc.perform(multipart("/upload").param("sample_id", "x").param("question_round", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failure.code").value("VALIDATION_FAILED"));
    }

    @Test
    void wrongTypeIs400() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("audio", new byte[] {1}))
                        .param("sample_id", "x").param("question_round", "one"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failure.code").value("VALIDATION_FAILED"));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/upload").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void brokenJsonIs400() throws Exception {
        mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failure.code").value("INVALID_JSON"));
    }
}
