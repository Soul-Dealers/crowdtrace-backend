package com.souldealers.crowdtracebackend.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-009: "public responses expose only the pseudonym and permitted badge metadata".
 *
 * <p>The public projection is a module-boundary seam rather than an HTTP endpoint —
 * {@code /api/public/**} belongs to discovery (phase 4), and no consumer exists yet.
 * These tests pin the badge rule, the badge type, and the serialized shape.
 *
 * <p>The badge rule is per type: for each verification type, the most recently decided
 * request wins, and the badge shows if any type's latest decision is APPROVED. A user
 * may hold an IDENTITY badge and separately apply as an ORGANIZATION, so a single
 * "latest decided request overall" rule would let a rejected second application strip
 * a valid first badge.
 */
@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@ActiveProfiles("test")
class PublicUserProjectionTest {

    private static final LocalDateTime JANUARY = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime JUNE = LocalDateTime.of(2026, 6, 1, 12, 0);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void exposesThePseudonymForAUserWithNoVerificationRequest() {
        User user = saveUser("no-request@example.com", "Quiet Contributor");

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.displayName()).isEqualTo("Quiet Contributor");
        assertThat(response.verified()).isFalse();
        assertThat(response.badgeType()).isNull();
    }

    @Test
    void showsTheBadgeTypeTheAdministratorApproved() {
        User user = saveUser("approved@example.com", "Approved Contributor");
        decide(user, VerificationType.ORGANIZATION, VerificationStatus.APPROVED, JANUARY);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isTrue();
        assertThat(response.badgeType()).isEqualTo(VerificationType.ORGANIZATION);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {"PENDING", "REJECTED", "REVOKED"})
    void withholdsTheBadgeForEveryStatusOtherThanApproved(VerificationStatus status) {
        User user = saveUser(status.name().toLowerCase() + "@example.com", "Unbadged Contributor");
        decide(user, VerificationType.IDENTITY, status, JANUARY);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isFalse();
        assertThat(response.badgeType()).isNull();
    }

    @Test
    void dropsTheBadgeWhenALaterDecisionRevokesTheSameType() {
        User user = saveUser("revoked-later@example.com", "Revoked Contributor");
        decide(user, VerificationType.IDENTITY, VerificationStatus.APPROVED, JANUARY);
        decide(user, VerificationType.IDENTITY, VerificationStatus.REVOKED, JUNE);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isFalse();
        assertThat(response.badgeType()).isNull();
    }

    @Test
    void keepsAnApprovedBadgeWhenALaterApplicationOfAnotherTypeIsRejected() {
        User user = saveUser("mixed-outcome@example.com", "Mixed Outcome Contributor");
        decide(user, VerificationType.IDENTITY, VerificationStatus.APPROVED, JANUARY);
        decide(user, VerificationType.ORGANIZATION, VerificationStatus.REJECTED, JUNE);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isTrue();
        assertThat(response.badgeType()).isEqualTo(VerificationType.IDENTITY);
    }

    @Test
    void ignoresAStillPendingApplicationAlongsideAnApprovedBadge() {
        User user = saveUser("pending-second@example.com", "Applying Again");
        decide(user, VerificationType.IDENTITY, VerificationStatus.APPROVED, JANUARY);
        pending(user, VerificationType.ORGANIZATION);

        PublicUserResponse response = userService.getPublicProfile(user.getId());

        assertThat(response.verified()).isTrue();
        assertThat(response.badgeType()).isEqualTo(VerificationType.IDENTITY);
    }

    @Test
    void keepsPrivateIdentityFieldsOutOfTheSerializedProjection() throws Exception {
        User user = saveUser("private-fields@example.com", "Public Pseudonym");
        decide(user, VerificationType.IDENTITY, VerificationStatus.APPROVED, JANUARY);

        String json = objectMapper.writeValueAsString(userService.getPublicProfile(user.getId()));

        // Asserted as a whole document rather than as absent substrings: a display
        // name like "David" contains "id" and "Carole" contains "role", so absence
        // checks against a small record would pass or fail on the fixture name.
        // Equality also catches fields nobody thought to enumerate.
        assertThat(json).isEqualTo(
                "{\"displayName\":\"Public Pseudonym\",\"verified\":true,\"badgeType\":\"IDENTITY\"}");
    }

    private User saveUser(String email, String displayName) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash("encoded-password")
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private void decide(
            User user,
            VerificationType type,
            VerificationStatus status,
            LocalDateTime decidedAt) {
        verificationRequestRepository.saveAndFlush(VerificationRequest.builder()
                .user(user)
                .verificationType(type)
                .evidenceReference("private/evidence.pdf")
                .status(status)
                .createdAt(decidedAt.minusDays(1))
                // PENDING rows are never "decided"; the repository filters them out, so
                // leaving reviewedAt set here would not smuggle them into the result.
                .reviewedAt(status == VerificationStatus.PENDING ? null : decidedAt)
                .build());
    }

    private void pending(User user, VerificationType type) {
        decide(user, type, VerificationStatus.PENDING, JUNE.plusDays(1));
    }
}
