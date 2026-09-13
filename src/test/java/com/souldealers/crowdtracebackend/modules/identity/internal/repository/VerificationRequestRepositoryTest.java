package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class VerificationRequestRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

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
    void persistsVerificationTypeAndStatusAsExplicitEnums() {
        User user = userRepository.saveAndFlush(user("ada@example.com"));
        VerificationRequest savedRequest = verificationRequestRepository.saveAndFlush(
                request(user, VerificationStatus.PENDING, LocalDateTime.of(2026, 1, 1, 9, 0)));

        VerificationRequest reloadedRequest = verificationRequestRepository
                .findById(savedRequest.getId())
                .orElseThrow();

        assertThat(reloadedRequest.getVerificationType()).isEqualTo(VerificationType.IDENTITY);
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

    private VerificationRequest request(User user, VerificationStatus status, LocalDateTime createdAt) {
        return VerificationRequest.builder()
                .user(user)
                .verificationType(VerificationType.IDENTITY)
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
