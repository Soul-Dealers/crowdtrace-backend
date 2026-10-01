package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CT-010 role policy: who may reach each of the seven verification paths.
 *
 * <p>Split from {@link VerificationWorkflowTest}, which owns ownership, state and
 * projection. Both classes carry identical annotations so they share one application
 * context.
 *
 * <p>Permitted cells assert the exact success status against a real fixture rather
 * than "not 403" — a 404, 409 or 500 would also satisfy a negative assertion while
 * proving nothing about authorization.
 */
@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationAuthorizationTest {

    private static final String SUBMIT = "/api/v1/verification-requests";
    private static final String OWN = "/api/v1/verification-requests/me";
    private static final String QUEUE = "/api/v1/admin/verification-requests";
    private static final String GRANT = "/api/v1/admin/verification-grants";
    private static final String USERS = "/api/v1/auth/users";
    private static final String SUBMIT_BODY = """
            {"verificationType":"NGO","evidenceReference":"authorization fixture"}""";
    /**
     * Denied cells send a body that would validate, because {@code @PreAuthorize} is a
     * proxy around the controller method and therefore runs <em>after</em> argument
     * resolution: an invalid body answers 400 before the role check is ever reached.
     * Asserting 403 against a valid body is what proves the role policy.
     */
    private static final String GRANT_BODY = """
            {"email":"grant-target@example.com","verificationType":"NGO",
             "evidenceReference":"authorization fixture"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private NotificationService notificationService;

    @ParameterizedTest(name = "{1} {0} without a token is 401")
    @MethodSource("everyVerificationPath")
    void requiresAuthenticationOnEveryVerificationPath(String path, HttpMethod method) throws Exception {
        mockMvc.perform(request(method, path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static Stream<Arguments> everyVerificationPath() {
        return Stream.of(
                Arguments.of(SUBMIT, HttpMethod.POST),
                Arguments.of(OWN, HttpMethod.GET),
                Arguments.of(QUEUE, HttpMethod.GET),
                Arguments.of(decisionPath(1L, "approve"), HttpMethod.POST),
                Arguments.of(decisionPath(1L, "reject"), HttpMethod.POST),
                Arguments.of(decisionPath(1L, "revoke"), HttpMethod.POST),
                Arguments.of(GRANT, HttpMethod.POST));
    }

    @ParameterizedTest(name = "a registered user is forbidden from {0}")
    @MethodSource("everyAdminPath")
    void deniesEveryAdminPathToRegisteredUsers(String path, HttpMethod method) throws Exception {
        mockMvc.perform(authorized(method, path, UserRoles.REGISTERED_USER))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "a moderator is forbidden from {0}")
    @MethodSource("superAdminOnlyPaths")
    void deniesRevocationAndDirectGrantsToModerators(String path, HttpMethod method) throws Exception {
        mockMvc.perform(authorized(method, path, UserRoles.MODERATOR))
                .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> everyAdminPath() {
        return Stream.concat(
                Stream.of(
                        Arguments.of(QUEUE, HttpMethod.GET),
                        Arguments.of(decisionPath(1L, "approve"), HttpMethod.POST),
                        Arguments.of(decisionPath(1L, "reject"), HttpMethod.POST)),
                superAdminOnlyPaths());
    }

    private static Stream<Arguments> superAdminOnlyPaths() {
        return Stream.of(
                Arguments.of(decisionPath(1L, "revoke"), HttpMethod.POST),
                Arguments.of(GRANT, HttpMethod.POST));
    }

    /**
     * Review Focus #6 — the badge is a trust signal, never a permission. An approved
     * holder is still a REGISTERED_USER everywhere a role is checked.
     */
    @ParameterizedTest(name = "an approved badge still cannot reach {0}")
    @MethodSource("everyAdminPathAndTheUserListing")
    void refusesAdminPathsToAnApprovedBadgeHolder(String path, HttpMethod method) throws Exception {
        User holder = saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE);
        saveRequest(holder, VerificationType.POLICE, VerificationStatus.APPROVED);

        mockMvc.perform(request(method, path)
                        .header("Authorization", "Bearer " + tokenFor(holder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFor(path)))
                .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> everyAdminPathAndTheUserListing() {
        return Stream.concat(everyAdminPath(), Stream.of(Arguments.of(USERS, HttpMethod.GET)));
    }

    @ParameterizedTest(name = "{0} may submit an application")
    @EnumSource(UserRoles.class)
    void allowsEveryAuthenticatedRoleToApply(UserRoles role) throws Exception {
        mockMvc.perform(request(HttpMethod.POST, SUBMIT)
                        .header("Authorization", "Bearer " + tokenFor(saveUser(role, UserStatus.ACTIVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMIT_BODY))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "a moderator may {0} a pending request")
    @MethodSource("moderatorDecisions")
    void allowsModeratorsToDecidePendingRequests(String action) throws Exception {
        VerificationRequest pending = saveRequest(
                saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE),
                VerificationType.NGO, VerificationStatus.PENDING);

        mockMvc.perform(authorized(HttpMethod.POST, decisionPath(pending.getId(), action), UserRoles.MODERATOR))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "a super admin may {0} a pending request")
    @MethodSource("moderatorDecisions")
    void allowsSuperAdminsToDecidePendingRequests(String action) throws Exception {
        VerificationRequest pending = saveRequest(
                saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE),
                VerificationType.NGO, VerificationStatus.PENDING);

        mockMvc.perform(authorized(HttpMethod.POST, decisionPath(pending.getId(), action), UserRoles.SUPER_ADMIN))
                .andExpect(status().isOk());
    }

    private static Stream<String> moderatorDecisions() {
        return Stream.of("approve", "reject");
    }

    @Test
    void allowsASuperAdminToRevokeAnApprovedBadge() throws Exception {
        VerificationRequest approved = saveRequest(
                saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE),
                VerificationType.POLICE, VerificationStatus.APPROVED);

        mockMvc.perform(authorized(HttpMethod.POST, decisionPath(approved.getId(), "revoke"), UserRoles.SUPER_ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void allowsASuperAdminToGrantABadgeDirectly() throws Exception {
        User target = saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE);

        mockMvc.perform(request(HttpMethod.POST, GRANT)
                        .header("Authorization", "Bearer "
                                + tokenFor(saveUser(UserRoles.SUPER_ADMIN, UserStatus.ACTIVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"POLICE",
                                 "evidenceReference":"Warrant card verified in person."}"""
                                .formatted(target.getEmail())))
                .andExpect(status().isOk());
    }

    @Test
    void allowsAModeratorToReadTheQueue() throws Exception {
        mockMvc.perform(authorized(HttpMethod.GET, QUEUE, UserRoles.MODERATOR))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
            HttpMethod method, String path, UserRoles role) {
        return request(method, path)
                .header("Authorization", "Bearer " + tokenFor(saveUser(role, UserStatus.ACTIVE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(path));
    }

    private static String bodyFor(String path) {
        return GRANT.equals(path) ? GRANT_BODY : "{}";
    }

    private static String decisionPath(Long id, String action) {
        return "/api/v1/admin/verification-requests/" + id + "/" + action;
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(new SecurityUser(user));
    }

    private User saveUser(UserRoles role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .email("verification-authz-" + UUID.randomUUID() + "@example.com")
                .passwordHash("encoded-password")
                .displayName(role.name())
                .role(role)
                .accountStatus(status)
                .build());
    }

    private VerificationRequest saveRequest(User user, VerificationType type, VerificationStatus status) {
        return verificationRequestRepository.saveAndFlush(VerificationRequest.builder()
                .user(user)
                .verificationType(type)
                .evidenceReference("private/evidence.pdf")
                .status(status)
                .reviewedAt(status == VerificationStatus.PENDING ? null : LocalDateTime.now())
                .build());
    }
}
