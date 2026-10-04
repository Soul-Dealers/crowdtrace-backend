package com.souldealers.crowdtracebackend.modules.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

class VerificationDtoValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController()).build();
    }

    @Test
    void rejectsEvidenceReferenceLongerThanDatabaseColumn() throws Exception {
        String evidence = "x".repeat(1025);

        mockMvc.perform(post("/verification-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"POLICE\",\"evidenceReference\":\""
                                + evidence + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @RestController
    static class ValidationController {

        @PostMapping("/verification-validation")
        void validate(@Valid @RequestBody SubmitVerificationRequest request) {
        }
    }
}
