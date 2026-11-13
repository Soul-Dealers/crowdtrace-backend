package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;

import java.time.LocalDateTime;

public record FollowedCaseResponse(PublicCaseSummaryResponse followedCase, LocalDateTime followedAt) {}
