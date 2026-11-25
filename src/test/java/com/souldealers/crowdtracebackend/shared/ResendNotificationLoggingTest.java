package com.souldealers.crowdtracebackend.shared;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.ITemplateEngine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sentinel values go through the email paths; none of them may reach the logs. */
@ExtendWith(OutputCaptureExtension.class)
class ResendNotificationLoggingTest {
    private static final String EMAIL = "sentinel.reporter@leak.test";
    private static final String OTP = "918273";
    private static final String MEDICAL = "SENTINEL-MEDICAL-ASTHMA";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void successLogsNoRecipientOrOtp(CapturedOutput out) throws IOException {
        ResendNotificationService service = service(serve(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }));

        service.sendOtpEmail(EMAIL, OTP, "Ama", OtpType.CREATE);
        service.sendWelcomeEmail(EMAIL, "Ama");

        assertThat(out.getAll()).doesNotContain(EMAIL, OTP).contains("OTP email sent").contains("Welcome email sent");
    }

    @Test
    void providerFailureLogsNoRecipientOrProviderBody(CapturedOutput out) throws IOException {
        byte[] body = ("{\"error\":\"rejected " + EMAIL + " code " + OTP + " note " + MEDICAL + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        ResendNotificationService service = service(serve(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(body);
            }
        }));

        service.sendOtpEmail(EMAIL, OTP, "Ama", OtpType.CREATE);

        assertThat(out.getAll()).doesNotContain(EMAIL, OTP, MEDICAL).contains("Email delivery failed");
    }

    private static ResendNotificationService service(String url) {
        ITemplateEngine templates = mock(ITemplateEngine.class);
        when(templates.process(anyString(), any())).thenReturn("<p>Your code</p>");
        ResendNotificationService service = new ResendNotificationService(templates);
        ReflectionTestUtils.setField(service, "resendUrl", url);
        ReflectionTestUtils.setField(service, "resendApiKey", "key-123");
        ReflectionTestUtils.setField(service, "fromEmail", "noreply@crowdtrace.example");
        return service;
    }

    private String serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
    }
}
