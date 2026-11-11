package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

public enum DetectedType {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png");

    private final String contentType;

    DetectedType(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }
}
