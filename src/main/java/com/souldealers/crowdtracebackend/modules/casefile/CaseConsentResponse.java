package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

/** Admin consent history accompanying sensitive collection under PRD §5.1. */
public record CaseConsentResponse(ConsentType consentType, String consentVersion,
        ConsentSource source, LocalDateTime acceptedAt) {}
