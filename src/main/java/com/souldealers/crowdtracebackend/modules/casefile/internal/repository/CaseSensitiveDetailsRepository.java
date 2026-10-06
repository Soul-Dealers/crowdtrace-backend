package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseSensitiveDetailsRepository extends JpaRepository<CaseSensitiveDetails, Long> {}
