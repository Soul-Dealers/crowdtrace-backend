package com.souldealers.crowdtracebackend.modules.discovery;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PublicCaseEndpointTest extends CasePostgresTestSupport {
    private static final String PATH = "/api/public/cases";
    private static final Set<String> PUBLIC_FIELDS = Set.of("id", "fullName", "age", "gender", "region",
            "lastSeenDate", "caseStatus", "presentation", "approvedAt", "resolvedAt", "thumbnailPhotoId");

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private String ownerEmail;
    private long ownerId;

    @BeforeEach
    void createOwner() {
        ownerEmail = email("owner");
        ownerId = user(ownerEmail);
    }

    @Test
    void guestCanSearchWithoutATokenAndReturnsOnlyThePublicProjection() throws Exception {
        CaseRecord visible = createCase("Kofi Mensah", ReviewStatus.APPROVED, CaseStatus.MISSING, 12);
        createCase("Private Person", ReviewStatus.SUBMITTED, null, 13);

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Public cases retrieved successfully"))
                .andExpect(jsonPath("$.data.content[0].fullName").value("Kofi Mensah"))
                .andExpect(jsonPath("$.data.content[0].id").value(visible.getId()));
        assertProjection(mvc.perform(get(PATH)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void authenticatedModeratorSeesTheSamePublicProjectionAndNoPendingCase() throws Exception {
        String marker = marker();
        createCase(marker + " Public", ReviewStatus.APPROVED, CaseStatus.MISSING, 12);
        createCase(marker + " Private", ReviewStatus.SUBMITTED, null, 13);
        String moderatorEmail = email("moderator");
        user(moderatorEmail);
        jdbc.update("UPDATE users SET role='MODERATOR' WHERE email=?", moderatorEmail);

        String body = mvc.perform(as(get(PATH).param("name", marker), moderatorEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertProjection(body);
    }

    @Test
    void invalidEnumAndNumericParametersReturnInvalidRequest() throws Exception {
        invalid("?status=BOGUS");
        invalid("?region=MARS");
        invalid("?ageMin=abc");
        invalid("?lastSeenFrom=2026-13-01");
        invalid("?page=abc");
        invalid("?page=2147483648");
        invalid("?size=x");
    }

    @Test
    void invalidFiltersAndOutOfRangePagesReturnValidationFailed() throws Exception {
        validationFailed("?name=ab");
        validationFailed("?ageMin=50&ageMax=40");
        validationFailed("?page=200&size=50");
        validationFailed("?page=-1");
        validationFailed("?size=0");
        validationFailed("?size=51");
        validationFailed("?status=MISSING&status=");
        validationFailed("?status=");
        validationFailed("?lastSeenFrom=0000-12-31");
        mvc.perform(get(PATH).param("lastSeenTo", "+10000-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(get(PATH).param("lastSeenFrom", "9999-12-31"))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedStatusParamsAreUnionedAndSortParamIsIgnored() throws Exception {
        String marker = marker();
        createCase(marker + " Missing", ReviewStatus.APPROVED, CaseStatus.MISSING, 10);
        createCase(marker + " Closed", ReviewStatus.APPROVED, CaseStatus.CLOSED, 11);
        createCase(marker + " Safe", ReviewStatus.APPROVED, CaseStatus.FOUND_SAFE, 12);

        mvc.perform(get(PATH).param("name", marker).param("status", "MISSING", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].caseStatus", containsInAnyOrder("MISSING", "CLOSED")));

        String regularOrder = mvc.perform(get(PATH)).andReturn().getResponse().getContentAsString();
        String suppliedSortOrder = mvc.perform(get(PATH).param("sort", "fullName,asc"))
                .andReturn().getResponse().getContentAsString();
        assertThat(suppliedSortOrder).isEqualTo(regularOrder);
    }

    private void invalid(String query) throws Exception {
        mvc.perform(get(PATH + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private void validationFailed(String query) throws Exception {
        mvc.perform(get(PATH + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void assertProjection(String body) throws Exception {
        com.fasterxml.jackson.databind.JsonNode item = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("content").path(0);
        Set<String> actualFields = new HashSet<>();
        Iterator<String> fields = item.fieldNames();
        fields.forEachRemaining(actualFields::add);
        assertThat(actualFields).containsExactlyInAnyOrderElementsOf(PUBLIC_FIELDS);
        assertThat(item.has("reporterId")).isFalse();
        assertThat(item.has("storageKey")).isFalse();
        assertThat(item.has("lastSeenLocation")).isFalse();
        assertThat(item.has("publicContactNumber")).isFalse();
        assertThat(item.has("reviewStatus")).isFalse();
        assertThat(item.has("version")).isFalse();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                new SecurityUser(users.findByEmail(email).orElseThrow()))).contentType(MediaType.APPLICATION_JSON);
    }

    private CaseRecord createCase(String name, ReviewStatus reviewStatus, CaseStatus caseStatus, int hour) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, hour, 0);
        return cases.saveAndFlush(CaseRecord.builder().reporterId(ownerId).fullName(name).age(24)
                .gender(Gender.MALE).lastSeenDate(LocalDate.of(2026, 9, 30)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .reviewStatus(reviewStatus).caseStatus(caseStatus)
                .approvedAt(reviewStatus == ReviewStatus.APPROVED ? now : null)
                .resolvedAt(caseStatus == null || caseStatus == CaseStatus.MISSING ? null : now)
                .closedAt(caseStatus == CaseStatus.CLOSED ? now : null)
                .submittedAt(now.minusDays(2)).createdAt(now.minusDays(2)).updatedAt(now.minusDays(2)).build());
    }

    private static String email(String label) {
        return "public-case-" + label + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String marker() {
        return "Public " + UUID.randomUUID();
    }
}
