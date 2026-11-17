package com.souldealers.crowdtracebackend.modules.identity;

import java.util.Optional;

/** Private contact details for system mail to an account's own owner. Never expose the result publicly. */
public interface AccountContacts {

    /** The account's email; empty when the account is gone, deleted or not active. */
    Optional<String> emailOf(Long userId);
}
