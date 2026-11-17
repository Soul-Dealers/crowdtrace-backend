package com.souldealers.crowdtracebackend.shared;

import org.jspecify.annotations.Nullable;

/** Port to the email provider. */
public interface EmailService {

    /**
     * Sends one HTML email, blocking until the provider answers.
     *
     * @throws EmailDeliveryException if the provider rejects the message, times out or cannot be reached
     */
    default void send(String to, String subject, String html) {
        send(to, subject, html, null);
    }

    /**
     * As {@link #send(String, String, String)}. A repeat call with the same idempotency key and content is not sent
     * again by the provider, for as long as the provider remembers the key.
     */
    void send(String to, String subject, String html, @Nullable String idempotencyKey);
}
