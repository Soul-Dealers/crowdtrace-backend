package com.souldealers.crowdtracebackend.modules.casefile.internal;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

final class CasePageRequests {
    static final int MAX_PAGE_SIZE = 50;

    private CasePageRequests() {}

    /** Keeps page and size only; repository queries fix ordering with id as the last tiebreaker. */
    static Pageable fixed(Pageable requested) {
        int size = Math.clamp(requested.isPaged() ? requested.getPageSize() : 20, 1, MAX_PAGE_SIZE);
        int page = requested.isPaged() ? Math.max(requested.getPageNumber(), 0) : 0;
        return PageRequest.of(page, size);
    }
}
