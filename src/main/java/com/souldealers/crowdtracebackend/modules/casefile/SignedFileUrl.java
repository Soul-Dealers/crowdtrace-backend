package com.souldealers.crowdtracebackend.modules.casefile;

import java.net.URI;
import java.time.Instant;

/** A short-lived download URL. Whoever holds it can download the file until it expires. */
public record SignedFileUrl(URI url, Instant expiresAt) {}
