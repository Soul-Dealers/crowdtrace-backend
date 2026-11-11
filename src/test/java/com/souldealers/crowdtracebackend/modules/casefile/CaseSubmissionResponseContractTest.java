package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.CaseSubmissionEndpointSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaseSubmissionResponseContractTest extends CaseSubmissionEndpointSupport {
    @Test
    void actualEndpointSerializationContainsExactlyTheSafeCaseReferenceFields() throws Exception {
        String body = submit(validRequest()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(body, "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("caseId", "reviewStatus", "submittedAt");
        assertThat(data.get("caseId")).isInstanceOf(Number.class);
        assertThat(data.get("reviewStatus")).isEqualTo("SUBMITTED");
        assertThat(data.get("submittedAt")).isEqualTo("2026-10-08T10:15:30");
        assertNoSensitiveValues(body);
    }
}
