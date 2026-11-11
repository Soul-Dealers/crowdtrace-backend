package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;

import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaseSubmissionEndpointTest extends CaseSubmissionEndpointSupport {
    @Test
    void validSubmissionReturns201AndPersistsTheAuthenticatedReporter() throws Exception {
        String body = submit(validRequest()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reviewStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-08T10:15:30"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.caseId")).longValue();
        assertThat(jdbc.queryForObject("SELECT reporter_id FROM cases WHERE id=?", Long.class, id))
                .isEqualTo(reporterId);
        assertThat(files.findById(reportId).orElseThrow().getCaseId()).isEqualTo(id);
        assertNoSensitiveValues(body);
    }

    @Test
    void missingReportNamesTheReportField() throws Exception {
        submit(validRequest().toBuilder().reportFileIds(null).build()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reportFileIds").isArray());
        submit(validRequest().toBuilder().reportFileIds(List.of()).build()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reportFileIds").isArray());
    }

    @Test
    void missingConsentNamesTheConsentField() throws Exception {
        submit(validRequest().toBuilder().consent(null).build()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.consent").isArray());
    }

    @Test
    void declinedConsentNamesTheAcceptedFieldWithoutEchoingSensitiveInput() throws Exception {
        CaseSubmissionRequest request = validRequest();
        String body = submit(request.toBuilder().consent(request.consent().toBuilder().accepted(false).build()).build())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['consent.accepted']").isArray())
                .andReturn().getResponse().getContentAsString();
        assertNoSensitiveValues(body);
    }

    @Test
    void staleConsentReturns400WithoutPersistingACase() throws Exception {
        CaseSubmissionRequest request = validRequest();
        String body = submit(request.toBuilder().consent(request.consent().toBuilder().version("old-v1").build()).build())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Consent version is outdated"))
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE reporter_id=?", Long.class, reporterId)).isZero();
        assertNoSensitiveValues(body);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 131})
    void invalidAgeReturns400NamingAge(int age) throws Exception {
        submit(validRequest().toBuilder().age(age).build()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.age").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1899-12-31", "2026-10-09"})
    void invalidDateReturns400NamingLastSeenDate(String date) throws Exception {
        submit(validRequest().toBuilder().lastSeenDate(LocalDate.parse(date)).build()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.lastSeenDate").isArray());
    }

    @Test
    void submissionRequiresAuthentication() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(CaseFilePurpose.class)
    void foreignFilesReturn404AndLeaveTheReportUnattached(CaseFilePurpose purpose) throws Exception {
        long foreign = upload(anotherReporter(), purpose);
        CaseSubmissionRequest request = purpose == CaseFilePurpose.REPORT
                ? validSubmission(List.of(foreign)).build()
                : validRequest().toBuilder().photoFileIds(List.of(foreign)).build();
        String body = submit(request).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(files.findById(reportId).orElseThrow().getCaseId()).isNull();
        assertThat(files.findById(foreign).orElseThrow().getCaseId()).isNull();
        assertNoSensitiveValues(body);
    }

    @Test
    void reusingAnAttachedReportReturns409() throws Exception {
        submit(validRequest()).andExpect(status().isCreated());
        submit(validRequest()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE reporter_id=?", Long.class, reporterId)).isEqualTo(1);
    }

    @Test
    void filesInTheWrongPurposeSlotReturn400() throws Exception {
        long photo = upload(reporterId, CaseFilePurpose.PHOTO);
        submit(validSubmission(List.of(photo)).build()).andExpect(status().isBadRequest());
        long extraReport = upload(reporterId, CaseFilePurpose.REPORT);
        submit(validRequest().toBuilder().photoFileIds(List.of(extraReport)).build()).andExpect(status().isBadRequest());
    }

    @Test
    void moreThanFivePhotosReturns400NamingPhotoFileIds() throws Exception {
        submit(validRequest().toBuilder().photoFileIds(List.of(1L, 2L, 3L, 4L, 5L, 6L)).build())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.photoFileIds").isArray());
    }
}
