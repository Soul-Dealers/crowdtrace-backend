package com.souldealers.crowdtracebackend.modules.community;

/** A comment locked for a report or a moderation decision. */
public record ModeratedComment(long id, long caseId, long authorId, boolean removed, boolean deleted) {
}
