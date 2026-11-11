package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

/** Admin queue summary with PRD §5.1 review flags; no sensitive input or private identity. */
public record AdminCaseSummaryResponse(Long id, String fullName, int age, GhanaRegion region,
        ReviewStatus reviewStatus, boolean priorityMinor, boolean duplicateFlag, LocalDateTime submittedAt) {}
