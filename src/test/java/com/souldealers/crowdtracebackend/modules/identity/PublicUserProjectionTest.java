package com.souldealers.crowdtracebackend.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-009: "public responses expose only the pseudonym and permitted badge metadata".
 *
 * <p>The public projection is a module-boundary seam rather than an HTTP endpoint —
 * {@code /api/public/**} belongs to discovery (phase 4), and no consumer exists yet.
 * These tests pin that the projection reads the badge from {@code users.badge_type}
 * (a user holds at most one), the badge type, and the serialized shape. How the column
 * is written is covered by {@link VerificationWorkflowTest}; request rows alone no
 * longer influence what the projection shows.
 */
@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@ActiveProfiles("test")
class PublicUserProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void exposesThePseudonymForAUserWithNoBadge() {
        User user = saveUser("no-badge@example.com", "Quiet Contributor", null);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.displayName()).isEqualTo("Quiet Contributor");
        assertThat(response.verified()).isFalse();
        assertThat(response.badgeType()).isNull();
    }

    @ParameterizedTest
    @EnumSource(VerificationType.class)
    void showsTheBadgeTypeStoredOnTheUser(VerificationType type) {
        User user = saveUser(type.name().toLowerCase() + "@example.com", "Badged Contributor", type);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isTrue();
        assertThat(response.badgeType()).isEqualTo(type);
    }

    /**
     * Rows in the request history are not the source of display: an approved request
     * with no badge on the user row shows nothing, because the workflow sets the column
     * in the same transaction as the decision and nothing else is consulted.
     */
    @Test
    void readsTheColumnAndNotTheRequestHistory() {
        User user = saveUser("history-only@example.com", "History Only", null);
        jdbcTemplate.update("""
                INSERT INTO verification_requests (user_id, verification_type, evidence_reference, status, created_at)
                VALUES (?, 'POLICE', 'private/evidence.pdf', 'APPROVED', CURRENT_TIMESTAMP)
                """, user.getId());

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isFalse();
        assertThat(response.badgeType()).isNull();
    }

    @Test
    void keepsPrivateIdentityFieldsOutOfTheSerializedProjection() throws Exception {
        User user = saveUser("private-fields@example.com", "Public Pseudonym", VerificationType.POLICE);

        String json = objectMapper.writeValueAsString(userService.getPublicProfile(user.getId()));

        // Asserted as a whole document rather than as absent substrings: a display
        // name like "David" contains "id" and "Carole" contains "role", so absence
        // checks against a small record would pass or fail on the fixture name.
        // Equality also catches fields nobody thought to enumerate.
        assertThat(json).isEqualTo(
                "{\"displayName\":\"Public Pseudonym\",\"verified\":true,\"badgeType\":\"POLICE\"}");
    }

    private User saveUser(String email, String displayName, VerificationType badgeType) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash("encoded-password")
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .badgeType(badgeType)
                .build());
    }
}
