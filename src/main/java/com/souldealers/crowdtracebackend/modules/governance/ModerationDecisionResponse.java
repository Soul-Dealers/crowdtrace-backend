package com.souldealers.crowdtracebackend.modules.governance;

/** @param resolution REMOVED or DISMISSED */
public record ModerationDecisionResponse(long commentId, String resolution, int resolvedReports) {
}
