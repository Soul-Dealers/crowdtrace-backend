package com.souldealers.crowdtracebackend.shared;

/** Port to the email provider. */
public interface EmailService {

    /**
     * Sends one HTML email, blocking until the provider answers.
     *
     * @throws EmailDeliveryException if the provider rejects the message, times out or cannot be reached
     */
    void send(String to, String subject, String html);
}
