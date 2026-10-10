package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

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
class VerificationAuditMissingTest {

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

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void missingAuditEventRollsBackApprovalAndBadge() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        VerificationRequest request = savePendingRequest(applicant);

        assertThatThrownBy(() -> verificationService.approve(moderator.getEmail(), request.getId(), null))
                .isInstanceOf(AuditMissingException.class);

        assertThat(statusOf(request.getId())).isEqualTo("PENDING");
        assertThat(badgeOf(applicant.getId())).isNull();
        assertThat(eventCount(request.getId())).isZero();
    }

    private User saveUser(UserRoles role) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .email("verification-audit-missing-" + suffix + "@example.com")
                .passwordHash("encoded-password")
                .displayName("Audit Actor " + suffix)
                .role(role)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private VerificationRequest savePendingRequest(User applicant) {
        return requestRepository.saveAndFlush(VerificationRequest.builder()
                .user(applicant)
                .verificationType(VerificationType.POLICE)
                .evidenceReference("private/audit-missing-evidence.pdf")
                .status(VerificationStatus.PENDING)
                .build());
    }

    private String statusOf(long requestId) {
        return jdbc.queryForObject("SELECT status FROM verification_requests WHERE id = ?", String.class, requestId);
    }

    private String badgeOf(long userId) {
        return jdbc.queryForObject("SELECT badge_type FROM users WHERE id = ?", String.class, userId);
    }

    private int eventCount(long requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_id = ?", Integer.class, requestId);
    }
}
