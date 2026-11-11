package com.souldealers.crowdtracebackend.shared.audit;

import java.util.Set;
import java.util.stream.Collectors;

/** Raised when an audited operation completes without recording every declared action. */
public class AuditMissingException extends RuntimeException {
    public AuditMissingException(Set<AuditAction> actions) {
        super("Audited operation did not record required actions: " + actions.stream()
                .map(AuditAction::code).sorted().collect(Collectors.joining(", ")));
    }
}
