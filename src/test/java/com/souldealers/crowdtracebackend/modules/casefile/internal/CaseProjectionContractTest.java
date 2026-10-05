package com.souldealers.crowdtracebackend.modules.casefile.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

}
