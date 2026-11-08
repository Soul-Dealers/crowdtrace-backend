package com.souldealers.crowdtracebackend.shared;

/** The configured file storage cannot issue download URLs. Rendered as 503. */
public class FileAccessUnavailableException extends RuntimeException {

    public FileAccessUnavailableException(String message) {
        super(message);
    }
}
