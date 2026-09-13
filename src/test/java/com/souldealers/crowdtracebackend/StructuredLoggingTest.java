package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTest.class);

    @Test
    void consoleLogsAreJsonAndIncludeCorrelationId(CapturedOutput output) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "request-456")) {
            log.info("structured logging probe");
        }

        assertThat(output.getOut())
                .contains("\"message\":\"structured logging probe\"")
                .contains("\"correlationId\":\"request-456\"");
    }
}
