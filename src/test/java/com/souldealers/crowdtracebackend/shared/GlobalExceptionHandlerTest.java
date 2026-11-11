package com.souldealers.crowdtracebackend.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
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
    void shouldRenderAuthenticationFailureAsUnauthorized() throws Exception {
        mockMvc.perform(get("/bad-credentials").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
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

    @Test
    void shouldRenderMalformedEnumAsBadRequestWithoutEchoingBody() throws Exception {
        String malformedBody = "{\"verificationType\":\"IDENTITY\",\"evidenceReference\":\"private-proof\"}";

        mockMvc.perform(MockMvcRequestBuilders.post("/verification-input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("The request body could not be read"))
                .andExpect(result -> Assertions.assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(malformedBody));
    }

    @Test
    void shouldRenderNonNumericPathIdAsBadRequest() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/verification-input/abc/approve")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("The request parameter is invalid"));
    }

    @Test
    void shouldRenderOversizedMultipartUploadAsPayloadTooLarge() throws Exception {
        mockMvc.perform(multipart("/oversized-upload")
                        .file(new MockMultipartFile("file", new byte[]{1}))
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "upload-123")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:crowdtrace:problem:payload-too-large"))
                .andExpect(jsonPath("$.title").value("Payload too large"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("The uploaded file exceeds the maximum allowed size"))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.instance").value("/oversized-upload"))
                .andExpect(jsonPath("$.correlationId").value("upload-123"));
    }

    @Test
    void shouldRenderMissingFilePartAsBadRequest() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .param("purpose", "REPORT")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "upload-456")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:crowdtrace:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("The required request part 'file' is missing"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.instance").value("/upload"))
                .andExpect(jsonPath("$.correlationId").value("upload-456"));
    }

    @Test
    void shouldRenderMissingPurposeAsBadRequest() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", new byte[]{1}))
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "upload-789")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:crowdtrace:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("The required request parameter 'purpose' is missing"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.instance").value("/upload"))
                .andExpect(jsonPath("$.correlationId").value("upload-789"));
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

        @GetMapping("/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new RuntimeException("database password leaked");
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {
        }

        @PostMapping("/verification-input")
        void verificationInput(@RequestBody VerificationInput request) {
        }

        @PostMapping("/verification-input/{id}/approve")
        void approve(@PathVariable Long id) {
        }

        @PostMapping("/oversized-upload")
        void oversizedUpload() {
            // Standalone MockMvc does not enforce the servlet multipart size limit.
            throw new MaxUploadSizeExceededException(10 * 1024 * 1024);
        }

        @PostMapping("/upload")
        void upload(@RequestPart MultipartFile file, @RequestParam String purpose) {
        }
    }

    record ValidationRequest(@NotBlank(message = "name is required") String name) {
    }

    record VerificationInput(com.souldealers.crowdtracebackend.modules.identity.VerificationType verificationType,
                             String evidenceReference) {
    }
}
