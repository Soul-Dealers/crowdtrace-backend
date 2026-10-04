package com.souldealers.crowdtracebackend.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CT-010 acceptance: ownership, state transitions, response projection, and the audit
 * trail, exercised over HTTP against the full context.
 *
 * <p>Deliberately not {@code @Transactional}. A test-level transaction would keep the
 * persistence context open and hide exactly the stale-instance and lazy-loading faults
 * that {@code open-in-view: false} exposes in production. Fixtures are isolated by
 * UUID email instead.
 *
 * <p>The admin queue is global and shared with every other test in this context, so no
 * assertion here depends on its size, ordering, or first element — rows are located by
 * id within a deliberately large page.
 */
@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class VerificationWorkflowTest {

    private static final String SUBMIT = "/api/v1/verification-requests";
    private static final String OWN = "/api/v1/verification-requests/me";
    private static final String QUEUE = "/api/v1/admin/verification-requests?page=0&size=200";
    private static final String GRANT = "/api/v1/admin/verification-grants";
    private static final String ME = "/api/v1/auth/me";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationService notificationService;

    // --- Ownership ----------------------------------------------------------

    @Test
    void showsAnApplicantOnlyTheirOwnRequests() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User bystander = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.NGO, "applicant evidence");

        mockMvc.perform(get(OWN).header("Authorization", bearer(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(requestId)).exists());

        mockMvc.perform(get(OWN).header("Authorization", bearer(bystander)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(requestId)).doesNotExist());
    }

    @Test
    void refusesASecondApplicationWhileOneIsPending() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        submit(applicant, VerificationType.NGO, "first application");

        mockMvc.perform(post(SUBMIT).header("Authorization", bearer(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(VerificationType.NGO, "second application")))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesAnApplicationForATypeAlreadyApproved() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        saveRequest(applicant, VerificationType.POLICE, VerificationStatus.APPROVED);

        mockMvc.perform(post(SUBMIT).header("Authorization", bearer(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(VerificationType.POLICE, "already badged")))
                .andExpect(status().isConflict());
    }

    @Test
    void allowsAFreshApplicationAfterARevocation() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        saveRequest(applicant, VerificationType.POLICE, VerificationStatus.REVOKED);

        mockMvc.perform(post(SUBMIT).header("Authorization", bearer(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(VerificationType.POLICE, "re-application")))
                .andExpect(status().isCreated());
    }

    // --- One badge per user -------------------------------------------------

    /** A user holds at most one badge, so a badge holder cannot take a second one of another type. */
    @Test
    void refusesABadgeHolderApplyingForADifferentType() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.POLICE, "first badge");
        decide(saveUser(UserRoles.MODERATOR), requestId, "approve", null);

        mockMvc.perform(post(SUBMIT).header("Authorization", bearer(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(VerificationType.NGO, "second badge")))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesAPendingApplicantApplyingForADifferentType() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        submit(applicant, VerificationType.POLICE, "first application");

        mockMvc.perform(post(SUBMIT).header("Authorization", bearer(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(VerificationType.NGO, "second application")))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesAGrantOfADifferentTypeToABadgeHolder() throws Exception {
        User holder = saveUser(UserRoles.REGISTERED_USER);
        decide(saveUser(UserRoles.MODERATOR), submit(holder, VerificationType.POLICE, "held"), "approve", null);

        mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(saveUser(UserRoles.SUPER_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"NGO",
                                 "evidenceReference":"second badge"}""".formatted(holder.getEmail())))
                .andExpect(status().isConflict());

        assertThat(badgeColumn(holder)).isEqualTo("POLICE");
    }

    /** Revocation frees the slot: the same user may then apply, even for another type. */
    @Test
    void letsAUserApplyAgainAfterRevocationOfTheirBadge() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        long policeId = submit(applicant, VerificationType.POLICE, "first badge");
        decide(moderator, policeId, "approve", null);
        decide(saveUser(UserRoles.SUPER_ADMIN), policeId, "revoke", null);

        long ngoId = submit(applicant, VerificationType.NGO, "second chance");
        decide(moderator, ngoId, "approve", null);

        assertThat(badgeColumn(applicant)).isEqualTo("NGO");
    }

    @Test
    void letsAUserApplyAgainAfterARejection() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        decide(saveUser(UserRoles.MODERATOR), submit(applicant, VerificationType.POLICE, "refused"), "reject", null);

        submit(applicant, VerificationType.NGO, "second chance");
    }

    // --- users.badge_type is written with the decision ----------------------

    @Test
    void setsTheStoredBadgeWhenARequestIsApprovedAndClearsItWhenRevoked() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.SUBJECT_MATTER_EXPERT, "stored badge");
        assertThat(badgeColumn(applicant)).isNull();

        decide(saveUser(UserRoles.MODERATOR), requestId, "approve", null);
        assertThat(badgeColumn(applicant)).isEqualTo("SUBJECT_MATTER_EXPERT");

        decide(saveUser(UserRoles.SUPER_ADMIN), requestId, "revoke", null);
        assertThat(badgeColumn(applicant)).isNull();
    }

    @Test
    void setsTheStoredBadgeOnADirectGrantAndClearsItWhenThatGrantIsRevoked() throws Exception {
        User target = saveUser(UserRoles.REGISTERED_USER);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        MvcResult result = mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"NGO",
                                 "evidenceReference":"granted"}""".formatted(target.getEmail())))
                .andExpect(status().isOk())
                .andReturn();
        long requestId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(badgeColumn(target)).isEqualTo("NGO");

        decide(superAdmin, requestId, "revoke", null);
        assertThat(badgeColumn(target)).isNull();
    }

    @Test
    void leavesTheStoredBadgeUntouchedWhenARequestIsRejected() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.POLICE, "to be refused");
        jdbcTemplate.update("UPDATE users SET badge_type = 'NGO' WHERE id = ?", applicant.getId());

        decide(saveUser(UserRoles.MODERATOR), requestId, "reject", null);

        assertThat(badgeColumn(applicant)).isEqualTo("NGO");
    }

    /** Defensive guard: a revocation never wipes a badge that belongs to a different request. */
    @Test
    void leavesADifferentStoredBadgeAloneWhenRevokingAnApprovalOfAnotherType() throws Exception {
        User holder = saveUser(UserRoles.REGISTERED_USER);
        VerificationRequest stale = saveRequest(holder, VerificationType.POLICE, VerificationStatus.APPROVED);
        jdbcTemplate.update("UPDATE users SET badge_type = 'NGO' WHERE id = ?", holder.getId());

        decide(saveUser(UserRoles.SUPER_ADMIN), stale.getId(), "revoke", null);

        assertThat(badgeColumn(holder)).isEqualTo("NGO");
    }

    // --- Response projection ------------------------------------------------

    @Test
    void keepsReviewerDetailOutOfTheApplicantsOwnView() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.NGO, "projection evidence");
        decide(saveUser(UserRoles.MODERATOR), requestId, "approve", "internal reviewer note");

        mockMvc.perform(get(OWN).header("Authorization", bearer(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").exists())
                .andExpect(jsonPath("$.data[0].reviewNotes").doesNotExist())
                .andExpect(jsonPath("$.data[0].reviewer").doesNotExist())
                .andExpect(jsonPath("$.data[0].reviewerId").doesNotExist())
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].user").doesNotExist());
    }

    @Test
    void showsAdministratorsThePseudonymAndNeverTheEmail() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.NGO, "queue evidence");

        MvcResult result = mockMvc.perform(get(QUEUE)
                        .header("Authorization", bearer(saveUser(UserRoles.MODERATOR))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode row = queueRow(result, requestId);
        assertThat(row.get("displayName").asText()).isEqualTo(applicant.getDisplayName());
        assertThat(row.has("email")).isFalse();
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void keepsTheEmailOutOfADecisionResponse() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        long requestId = submit(applicant, VerificationType.NGO, "decision evidence");

        mockMvc.perform(post(decisionPath(requestId, "approve"))
                        .header("Authorization", bearer(saveUser(UserRoles.MODERATOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewNotes":"Credential confirmed."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.displayName").value(applicant.getDisplayName()))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.user").doesNotExist());
    }

    // --- State machine ------------------------------------------------------

    @ParameterizedTest(name = "{1} on a {0} request is {2}")
    @MethodSource("stateMachine")
    void enforcesTheStateMachine(VerificationStatus from, String action, int expectedStatus) throws Exception {
        VerificationRequest fixture = saveRequest(
                saveUser(UserRoles.REGISTERED_USER), VerificationType.NGO, from);

        mockMvc.perform(post(decisionPath(fixture.getId(), action))
                        .header("Authorization", bearer(saveUser(UserRoles.SUPER_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(expectedStatus));
    }

    private static Stream<Arguments> stateMachine() {
        return Stream.of(VerificationStatus.values())
                .flatMap(from -> Stream.of("approve", "reject", "revoke")
                        .map(action -> Arguments.of(from, action, expectedFor(from, action))));
    }

    private static int expectedFor(VerificationStatus from, String action) {
        boolean allowed = switch (action) {
            case "revoke" -> from == VerificationStatus.APPROVED;
            default -> from == VerificationStatus.PENDING;
        };
        return allowed ? 200 : 409;
    }

    @Test
    void refusesADecisionOnAnUnknownRequest() throws Exception {
        mockMvc.perform(post(decisionPath(99_999_999L, "approve"))
                        .header("Authorization", bearer(saveUser(UserRoles.MODERATOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesAModeratorDecidingTheirOwnApplication() throws Exception {
        User moderator = saveUser(UserRoles.MODERATOR);
        long requestId = submit(moderator, VerificationType.NGO, "self decision");

        mockMvc.perform(post(decisionPath(requestId, "approve"))
                        .header("Authorization", bearer(moderator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesASuperAdminRevokingTheirOwnBadge() throws Exception {
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);
        VerificationRequest own = saveRequest(superAdmin, VerificationType.POLICE, VerificationStatus.APPROVED);

        mockMvc.perform(post(decisionPath(own.getId(), "revoke"))
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // --- Audit --------------------------------------------------------------

    @Test
    void recordsTheDecidingAdministratorOnTheRow() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        long requestId = submit(applicant, VerificationType.NGO, "audit evidence");
        LocalDateTime before = LocalDateTime.now();

        decide(moderator, requestId, "approve", "Credential confirmed against the issuing body.");

        Map<String, Object> row = auditRow(requestId);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(((Number) row.get("reviewer_id")).longValue()).isEqualTo(moderator.getId());
        assertThat(row.get("review_notes")).isEqualTo("Credential confirmed against the issuing body.");
        assertThat(reviewedAt(row)).isAfterOrEqualTo(before);
    }

    /**
     * A revocation overwrites the reviewer and notes of the approval it undoes: the row
     * holds the latest decision only. That is the accepted CT-020 gap — the append-only
     * trail arrives with the audit service — so this test pins the current behaviour
     * rather than asserting a history the schema cannot hold.
     */
    @Test
    void replacesTheApprovalRecordWhenTheBadgeIsRevoked() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);
        long requestId = submit(applicant, VerificationType.POLICE, "revocation evidence");
        decide(moderator, requestId, "approve", "Approved by the moderator.");

        decide(superAdmin, requestId, "revoke", "Withdrawn by the issuing body.");

        Map<String, Object> row = auditRow(requestId);
        assertThat(row.get("status")).isEqualTo("REVOKED");
        assertThat(((Number) row.get("reviewer_id")).longValue()).isEqualTo(superAdmin.getId());
        assertThat(row.get("review_notes")).isEqualTo("Withdrawn by the issuing body.");
    }

    @Test
    void recordsTheGrantingAdministratorOnADirectGrant() throws Exception {
        User target = saveUser(UserRoles.REGISTERED_USER);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        MvcResult result = mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"POLICE",
                                 "evidenceReference":"Warrant card verified in person.",
                                 "reviewNotes":"Granted after an offline check."}"""
                                .formatted(target.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andReturn();

        long requestId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        Map<String, Object> row = auditRow(requestId);
        assertThat(((Number) row.get("reviewer_id")).longValue()).isEqualTo(superAdmin.getId());
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(userService.getPublicProfile(target.getId()).badgeType()).isEqualTo(VerificationType.POLICE);
    }

    @Test
    void refusesAGrantToAnUnknownRecipient() throws Exception {
        mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(saveUser(UserRoles.SUPER_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-%s@example.com","verificationType":"NGO",
                                 "evidenceReference":"unknown recipient"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesAnAdministratorGrantingTheirOwnBadge() throws Exception {
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"NGO",
                                 "evidenceReference":"self grant"}""".formatted(superAdmin.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesAGrantThatDuplicatesAPendingApplication() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        submit(applicant, VerificationType.NGO, "pending application");

        mockMvc.perform(post(GRANT)
                        .header("Authorization", bearer(saveUser(UserRoles.SUPER_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","verificationType":"NGO",
                                 "evidenceReference":"duplicate of a pending application"}"""
                                .formatted(applicant.getEmail())))
                .andExpect(status().isConflict());
    }

    // --- The badge the public actually sees ---------------------------------

    @Test
    void showsTheBadgeOnlyWhileADecisionLeavesItApproved() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        long policeId = submit(applicant, VerificationType.POLICE, "police evidence");
        assertThat(userService.getPublicProfile(applicant.getId()).verified()).isFalse();

        decide(moderator, policeId, "approve", null);
        PublicUserResponse badged = userService.getPublicProfile(applicant.getId());
        assertThat(badged.verified()).isTrue();
        assertThat(badged.badgeType()).isEqualTo(VerificationType.POLICE);

        decide(superAdmin, policeId, "revoke", null);
        PublicUserResponse afterRevocation = userService.getPublicProfile(applicant.getId());
        assertThat(afterRevocation.verified()).isFalse();
        assertThat(afterRevocation.badgeType()).isNull();

        long ngoId = submit(applicant, VerificationType.NGO, "ngo evidence");
        decide(moderator, ngoId, "approve", null);
        assertThat(userService.getPublicProfile(applicant.getId()).badgeType()).isEqualTo(VerificationType.NGO);
    }

    @Test
    void carriesTheBadgeOnTheUsersOwnProfileAndDropsItOnRevocation() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);
        long requestId = submit(applicant, VerificationType.POLICE, "own profile evidence");

        mockMvc.perform(get(ME).header("Authorization", bearer(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.badgeType").value(nullValue()));

        decide(moderator, requestId, "approve", null);
        mockMvc.perform(get(ME).header("Authorization", bearer(applicant)))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.badgeType").value("POLICE"))
                // the badge rides along with the private view; the evidence does not
                .andExpect(jsonPath("$.data.evidenceReference").doesNotExist())
                .andExpect(jsonPath("$.data.reviewNotes").doesNotExist());

        decide(superAdmin, requestId, "revoke", null);
        mockMvc.perform(get(ME).header("Authorization", bearer(applicant)))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.badgeType").value(nullValue()));
    }

    // --- Logging ------------------------------------------------------------

    /**
     * Review Focus #7 — a decision log line carries the request id, the action, the
     * transition and the actor id. Evidence and email never reach the console.
     */
    @Test
    void keepsEvidenceAndEmailOutOfTheDecisionLog(CapturedOutput output) throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User moderator = saveUser(UserRoles.MODERATOR);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);
        String evidence = "classified-evidence-" + UUID.randomUUID();
        int mark = output.getOut().length();

        long requestId = submit(applicant, VerificationType.POLICE, evidence);
        decide(moderator, requestId, "approve", "sensitive reviewer reasoning");
        decide(superAdmin, requestId, "revoke", "sensitive revocation reasoning");

        String logged = output.getOut().substring(mark);
        assertThat(logged)
                .contains("verification_decision")
                .doesNotContain(evidence)
                .doesNotContain(applicant.getEmail())
                .doesNotContain("sensitive reviewer reasoning")
                .doesNotContain("sensitive revocation reasoning");
    }

    // --- Helpers ------------------------------------------------------------

    private long submit(User actor, VerificationType type, String evidence) throws Exception {
        MvcResult result = mockMvc.perform(post(SUBMIT)
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(type, evidence)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void decide(User actor, long requestId, String action, String reviewNotes) throws Exception {
        String body = reviewNotes == null ? "{}" : objectMapper.writeValueAsString(
                new VerificationDecisionRequest(reviewNotes));
        mockMvc.perform(post(decisionPath(requestId, action))
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private JsonNode queueRow(MvcResult result, long requestId) throws Exception {
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("content");
        for (JsonNode row : content) {
            if (row.path("id").asLong() == requestId) {
                return row;
            }
        }
        throw new AssertionError("Request " + requestId + " is missing from the pending queue");
    }

    /** H2 and PostgreSQL hand back different Java types for a timestamp column. */
    private static LocalDateTime reviewedAt(Map<String, Object> row) {
        Object value = row.get("reviewed_at");
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    private String badgeColumn(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT badge_type FROM users WHERE id = ?", String.class, user.getId());
    }

    private Map<String, Object> auditRow(long requestId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reviewer_id, review_notes, reviewed_at FROM verification_requests WHERE id = ?",
                requestId);
    }

    private static String submitBody(VerificationType type, String evidence) {
        return """
                {"verificationType":"%s","evidenceReference":"%s"}""".formatted(type, evidence);
    }

    private static String decisionPath(long id, String action) {
        return "/api/v1/admin/verification-requests/" + id + "/" + action;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(new SecurityUser(user));
    }

    private User saveUser(UserRoles role) {
        return userRepository.saveAndFlush(User.builder()
                .email("verification-workflow-" + UUID.randomUUID() + "@example.com")
                .passwordHash("encoded-password")
                .displayName("Contributor " + UUID.randomUUID())
                .role(role)
                .accountStatus(UserStatus.ACTIVE)
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
