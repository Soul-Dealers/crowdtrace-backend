package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.community.CommentModerationView;

import java.util.List;

/** A comment with every report ever made on it, oldest first. */
public record CommentModerationDetail(CommentModerationView comment, String caseName, boolean casePublic,
                                      List<ContentReportView> reports) {
}
