package com.souldealers.crowdtracebackend.modules.casefile;

/** Reporter-owned or admin sensitive details from PRD §5.1; never public. */
public record SensitiveDetailsResponse(String reporterRelationship, String medicalConditions,
        String knownAssociates, String vehicleInfo, String socialMediaHandles) {}
