package com.souldealers.crowdtracebackend.modules.casefile;

/** A case as the comment moderation queue names it, whatever its review status. */
public record ModerationCaseRef(long id, String fullName, boolean publiclyVisible) {
}
