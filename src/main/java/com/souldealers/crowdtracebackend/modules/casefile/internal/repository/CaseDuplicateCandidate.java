package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import java.time.LocalDate;

public interface CaseDuplicateCandidate {
    Long getId();
    String getFullName();
    LocalDate getLastSeenDate();
}
