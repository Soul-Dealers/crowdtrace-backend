package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A possible duplicate found when this case was submitted. {@code nameSimilarity} is per mille; times are UTC.
 * Only matches recorded against this case are listed, the ones that flag it and bump its version.
 */
public record DuplicateMatchResponse(Long matchedCaseId, int confidence, int nameSimilarity, int dayDifference,
                                     List<String> reasons, String algorithmVersion, LocalDateTime detectedAt) {}
