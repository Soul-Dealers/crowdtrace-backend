package com.souldealers.crowdtracebackend.shared;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Resend adapter. Bounded by a timeout so a slow provider cannot hold a worker thread indefinitely. */
@Service
public class ResendEmailService implements EmailService {
    private final RestClient client;
    private final String url;
    private final String apiKey;
    private final String fromEmail;

    @Autowired
    public ResendEmailService(@Value("${resend.url}") String url, @Value("${resend.api-key}") String apiKey,
                              @Value("${resend.from-email}") String fromEmail,
                              @Value("${resend.timeout:10s}") Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.url = url;
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
    }

    @Override
    public void send(String to, String subject, String html) {
        try {
            client.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", fromEmail, "to", to, "subject", subject, "html", html))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new EmailDeliveryException("Email provider returned " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new EmailDeliveryException("Email provider unreachable or timed out", e);
        }
    }
}
