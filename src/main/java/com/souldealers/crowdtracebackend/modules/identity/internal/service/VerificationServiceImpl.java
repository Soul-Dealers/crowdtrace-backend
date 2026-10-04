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
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.USER_NOT_FOUND_MSG;

@Service
@RequiredArgsConstructor
public class VerificationServiceImpl implements VerificationService {
    private static final Logger log = LoggerFactory.getLogger(VerificationServiceImpl.class);
    private static final String SLOT_TAKEN_MESSAGE =
            "You already hold a verification badge or have a pending request";
    private static final String GRANT_SLOT_TAKEN_MESSAGE =
            "The user already holds a verification badge or has a pending request";
    private static final Set<VerificationStatus> ACTIVE_STATUSES =
            EnumSet.of(VerificationStatus.PENDING, VerificationStatus.APPROVED);
    private static final String ACTIVE_REQUEST_UNIQUE_INDEX = "uq_verification_requests_active_user";

    private final VerificationRequestRepository requestRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public VerificationRequestResponse submit(String actorEmail, SubmitVerificationRequest request) {
        User user = findUser(actorEmail);
        if (hasActiveRequest(user.getId())) {
            throw new ConflictException(SLOT_TAKEN_MESSAGE);
        }

        VerificationRequest saved = saveActiveRequest(VerificationRequest.builder()
                .user(user)
                .verificationType(request.verificationType())
                .evidenceReference(request.evidenceReference())
                .status(VerificationStatus.PENDING)
                .build(), SLOT_TAKEN_MESSAGE);
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
        if (hasActiveRequest(target.getId())) {
            throw new ConflictException(GRANT_SLOT_TAKEN_MESSAGE);
        }

        LocalDateTime reviewedAt = LocalDateTime.now();
        VerificationRequest saved = saveActiveRequest(VerificationRequest.builder()
                .user(target)
                .verificationType(request.verificationType())
                .evidenceReference(request.evidenceReference())
                .status(VerificationStatus.APPROVED)
                .reviewer(actor)
                .reviewNotes(request.reviewNotes())
                .reviewedAt(reviewedAt)
                .build(), GRANT_SLOT_TAKEN_MESSAGE);
        userRepository.setBadgeType(target.getId(), request.verificationType());
        logDecision(saved.getId(), actor, "grant", null, VerificationStatus.APPROVED);
        return toAdminResponse(saved);
    }

    /** A user holds one badge, so any PENDING or APPROVED request, of any type, takes the slot. */
    private boolean hasActiveRequest(Long userId) {
        return requestRepository.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
    }

    /**
     * Inserts an active request, letting the database settle a race the checks above
     * cannot.
     *
     * <p>The active-request check is check-then-act: two concurrent submits, two grants, or
     * a submit racing a grant all observe "nothing exists" and both insert, because
     * neither transaction can see the other's uncommitted row. The partial unique index
     * (one active request per user, any type)
     * {@code uq_verification_requests_active_user} rejects the loser, and this turns
     * that rejection into the same 409 the ordinary duplicate path returns. The checks
     * stay because they produce the clearer message on the common, uncontended path.
     *
     * <p>Flushed explicitly: without it the insert happens at commit, outside this
     * method, and the violation would surface as a 500 instead. Only this constraint is
     * translated — every other integrity failure keeps its existing handling.
     */
    private VerificationRequest saveActiveRequest(VerificationRequest request, String conflictMessage) {
        try {
            return requestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException exception) {
            if (violatesActiveRequestUniqueness(exception)) {
                throw new ConflictException(conflictMessage);
            }
            throw exception;
        }
    }

    private static boolean violatesActiveRequestUniqueness(DataIntegrityViolationException exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        String message = cause.getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT).contains(ACTIVE_REQUEST_UNIQUE_INDEX);
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
        syncBadge(request, to);
        logDecision(requestId, actor, action, from, to);
        VerificationRequest refreshed = requestRepository.findWithUserById(requestId)
                .orElseThrow(() -> new NotFoundException("Verification request not found"));
        return toAdminResponse(refreshed);
    }

    /**
     * Keeps {@code users.badge_type} in step with the decision, inside the decision's
     * transaction. Approve sets it, revoke clears it (only while it still holds this
     * request's type), reject leaves it alone. Bulk updates, because the decision query
     * clears the persistence context and any loaded User is detached by now.
     */
    private void syncBadge(VerificationRequest request, VerificationStatus to) {
        Long userId = request.getUser().getId();
        if (to == VerificationStatus.APPROVED) {
            userRepository.setBadgeType(userId, request.getVerificationType());
        } else if (to == VerificationStatus.REVOKED) {
            userRepository.clearBadgeType(userId, request.getVerificationType());
        }
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
