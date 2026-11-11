package com.souldealers.crowdtracebackend.modules.casefile.internal;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import static org.assertj.core.api.Assertions.assertThat;

class CasePageRequestsTest {
    @Test
    void dropsClientSortAndClampsSizeWhileKeepingPage() {
        Pageable fixed = CasePageRequests.fixed(PageRequest.of(3, 10000, Sort.by("duplicateFlag")));
        assertThat(fixed.getSort().isUnsorted()).isTrue();
        assertThat(fixed.getPageSize()).isEqualTo(50);
        assertThat(fixed.getPageNumber()).isEqualTo(3);
    }

    @Test
    void unpagedRequestsUseTheDefaultFirstPage() {
        Pageable fixed = CasePageRequests.fixed(Pageable.unpaged(Sort.by("duplicateFlag")));
        assertThat(fixed.getPageNumber()).isZero();
        assertThat(fixed.getPageSize()).isEqualTo(20);
        assertThat(fixed.getSort().isUnsorted()).isTrue();
    }
}
