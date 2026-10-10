package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class VerificationRequestRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

    @AfterEach
    void cleanDetachedFixture() {
        verificationRequestRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void findsPendingRequestsInOldestFirstQueueOrder() {
        User user = userRepository.saveAndFlush(user("ada@example.com"));
        VerificationRequest oldest = verificationRequestRepository.save(request(
                user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));
        verificationRequestRepository.save(request(
                user, VerificationStatus.APPROVED, LocalDateTime.of(2026, 1, 1, 10, 0)));
        VerificationRequest newest = verificationRequestRepository.save(request(
                user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 11, 0)));
        verificationRequestRepository.flush();

        Page<VerificationRequest> pending = verificationRequestRepository
                .findPendingRequests(PageRequest.of(0, 10));

        assertThat(pending.getContent())
                .extracting(VerificationRequest::getId)
                .containsExactly(oldest.getId(), newest.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void queueQueryFetchesTheUserNeededForDetachedAdminMapping() {
        User user = userRepository.saveAndFlush(user("detached@example.com"));
        verificationRequestRepository.saveAndFlush(request(
                user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));

        VerificationRequest queued = verificationRequestRepository
                .findPendingRequests(PageRequest.of(0, 10))
                .getContent()
                .getFirst();

        assertThat(queued.getUser().getDisplayName()).isEqualTo("Ada");
    }

    @Test
    void persistsVerificationTypeAndStatusAsExplicitEnums() {
        User user = userRepository.saveAndFlush(user("ada@example.com"));
        VerificationRequest savedRequest = verificationRequestRepository.saveAndFlush(
                request(user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));

        VerificationRequest reloadedRequest = verificationRequestRepository
                .findById(savedRequest.getId())
                .orElseThrow();

        assertThat(reloadedRequest.getVerificationType()).isEqualTo(VerificationType.POLICE);
        assertThat(reloadedRequest.getStatus()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    void findsRequestsOwnedByAUser() {
        User user = userRepository.saveAndFlush(user("ada@example.com"));
        VerificationRequest request = verificationRequestRepository.saveAndFlush(
                request(user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));

        Page<VerificationRequest> ownedRequests = verificationRequestRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10));

        assertThat(ownedRequests.getContent()).containsExactly(request);
    }

    @Test
    void findsAllRequestsOwnedByAUserInNewestFirstOrder() {
        User user = userRepository.saveAndFlush(user("list@example.com"));
        VerificationRequest oldest = verificationRequestRepository.saveAndFlush(request(
                user, VerificationStatus.REJECTED, LocalDateTime.of(2026, 1, 1, 9, 0)));
        VerificationRequest newest = verificationRequestRepository.saveAndFlush(request(
                user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 11, 0)));

        assertThat(verificationRequestRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(VerificationRequest::getId)
                .containsExactly(newest.getId(), oldest.getId());
    }

    /** One badge per user: the slot check ignores type and looks only at the statuses asked for. */
    @Test
    void detectsAnActiveRequestForAUserWhateverItsStatusAmongThoseAsked() {
        User user = userRepository.saveAndFlush(user("exists@example.com"));
        verificationRequestRepository.saveAndFlush(request(
                user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));

        assertThat(verificationRequestRepository.existsByUserIdAndStatusIn(
                user.getId(), List.of(VerificationStatus.PENDING, VerificationStatus.APPROVED))).isTrue();
        assertThat(verificationRequestRepository.existsByUserIdAndStatusIn(
                user.getId(), List.of(VerificationStatus.APPROVED))).isFalse();
    }

    @Test
    void ignoresDecidedRequestsAndOtherUsersWhenCheckingTheActiveSlot() {
        User user = userRepository.saveAndFlush(user("freed@example.com"));
        User other = userRepository.saveAndFlush(user("other@example.com"));
        verificationRequestRepository.saveAndFlush(request(
                user, VerificationStatus.REJECTED, LocalDateTime.of(2026, 1, 1, 9, 0)));
        verificationRequestRepository.saveAndFlush(request(
                other, VerificationStatus.APPROVED, LocalDateTime.of(2026, 1, 1, 9, 0)));

        assertThat(verificationRequestRepository.existsByUserIdAndStatusIn(
                user.getId(), List.of(VerificationStatus.PENDING, VerificationStatus.APPROVED))).isFalse();
    }

    @Test
    void appliesOnlyTheFirstConditionalDecision() {
        User user = userRepository.saveAndFlush(user("decision@example.com"));
        VerificationRequest request = verificationRequestRepository.saveAndFlush(
                request(user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 1, 2, 9, 0);

        int firstUpdate = verificationRequestRepository.applyDecision(
                request.getId(), VerificationStatus.PENDING, VerificationStatus.APPROVED,
                user, "approved", reviewedAt);
        int secondUpdate = verificationRequestRepository.applyDecision(
                request.getId(), VerificationStatus.PENDING, VerificationStatus.REJECTED,
                user, "rejected", reviewedAt.plusMinutes(1));

        assertThat(firstUpdate).isEqualTo(1);
        assertThat(secondUpdate).isZero();
    }

    @Test
    void revokesOnlyAnApprovedRequestAndPreservesItsApprovalAttribution() {
        User applicant = userRepository.saveAndFlush(user("revocation-applicant@example.com"));
        User approver = userRepository.saveAndFlush(user("revocation-approver@example.com"));
        User revoker = userRepository.saveAndFlush(user("revocation-admin@example.com"));
        LocalDateTime approvedAt = LocalDateTime.of(2026, 1, 2, 9, 0);
        VerificationRequest approved = verificationRequestRepository.saveAndFlush(VerificationRequest.builder()
                .user(applicant)
                .verificationType(VerificationType.POLICE)
                .evidenceReference("evidence/identity-proof.pdf")
                .status(VerificationStatus.APPROVED)
                .reviewer(approver)
                .reviewNotes("approved")
                .reviewedAt(approvedAt)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build());
        LocalDateTime revokedAt = approvedAt.plusDays(1);

        int firstUpdate = verificationRequestRepository.applyRevocation(
                approved.getId(), revoker, "fraud", revokedAt);
        int secondUpdate = verificationRequestRepository.applyRevocation(
                approved.getId(), revoker, "second attempt", revokedAt.plusMinutes(1));

        VerificationRequest revoked = verificationRequestRepository.findById(approved.getId()).orElseThrow();
        assertThat(firstUpdate).isEqualTo(1);
        assertThat(secondUpdate).isZero();
        assertThat(revoked.getStatus()).isEqualTo(VerificationStatus.REVOKED);
        assertThat(revoked.getReviewer().getId()).isEqualTo(approver.getId());
        assertThat(revoked.getReviewNotes()).isEqualTo("approved");
        assertThat(revoked.getReviewedAt()).isEqualTo(approvedAt);
        assertThat(revoked.getRevokedBy().getId()).isEqualTo(revoker.getId());
        assertThat(revoked.getRevocationNotes()).isEqualTo("fraud");
        assertThat(revoked.getRevokedAt()).isEqualTo(revokedAt);

        User pendingApplicant = userRepository.saveAndFlush(user("pending-revocation-applicant@example.com"));
        VerificationRequest pending = verificationRequestRepository.saveAndFlush(request(
                pendingApplicant, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));
        assertThat(verificationRequestRepository.applyRevocation(
                pending.getId(), revoker, "fraud", revokedAt)).isZero();
    }

    private VerificationRequest request(User user, VerificationStatus status, LocalDateTime createdAt) {
        return VerificationRequest.builder()
                .user(user)
                .verificationType(VerificationType.POLICE)
                .evidenceReference("evidence/identity-proof.pdf")
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    private User user(String email) {
        return User.builder()
                .email(email)
                .passwordHash("encoded-password")
                .displayName("Ada")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build();
    }
}
