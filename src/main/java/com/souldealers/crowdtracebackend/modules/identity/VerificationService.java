package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface VerificationService {
    VerificationRequestResponse submit(String actorEmail, SubmitVerificationRequest request);

    List<VerificationRequestResponse> getOwnRequests(String actorEmail);

    PagedResponse<AdminVerificationRequestResponse> getPendingQueue(Pageable pageable);

    AdminVerificationRequestResponse approve(String actorEmail, Long requestId,
                                             VerificationDecisionRequest decision);

    AdminVerificationRequestResponse reject(String actorEmail, Long requestId,
                                            VerificationDecisionRequest decision);

    AdminVerificationRequestResponse revoke(String actorEmail, Long requestId,
                                            VerificationDecisionRequest decision);

    AdminVerificationRequestResponse grant(String actorEmail, GrantVerificationRequest request);
}
