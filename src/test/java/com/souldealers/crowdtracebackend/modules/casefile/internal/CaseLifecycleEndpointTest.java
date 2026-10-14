package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseLifecycleEndpointTest extends CasePostgresTestSupport {
    private static final String REPORTER_PATH = "/api/user/cases/";
    private static final String ADMIN_PATH = "/api/v1/admin/cases/";
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository users;
    @Autowired private CaseRecordRepository cases;
    private String ownerEmail;
    private long ownerId;

    @BeforeEach
    void createOwner() {
        ownerEmail = email("owner");
        ownerId = actor(ownerEmail, UserRoles.REGISTERED_USER);
    }

    @Test
    void reporterEndpointRequiresAuthenticationAndHidesForeignCases() throws Exception {
        CaseRecord own = approved(ownerId);
        long foreignOwner = actor(email("foreign"), UserRoles.REGISTERED_USER);
        CaseRecord foreign = approved(foreignOwner);
        mvc.perform(patch(REPORTER_PATH + own.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(body(own.getVersion(), "FOUND_SAFE")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(patch(REPORTER_PATH + foreign.getId() + "/status"), UserRoles.REGISTERED_USER)
                .content(body(foreign.getVersion(), "FOUND_SAFE"))).andExpect(status().isNotFound());
        assertThat(cases.findById(foreign.getId()).orElseThrow().getCaseStatus()).isEqualTo(CaseStatus.MISSING);
    }

    @Test
    void reporterUsesTokenActorAndGetsLifecycleResponse() throws Exception {
        CaseRecord own = approved(ownerId);
        assertThat(jdbc.queryForObject("SELECT reporter_id FROM cases WHERE id=?", Long.class, own.getId()))
                .isEqualTo(ownerId);
        mvc.perform(as(patch(REPORTER_PATH + own.getId() + "/status"), ownerEmail)
                .content("{\"expectedCaseVersion\":" + own.getVersion()
                        + ",\"caseStatus\":\"FOUND_SAFE\",\"actorId\":999999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseId").value(own.getId()))
                .andExpect(jsonPath("$.data.caseVersion").value(own.getVersion() + 1))
                .andExpect(jsonPath("$.data.caseStatus").value("FOUND_SAFE"));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_events WHERE target_type='CASE' AND target_id=?",
                Long.class, own.getId())).isEqualTo(ownerId);
    }

    @Test
    void adminRouteRequiresModeratorAndAllowsModeratorCorrection() throws Exception {
        CaseRecord own = approved(ownerId);
        mvc.perform(as(patch(ADMIN_PATH + own.getId() + "/status"), UserRoles.REGISTERED_USER)
                .content(body(own.getVersion(), "CLOSED"))).andExpect(status().isForbidden());
        String moderatorEmail = email("mod");
        actor(moderatorEmail, UserRoles.MODERATOR);
        mvc.perform(as(patch(ADMIN_PATH + own.getId() + "/status"), moderatorEmail)
                .content(body(own.getVersion(), "FOUND_DECEASED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseStatus").value("FOUND_DECEASED"));
    }

    @Test
    void invalidStatusBodyReturns400() throws Exception {
        CaseRecord own = approved(ownerId);
        mvc.perform(as(patch(REPORTER_PATH + own.getId() + "/status"), UserRoles.REGISTERED_USER)
                .content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(as(patch(REPORTER_PATH + own.getId() + "/status"), UserRoles.REGISTERED_USER)
                .content("{\"expectedCaseVersion\":-1,\"caseStatus\":\"FOUND_SAFE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reporterCanSetAStatementAndForeignReporterGets404() throws Exception {
        CaseRecord own = approved(ownerId, CaseStatus.FOUND_SAFE);
        mvc.perform(as(put(REPORTER_PATH + own.getId() + "/closing-statement"), ownerEmail)
                .content("{\"expectedCaseVersion\":" + own.getVersion()
                        + ",\"closingStatement\":\"  They are home.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closingStatement").value("They are home."));

        long foreignId = actor(email("foreign-statement"), UserRoles.REGISTERED_USER);
        CaseRecord foreign = approved(foreignId, CaseStatus.FOUND_SAFE);
        mvc.perform(as(put(REPORTER_PATH + foreign.getId() + "/closing-statement"), ownerEmail)
                .content("{\"expectedCaseVersion\":" + foreign.getVersion()
                        + ",\"closingStatement\":\"Private\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminStatementRouteRequiresModeratorAndRejectsBlankInput() throws Exception {
        CaseRecord resolved = approved(ownerId, CaseStatus.FOUND_SAFE);
        mvc.perform(as(put(ADMIN_PATH + resolved.getId() + "/closing-statement"), UserRoles.REGISTERED_USER)
                .content("{\"expectedCaseVersion\":" + resolved.getVersion()
                        + ",\"closingStatement\":\"No\"}"))
                .andExpect(status().isForbidden());
        String moderatorEmail = email("statement-moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
        mvc.perform(as(put(ADMIN_PATH + resolved.getId() + "/closing-statement"), moderatorEmail)
                .content("{\"expectedCaseVersion\":" + resolved.getVersion()
                        + ",\"closingStatement\":\" \u2003 \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminTakedownRouteRequiresModeratorAndReturnsTheUpdatedLifecycle() throws Exception {
        CaseRecord resolved = approved(ownerId, CaseStatus.FOUND_SAFE);
        mvc.perform(as(post(ADMIN_PATH + resolved.getId() + "/takedown"), UserRoles.REGISTERED_USER)
                .content("{\"expectedCaseVersion\":" + resolved.getVersion() + ",\"reason\":\"Hoax\"}"))
                .andExpect(status().isForbidden());
        String moderatorEmail = email("takedown-moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
        mvc.perform(as(post(ADMIN_PATH + resolved.getId() + "/takedown"), moderatorEmail)
                .content("{\"expectedCaseVersion\":" + resolved.getVersion() + ",\"reason\":\" Hoax \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("TAKEN_DOWN"))
                .andExpect(jsonPath("$.data.caseStatus").value("FOUND_SAFE"));
    }

    @Test
    void adminTakedownRouteRejectsInvalidReason() throws Exception {
        CaseRecord own = approved(ownerId);
        String moderatorEmail = email("invalid-takedown-moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
        mvc.perform(as(post(ADMIN_PATH + own.getId() + "/takedown"), moderatorEmail)
                .content("{\"expectedCaseVersion\":" + own.getVersion() + ",\"reason\":\" \u2003 \"}"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserRoles role) {
        String email = email(role.name().toLowerCase());
        actor(email, role);
        return as(request, email);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                        new SecurityUser(users.findByEmail(email).orElseThrow())))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private CaseRecord approved(long reporterId) {
        return approved(reporterId, CaseStatus.MISSING);
    }

    private CaseRecord approved(long reporterId, CaseStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 10, 0);
        return cases.saveAndFlush(CaseRecord.builder().reporterId(reporterId).fullName("Test Person").age(24)
                .gender(Gender.MALE).lastSeenDate(LocalDate.of(2026, 9, 30)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(status).approvedAt(now)
                .resolvedAt(status == CaseStatus.MISSING ? null : now)
                .closedAt(status == CaseStatus.CLOSED ? now : null)
                .submittedAt(now.minusDays(2)).createdAt(now.minusDays(2)).updatedAt(now.minusDays(2)).build());
    }

    private long actor(String email, UserRoles role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id", Long.class, email, role.name());
    }

    private static String email(String label) { return "endpoint-" + label + "-" + UUID.randomUUID() + "@example.com"; }
    private static String body(long version, String status) {
        return "{\"expectedCaseVersion\":" + version + ",\"caseStatus\":\"" + status + "\"}";
    }
}
