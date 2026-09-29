package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.AdminVerificationRequestResponse;
import com.souldealers.crowdtracebackend.modules.identity.GrantVerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.SubmitVerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.VerificationDecisionRequest;
import com.souldealers.crowdtracebackend.modules.identity.VerificationRequestResponse;
import com.souldealers.crowdtracebackend.modules.identity.VerificationService;
import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.USER_NOT_FOUND_MSG;

@Service
@RequiredArgsConstructor
public class VerificationServiceImpl implements VerificationService {
    private static final Logger log = LoggerFactory.getLogger(VerificationServiceImpl.class);

    private final VerificationRequestRepository requestRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public VerificationRequestResponse submit(String actorEmail, SubmitVerificationRequest request) {
        User user = findUser(actorEmail);
        if (requestRepository.existsByUserIdAndVerificationTypeAndStatus(
                user.getId(), request.verificationType(), VerificationStatus.PENDING)) {
            throw new ConflictException("A pending verification request already exists for this type");
        }
        if (requestRepository.existsByUserIdAndVerificationTypeAndStatus(
                user.getId(), request.verificationType(), VerificationStatus.APPROVED)) {
            throw new ConflictException("An approved badge already exists for this type");
        }

        VerificationRequest saved = requestRepository.save(VerificationRequest.builder()
                .user(user)
                .verificationType(request.verificationType())
                .evidenceReference(request.evidenceReference())
                .status(VerificationStatus.PENDING)
                .build());
        return toOwnResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationRequestResponse> getOwnRequests(String actorEmail) {
        User user = findUser(actorEmail);
        return requestRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(this::toOwnResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminVerificationRequestResponse> getPendingQueue(Pageable pageable) {
        return PagedResponse.from(requestRepository.findByStatusOrderByCreatedAtAsc(
                VerificationStatus.PENDING, pageable).map(this::toAdminResponse));
    }

    @Override
    @Transactional
    public AdminVerificationRequestResponse approve(String actorEmail, Long requestId,
                                                    VerificationDecisionRequest decision) {
        return decide(actorEmail, requestId, VerificationStatus.PENDING, VerificationStatus.APPROVED,
                decision == null ? null : decision.reviewNotes(), "approve");
    }

    @Override
    @Transactional
    public AdminVerificationRequestResponse reject(String actorEmail, Long requestId,
                                                   VerificationDecisionRequest decision) {
        return decide(actorEmail, requestId, VerificationStatus.PENDING, VerificationStatus.REJECTED,
                decision == null ? null : decision.reviewNotes(), "reject");
    }

    @Override
    @Transactional
    public AdminVerificationRequestResponse revoke(String actorEmail, Long requestId,
                                                   VerificationDecisionRequest decision) {
        return decide(actorEmail, requestId, VerificationStatus.APPROVED, VerificationStatus.REVOKED,
                decision == null ? null : decision.reviewNotes(), "revoke");
    }

    @Override
    @Transactional
    public AdminVerificationRequestResponse grant(String actorEmail, GrantVerificationRequest request) {
        User actor = findUser(actorEmail);
        User target = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));
        if (actor.getId().equals(target.getId())) {
            throw new AccessDeniedException("Administrators cannot grant their own badge");
        }
        if (target.getAccountStatus() != UserStatus.ACTIVE || target.getDeletedAt() != null) {
            throw new ConflictException("Verification can only be granted to an active user");
        }
        if (requestRepository.existsByUserIdAndVerificationTypeAndStatus(
                target.getId(), request.verificationType(), VerificationStatus.PENDING)
                || requestRepository.existsByUserIdAndVerificationTypeAndStatus(
                target.getId(), request.verificationType(), VerificationStatus.APPROVED)) {
            throw new ConflictException("A pending or approved verification already exists for this type");
        }

        LocalDateTime reviewedAt = LocalDateTime.now();
        VerificationRequest saved = requestRepository.save(VerificationRequest.builder()
                .user(target)
                .verificationType(request.verificationType())
                .evidenceReference(request.evidenceReference())
                .status(VerificationStatus.APPROVED)
                .reviewer(actor)
                .reviewNotes(request.reviewNotes())
                .reviewedAt(reviewedAt)
                .build());
        logDecision(saved.getId(), actor, "grant", null, VerificationStatus.APPROVED);
        return toAdminResponse(saved);
    }

    private AdminVerificationRequestResponse decide(String actorEmail, Long requestId,
                                                     VerificationStatus from, VerificationStatus to,
                                                     String reviewNotes, String action) {
        User actor = findUser(actorEmail);
        VerificationRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Verification request not found"));
        if (actor.getId().equals(request.getUser().getId())) {
            throw new AccessDeniedException("Administrators cannot decide their own request");
        }
        if (request.getStatus() != from) {
            throw new ConflictException("Verification request is not in the required state");
        }
        LocalDateTime reviewedAt = LocalDateTime.now();
        int updated = requestRepository.applyDecision(requestId, from, to, actor, reviewNotes, reviewedAt);
        if (updated == 0) {
            throw new ConflictException("Verification request was already decided");
        }
        logDecision(requestId, actor, action, from, to);
        VerificationRequest refreshed = requestRepository.findWithUserById(requestId)
                .orElseThrow(() -> new NotFoundException("Verification request not found"));
        return toAdminResponse(refreshed);
    }

    private void logDecision(Long requestId, User actor, String action,
                             VerificationStatus from, VerificationStatus to) {
        log.info("verification_decision requestId={} action={} transition={} actorId={}",
                requestId, action, from == null ? "NONE->" + to : from + "->" + to, actor.getId());
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));
    }

    private VerificationRequestResponse toOwnResponse(VerificationRequest request) {
        return new VerificationRequestResponse(request.getId(), request.getVerificationType(),
                request.getEvidenceReference(), request.getStatus(), request.getCreatedAt(), request.getReviewedAt());
    }

    private AdminVerificationRequestResponse toAdminResponse(VerificationRequest request) {
        return new AdminVerificationRequestResponse(request.getId(), request.getUser().getId(),
                request.getUser().getDisplayName(), request.getVerificationType(), request.getEvidenceReference(),
                request.getStatus(), request.getReviewNotes(), request.getCreatedAt(), request.getReviewedAt());
    }
}
