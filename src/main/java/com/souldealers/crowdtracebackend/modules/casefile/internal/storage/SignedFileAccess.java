package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import java.net.URI;
import java.time.Duration;

/**
 * Issues short-lived download URLs for stored objects. Only providers that can sign implement it, so callers
 * can tell signed access is unavailable (for example with in-memory storage) by the bean being absent.
 * Callers must authorize the actor before asking for a URL; signing grants access to whoever holds it.
 */
public interface SignedFileAccess {
    /**
     * @param ttl positive and no longer than the provider's configured maximum
     * @param inline serve a private file for display in the browser instead of as a download
     * @return a GET URL serving the object with its stored content type; private files download as attachments
     *         unless {@code inline}
     */
    URI signedDownloadUrl(StoredObject object, Duration ttl, boolean inline);
}
