package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.GrantVerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.SubmitVerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The failure map, at the service boundary.
 *
 * <p>{@link com.souldealers.crowdtracebackend.modules.identity.VerificationWorkflowTest}
 * drives the same rules over HTTP; these cover what a request cannot reach — a user
 * row that disappears mid-call, and the lost race where the conditional update matches
 * no rows because another administrator decided first.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceImplTest {

    private static final String ACTOR = "actor@example.com";

    @Mock
    private VerificationRequestRepository requestRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VerificationServiceImpl verificationService;

    @Test
    void refusesToSubmitForAPrincipalWithNoUserRow() {
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.submit(ACTOR,
                new SubmitVerificationRequest(VerificationType.NGO, "evidence")))
                .isInstanceOf(NotFoundException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesToSubmitWhileTheUserHoldsAnActiveRequestOfAnyType() {
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(requestRepository.existsByUserIdAndStatusIn(eq(1L), any())).thenReturn(true);

        assertThatThrownBy(() -> verificationService.submit(ACTOR,
                new SubmitVerificationRequest(VerificationType.NGO, "evidence")))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesToGrantWhileTheRecipientHoldsAnActiveRequestOfAnyType() {
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(userRepository.findByEmail("target@example.com"))
                .thenReturn(Optional.of(user(2L, UserStatus.ACTIVE)));
        when(requestRepository.existsByUserIdAndStatusIn(eq(2L), any())).thenReturn(true);

        assertThatThrownBy(() -> verificationService.grant(ACTOR, grantFor("target@example.com")))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).setBadgeType(anyLong(), any());
    }

    @Test
    void setsTheApplicantsBadgeWhenARequestIsApproved() {
        stubDecision(VerificationStatus.PENDING, VerificationStatus.APPROVED);

        verificationService.approve(ACTOR, 10L, null);

        verify(userRepository).setBadgeType(2L, VerificationType.NGO);
        verify(userRepository, never()).clearBadgeType(anyLong(), any());
    }

    @Test
    void clearsTheBadgeOnlyForTheRevokedRequestsTypeWhenItIsRevoked() {
        stubDecision(VerificationStatus.APPROVED, VerificationStatus.REVOKED);

        verificationService.revoke(ACTOR, 10L, null);

        verify(userRepository).clearBadgeType(2L, VerificationType.NGO);
        verify(userRepository, never()).setBadgeType(anyLong(), any());
        verify(requestRepository).applyRevocation(eq(10L), any(User.class), ArgumentMatchers.isNull(),
                any(LocalDateTime.class));
        verify(requestRepository, never()).applyDecision(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void leavesTheBadgeUntouchedWhenARequestIsRejected() {
        stubDecision(VerificationStatus.PENDING, VerificationStatus.REJECTED);

        verificationService.reject(ACTOR, 10L, null);

        verify(userRepository, never()).setBadgeType(anyLong(), any());
        verify(userRepository, never()).clearBadgeType(anyLong(), any());
    }

    private void stubDecision(VerificationStatus from, VerificationStatus to) {
        User actor = user(1L, UserStatus.ACTIVE);
        User owner = user(2L, UserStatus.ACTIVE);
        VerificationRequest request = pendingRequestOwnedBy(owner);
        request.setStatus(from);
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        if (to == VerificationStatus.REVOKED) {
            when(requestRepository.applyRevocation(eq(10L), eq(actor),
                    ArgumentMatchers.isNull(), any(LocalDateTime.class))).thenReturn(1);
        } else {
            when(requestRepository.applyDecision(eq(10L), eq(from), eq(to), eq(actor),
                    ArgumentMatchers.isNull(), any(LocalDateTime.class))).thenReturn(1);
        }
        when(requestRepository.findWithUserById(10L)).thenReturn(Optional.of(request));
    }

    @Test
    void refusesADecisionWhenTheConditionalUpdateMatchesNoRow() {
        User actor = user(1L, UserStatus.ACTIVE);
        VerificationRequest request = pendingRequestOwnedBy(user(2L, UserStatus.ACTIVE));
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(requestRepository.applyDecision(eq(10L), eq(VerificationStatus.PENDING),
                eq(VerificationStatus.APPROVED), eq(actor), ArgumentMatchers.isNull(), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> verificationService.approve(ACTOR, 10L, null))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).findWithUserById(anyLong());
    }

    @Test
    void refusesToDecideARequestThatDoesNotExist() {
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(requestRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.reject(ACTOR, 10L, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void refusesToDecideOnesOwnRequest() {
        User actor = user(1L, UserStatus.ACTIVE);
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(requestRepository.findById(10L)).thenReturn(Optional.of(pendingRequestOwnedBy(actor)));

        assertThatThrownBy(() -> verificationService.approve(ACTOR, 10L, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never()).applyDecision(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesToGrantABadgeToAnInactiveAccount() {
        User actor = user(1L, UserStatus.ACTIVE);
        User target = user(2L, UserStatus.INACTIVE);
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(userRepository.findByEmail("target@example.com")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> verificationService.grant(ACTOR, grantFor("target@example.com")))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesToGrantABadgeToASoftDeletedAccount() {
        User actor = user(1L, UserStatus.ACTIVE);
        User target = user(2L, UserStatus.ACTIVE);
        target.setDeletedAt(LocalDateTime.now());
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(userRepository.findByEmail("target@example.com")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> verificationService.grant(ACTOR, grantFor("target@example.com")))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesToGrantABadgeToAnUnknownRecipient() {
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(userRepository.findByEmail("target@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.grant(ACTOR, grantFor("target@example.com")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void translatesTheActiveRequestUniquenessViolationIntoAConflict() {
        User actor = user(1L, UserStatus.ACTIVE);
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(requestRepository.saveAndFlush(any())).thenThrow(uniquenessViolation(
                "duplicate key value violates unique constraint \"uq_verification_requests_active_user\""));

        assertThatThrownBy(() -> verificationService.submit(ACTOR,
                new SubmitVerificationRequest(VerificationType.NGO, "evidence")))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Only that one constraint becomes a 409. A different integrity failure is a real
     * fault and must keep surfacing as one rather than being reported as a duplicate.
     */
    @Test
    void leavesEveryOtherIntegrityViolationAlone() {
        User actor = user(1L, UserStatus.ACTIVE);
        when(userRepository.findByEmail(ACTOR)).thenReturn(Optional.of(actor));
        when(requestRepository.saveAndFlush(any())).thenThrow(uniquenessViolation(
                "null value in column \"evidence_reference\" violates not-null constraint"));

        assertThatThrownBy(() -> verificationService.submit(ACTOR,
                new SubmitVerificationRequest(VerificationType.NGO, "evidence")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }

    private static DataIntegrityViolationException uniquenessViolation(String causeMessage) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(causeMessage));
    }

    private static GrantVerificationRequest grantFor(String email) {
        return new GrantVerificationRequest(email, VerificationType.POLICE, "evidence", "notes");
    }

    private static VerificationRequest pendingRequestOwnedBy(User owner) {
        return VerificationRequest.builder()
                .id(10L)
                .user(owner)
                .verificationType(VerificationType.NGO)
                .evidenceReference("evidence")
                .status(VerificationStatus.PENDING)
                .build();
    }

    private static User user(Long id, UserStatus status) {
        return User.builder()
                .id(id)
                .email("user-" + id + "@example.com")
                .passwordHash("encoded-password")
                .displayName("User " + id)
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(status)
                .build();
    }
}
