package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;

/**
 * Authorizes file downloads, then issues short-lived signed URLs. Storage keys never leave the module.
 *
 * @throws com.souldealers.crowdtracebackend.shared.FileAccessUnavailableException from the URL methods when the
 *         configured storage cannot sign URLs
 */
public interface CaseFileAccessService {

    /** A live public photo of an approved case; anything else is not found. */
    SignedFileUrl publicPhotoUrl(Long caseId, Long fileId);

    /** Any live file attached to the case, for moderators. Records the grant in the audit log before returning. */
    @RequiresModerator
    SignedFileUrl reviewerFileUrl(String actorEmail, Long caseId, Long fileId);

    boolean signedAccessAvailable();
}
