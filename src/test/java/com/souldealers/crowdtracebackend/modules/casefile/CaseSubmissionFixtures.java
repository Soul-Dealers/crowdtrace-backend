package com.souldealers.crowdtracebackend.modules.casefile;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class CaseSubmissionFixtures {
    public static final String POLICY_VERSION = "test-v2";
    public static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 10, 8, 10, 15, 30);

    private CaseSubmissionFixtures() {}

    public static CaseSubmissionRequest.CaseSubmissionRequestBuilder validSubmission(List<Long> reportFileIds) {
        return CaseSubmissionRequest.builder().fullName("Ama Mensah").age(24).gender(Gender.FEMALE)
                .lastSeenDate(LocalDate.of(2026, 10, 1)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Madina market").physicalDescription("Slim, short hair")
                .clothing("Blue shirt").circumstances("Did not return home")
                .publicContactNumber("+233200000000")
                .sensitiveDetails(CaseSubmissionRequest.SensitiveDetailsInput.builder()
                        .reporterRelationship("Sister").medicalConditions("Requires daily medication")
                        .knownAssociates("Neighbour").vehicleInfo("Blue saloon car")
                        .socialMediaHandles("@ama.example").build())
                .consent(CaseSubmissionRequest.ConsentInput.builder().accepted(true).version(POLICY_VERSION)
                        .source(ConsentSource.WEB).build())
                .reportFileIds(reportFileIds).photoFileIds(List.of());
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock caseSubmissionTestClock() {
            return Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
        }
    }
}
