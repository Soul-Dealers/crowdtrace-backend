package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;

/** Insert-only persistence fragment for case reviews. */
public interface CaseReviewWriter {
    CaseReview saveAndFlush(CaseReview review);
}
