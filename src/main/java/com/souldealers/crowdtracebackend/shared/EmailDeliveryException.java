package com.souldealers.crowdtracebackend.shared;

/** The provider did not accept an email. Messages never include the recipient address. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
