package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseDuplicateMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseDuplicateMatchRepository extends JpaRepository<CaseDuplicateMatch, Long> {
    boolean existsByCaseIdAndMatchedCaseId(Long caseId, Long matchedCaseId);

    List<CaseDuplicateMatch> findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(Long caseId);
}
