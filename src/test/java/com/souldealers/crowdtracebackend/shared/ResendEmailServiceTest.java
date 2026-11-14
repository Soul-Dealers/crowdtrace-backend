package com.souldealers.crowdtracebackend.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendEmailServiceTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsTheMessageWithTheApiKey() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String url = serve(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        adapter(url, Duration.ofSeconds(2)).send("reporter@example.com", "Subject", "<p>Hi</p>");

        assertThat(auth.get()).isEqualTo("Bearer key-123");
        JsonNode json = new ObjectMapper().readTree(body.get());
        assertThat(json.get("from").asText()).isEqualTo("noreply@crowdtrace.example");
        assertThat(json.get("to").asText()).isEqualTo("reporter@example.com");
        assertThat(json.get("subject").asText()).isEqualTo("Subject");
        assertThat(json.get("html").asText()).isEqualTo("<p>Hi</p>");
    }

    @Test
    void aRejectedRequestThrows() throws Exception {
        String url = serve(exchange -> {
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> adapter(url, Duration.ofSeconds(2)).send("a@example.com", "s", "h"))
                .isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    void aSlowProviderTimesOut() throws Exception {
        String url = serve(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> adapter(url, Duration.ofMillis(200)).send("a@example.com", "s", "h"))
                .isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    void anUnreachableProviderThrows() {
        assertThatThrownBy(() -> adapter("http://127.0.0.1:9/emails", Duration.ofSeconds(1))
                .send("a@example.com", "s", "h"))
                .isInstanceOf(EmailDeliveryException.class);
    }

    private static ResendEmailService adapter(String url, Duration timeout) {
        return new ResendEmailService(url, "key-123", "noreply@crowdtrace.example", timeout);
    }

    private String serve(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
    }
}
