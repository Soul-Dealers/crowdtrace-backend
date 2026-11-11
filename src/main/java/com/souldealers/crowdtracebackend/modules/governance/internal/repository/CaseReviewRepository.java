package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Insert (via the writer) and read-only history; no save, update or delete operations. */
public interface CaseReviewRepository extends Repository<CaseReview, Long>, CaseReviewWriter {
    List<CaseReview> findByCaseIdOrderByCreatedAtAscIdAsc(long caseId);
}
