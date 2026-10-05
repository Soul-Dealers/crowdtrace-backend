package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseConsentRepository extends JpaRepository<CaseConsent, Long> {
    List<CaseConsent> findByCaseIdOrderByAcceptedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndConsentType(Long caseId, ConsentType consentType);
}
