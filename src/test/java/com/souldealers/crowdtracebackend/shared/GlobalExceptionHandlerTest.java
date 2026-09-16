package com.souldealers.crowdtracebackend.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.souldealers.crowdtracebackend.shared.config.CorrelationIdFilter;
import com.souldealers.crowdtracebackend.shared.exception.GlobalExceptionHandler;
import org.assertj.core.api.Assertions;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private Logger exceptionLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        exceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        exceptionLogger.addAppender(logAppender);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FailingController())
                .setValidator(new LocalValidatorFactoryBean())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        exceptionLogger.detachAppender(logAppender);
    }

    @Test
    void shouldRenderNotFoundAsProblemDetail() throws Exception {
        mockMvc.perform(get("/missing").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:crowdtrace:problem:resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Vehicle not found"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/missing"));
    }

    @Test
    void shouldRenderInvalidRequestAsProblemDetail() throws Exception {
        mockMvc.perform(get("/invalid").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid vehicle request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/unexpected").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void shouldKeepCorrelationIdInLogsWithoutLoggingExceptionDetails() throws Exception {
        mockMvc.perform(get("/unexpected")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "request-123")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(CorrelationIdFilter.CORRELATION_ID_HEADER, "request-123"));

        ILoggingEvent event = logAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains("Unexpected error"))
                .findFirst()
                .orElseThrow();

        Assertions.assertThat(event.getMDCPropertyMap())
                .containsEntry(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "request-123");
        Assertions.assertThat(event.getFormattedMessage())
                .doesNotContain("database password leaked");
        Assertions.assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void shouldIncludeFieldValidationErrorsAsCustomProblemDetailProperty() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name[0]").value("name is required"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/missing")
        void missing() {
            throw new NoSuchElementException("Vehicle not found");
        }

        @GetMapping("/invalid")
        void invalid() {
            throw new IllegalArgumentException("Invalid vehicle request");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new RuntimeException("database password leaked");
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {
        }
    }

    record ValidationRequest(@NotBlank(message = "name is required") String name) {
    }
}
