package com.souldealers.crowdtracebackend.modules.casefile.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.souldealers.crowdtracebackend.modules.casefile.internal.CaseProjectionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class CaseProjectionContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void publicJsonHasExactlyThePublicFields() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(CaseMapper.toPublic(fullCase())));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "id", "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
                "physicalDescription", "clothing", "circumstances", "caseStatus", "closingStatement",
                "publicContactNumber", "approvedAt", "resolvedAt");
        assertThat(json.get("fullName").asText()).isEqualTo("Kofi Mensah");
        assertThat(json.get("lastSeenDate").asText()).isEqualTo("2026-09-30");
        assertThat(json.get("resolvedAt").asText()).isEqualTo("2026-10-01T09:00:00");
    }

    @Test
    void publicJsonCarriesNoAdminOnlyValue() {
        String json = mapper.writeValueAsString(CaseMapper.toPublic(fullCase()));
        assertThat(json).doesNotContain(MEDICAL, ASSOCIATES, VEHICLE, SOCIALS, RELATION, EMAIL, STORAGE_KEY)
                .doesNotContain("reporterId", "priorityMinor", "duplicateFlag", "reviewStatus", "submittedAt");
    }

    @Test
    void reporterJsonHasTheirDetailsButNoReviewFlags() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(CaseMapper.toReporter(fullCase(), fullDetails())));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "id", "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
                "physicalDescription", "clothing", "circumstances", "publicContactNumber", "reviewStatus",
                "caseStatus", "closingStatement", "submittedAt", "approvedAt", "sensitiveDetails");
        assertThat(json.get("sensitiveDetails").propertyNames()).containsExactlyInAnyOrder(
                "reporterRelationship", "medicalConditions", "knownAssociates", "vehicleInfo", "socialMediaHandles");
        assertThat(json.get("sensitiveDetails").get("medicalConditions").asText()).isEqualTo(MEDICAL);
        assertThat(json.get("sensitiveDetails").get("reporterRelationship").asText()).isEqualTo(RELATION);
        assertThat(json.get("sensitiveDetails").get("knownAssociates").asText()).isEqualTo(ASSOCIATES);
        assertThat(json.get("sensitiveDetails").get("vehicleInfo").asText()).isEqualTo(VEHICLE);
        assertThat(json.get("sensitiveDetails").get("socialMediaHandles").asText()).isEqualTo(SOCIALS);
    }

    @Test
    void reporterMapsACaseWhoseSensitiveDetailsWerePurged() {
        assertThat(CaseMapper.toReporter(fullCase(), null).sensitiveDetails()).isNull();
    }

    @Test
    void reporterSummaryHasNoSensitiveOrFlagFields() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(CaseMapper.toReporterSummary(fullCase())));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("id", "fullName", "reviewStatus", "caseStatus", "submittedAt");
        assertThat(json.get("reviewStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void adminJsonHasEverythingExceptStorageKeys() {
        String raw = mapper.writeValueAsString(CaseMapper.toAdmin(
                fullCase(), fullDetails(), EMAIL, List.of(reportFile()), List.of(consent())));
        assertThat(raw).contains(MEDICAL, ASSOCIATES, VEHICLE, SOCIALS, RELATION, EMAIL,
                "\"priorityMinor\":true", "\"duplicateFlag\":true")
                .doesNotContain(STORAGE_KEY, "storageKey", "checksum", "uploadedBy");
        JsonNode json = mapper.readTree(raw);
        assertThat(json.get("reporterId").asLong()).isEqualTo(42L);
        assertThat(json.get("files").get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "purpose", "visibility", "contentType", "sizeBytes", "uploadedAt");
        assertThat(json.get("files").get(0).get("purpose").asText()).isEqualTo("REPORT");
        assertThat(json.get("files").get(0).get("sizeBytes").asLong()).isEqualTo(123L);
        assertThat(json.get("consents").get(0).propertyNames()).containsExactlyInAnyOrder(
                "consentType", "consentVersion", "source", "acceptedAt");
        assertThat(json.get("consents").get(0).get("consentVersion").asText()).isEqualTo("v1");
    }

    @Test
    void mapsACaseWhoseSensitiveDetailsWerePurged() {
        assertThat(CaseMapper.toReporter(fullCase(), null).sensitiveDetails()).isNull();
        var admin = CaseMapper.toAdmin(fullCase(), null, null, List.of(), List.of());
        assertThat(admin.sensitiveDetails()).isNull();
        assertThat(admin.reporterAccountEmail()).isNull();
        assertThat(admin.files()).isEmpty();
        assertThat(admin.consents()).isEmpty();
    }

    @Test
    void adminSummaryCarriesFlagsButNoSensitiveData() {
        String raw = mapper.writeValueAsString(CaseMapper.toAdminSummary(fullCase()));
        assertThat(raw).contains("priorityMinor", "duplicateFlag").doesNotContain(MEDICAL, EMAIL);
        assertThat(mapper.readTree(raw).propertyNames()).containsExactlyInAnyOrder(
                "id", "fullName", "age", "region", "reviewStatus", "priorityMinor", "duplicateFlag", "submittedAt");
    }

}
