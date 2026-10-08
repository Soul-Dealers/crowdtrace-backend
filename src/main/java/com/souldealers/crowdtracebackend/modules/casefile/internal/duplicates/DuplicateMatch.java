package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import java.util.List;

public record DuplicateMatch(int confidence, int nameSimilarity, int dayDifference,
                             List<MatchReason> reasons, String algorithmVersion) {
    public DuplicateMatch {
        reasons = List.copyOf(reasons);
    }
}
