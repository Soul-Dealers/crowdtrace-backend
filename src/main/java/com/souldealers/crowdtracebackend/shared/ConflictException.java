package com.souldealers.crowdtracebackend.shared;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
