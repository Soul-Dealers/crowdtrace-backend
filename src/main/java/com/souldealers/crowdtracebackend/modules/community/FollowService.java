package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

/** userId must come from the authenticated principal, never the request. */
public interface FollowService {

    /** Follows an approved case; repeat calls change nothing. Throws NotFoundException for any other case. */
    void follow(Long caseId, Long userId);

    /** Removes the caller's follow, whatever the case's state now. Repeat calls are harmless. */
    void unfollow(Long caseId, Long userId);

    /**
     * One page of the caller's follows, newest first. Cases no longer public are left out, so a page can hold fewer
     * than size items, or none, while hasNext is still true.
     */
    CursorResponse<FollowedCaseResponse> listOwn(Long userId, String cursor, int size);
}
