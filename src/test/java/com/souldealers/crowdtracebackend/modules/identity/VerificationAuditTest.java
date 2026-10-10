package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerificationAuditTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository requestRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void approvalCommitsOneEventWithActorTargetAndMetadata() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        VerificationRequest request = saveRequest(applicant, VerificationStatus.PENDING);

        verificationService.approve(moderator.getEmail(), request.getId(), null);

        var event = jdbc.queryForMap("""
                SELECT actor_id, actor_role, action, target_type, target_id,
                       metadata->>'userId' AS metadata_user_id,
                       metadata->>'verificationType' AS metadata_verification_type
                  FROM audit_events
                 WHERE target_id = ?
                """, request.getId());
        assertThat(event).containsEntry("actor_id", moderator.getId())
                .containsEntry("actor_role", "MODERATOR")
                .containsEntry("action", "VERIFICATION.APPROVED")
                .containsEntry("target_type", "VERIFICATION_REQUEST")
                .containsEntry("target_id", request.getId())
                .containsEntry("metadata_user_id", applicant.getId().toString())
                .containsEntry("metadata_verification_type", "POLICE");
        assertThat(eventCount(request.getId())).isEqualTo(1);
        assertThat(statusOf(request.getId())).isEqualTo("APPROVED");
        assertThat(badgeOf(applicant.getId())).isEqualTo("POLICE");
    }

    @Test
    void rejectionCommitsOneEventWithActorTargetAndMetadataWithoutChangingBadge() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);
        VerificationRequest request = saveRequest(applicant, VerificationStatus.PENDING);

        verificationService.reject(superAdmin.getEmail(), request.getId(), null);

        var event = jdbc.queryForMap("""
                SELECT actor_id, actor_role, action, target_type, target_id,
                       metadata->>'userId' AS metadata_user_id,
                       metadata->>'verificationType' AS metadata_verification_type
                  FROM audit_events
                 WHERE target_id = ?
                """, request.getId());
        assertThat(event).containsEntry("actor_id", superAdmin.getId())
                .containsEntry("actor_role", "SUPER_ADMIN")
                .containsEntry("action", "VERIFICATION.REJECTED")
                .containsEntry("target_type", "VERIFICATION_REQUEST")
                .containsEntry("target_id", request.getId())
                .containsEntry("metadata_user_id", applicant.getId().toString())
                .containsEntry("metadata_verification_type", "POLICE");
        assertThat(eventCount(request.getId())).isEqualTo(1);
        assertThat(statusOf(request.getId())).isEqualTo("REJECTED");
        assertThat(badgeOf(applicant.getId())).isNull();
    }

    @Test
    void decidingAnAlreadyRejectedRequestWritesNoEvent() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        VerificationRequest request = saveRequest(applicant, VerificationStatus.REJECTED);

        assertThatThrownBy(() -> verificationService.reject(moderator.getEmail(), request.getId(), null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> verificationService.approve(moderator.getEmail(), request.getId(), null))
                .isInstanceOf(ConflictException.class);

        assertThat(statusOf(request.getId())).isEqualTo("REJECTED");
        assertThat(eventCount(request.getId())).isZero();
    }

    @Test
    void concurrentApprovalsCommitOneDecisionEvent() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User firstModerator = saveUser(UserRoles.MODERATOR);
        User secondModerator = saveUser(UserRoles.MODERATOR);
        VerificationRequest request = saveRequest(applicant, VerificationStatus.PENDING);
        CyclicBarrier startLine = new CyclicBarrier(2);
        List<Callable<Object>> work = List.of(
                () -> approveAfterBarrier(startLine, firstModerator, request.getId()),
                () -> approveAfterBarrier(startLine, secondModerator, request.getId()));
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> call : work) {
                futures.add(pool.submit(() -> {
                    try {
                        call.call();
                        return new Outcome(true, null);
                    } catch (Exception failure) {
                        return new Outcome(false, failure);
                    }
                }));
            }
            List<Outcome> outcomes = List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded())
                    .map(Outcome::failure)).singleElement().isInstanceOf(ConflictException.class);
            assertThat(statusOf(request.getId())).isEqualTo("APPROVED");
            assertThat(eventCount(request.getId())).isEqualTo(1);
            Long persistedReviewerId = jdbc.queryForObject(
                    "SELECT reviewer_id FROM verification_requests WHERE id = ?", Long.class, request.getId());
            Long eventActorId = jdbc.queryForObject(
                    "SELECT actor_id FROM audit_events WHERE target_id = ?", Long.class, request.getId());
            assertThat(eventActorId).isEqualTo(persistedReviewerId)
                    .isIn(List.of(firstModerator.getId(), secondModerator.getId()));
        } finally {
            pool.shutdownNow();
        }
    }

    private Object approveAfterBarrier(CyclicBarrier startLine, User moderator, long requestId) throws Exception {
        startLine.await(10, TimeUnit.SECONDS);
        return verificationService.approve(moderator.getEmail(), requestId, null);
    }

    private User saveUser(UserRoles role) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .email("verification-audit-" + suffix + "@example.com")
                .passwordHash("encoded-password")
                .displayName("Audit Actor " + suffix)
                .role(role)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private VerificationRequest saveRequest(User applicant, VerificationStatus status) {
        return requestRepository.saveAndFlush(VerificationRequest.builder()
                .user(applicant)
                .verificationType(VerificationType.POLICE)
                .evidenceReference("private/audit-evidence.pdf")
                .status(status)
                .build());
    }

    private int eventCount(long requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_id = ?", Integer.class, requestId);
    }

    private String statusOf(long requestId) {
        return jdbc.queryForObject("SELECT status FROM verification_requests WHERE id = ?", String.class, requestId);
    }

    private String badgeOf(long userId) {
        return jdbc.queryForObject("SELECT badge_type FROM users WHERE id = ?", String.class, userId);
    }

    private record Outcome(boolean succeeded, Exception failure) { }
}
