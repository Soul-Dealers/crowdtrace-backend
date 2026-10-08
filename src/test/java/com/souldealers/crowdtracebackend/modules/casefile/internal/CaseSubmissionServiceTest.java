package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionRequest;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmittedEvent;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentSource;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseConsentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.POLICY_VERSION;
import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.SUBMITTED_AT;
import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "crowdtrace.consent.sensitive-data-version=" + POLICY_VERSION)
@Import(CaseSubmissionFixtures.FixedClockConfiguration.class)
@RecordApplicationEvents
class CaseSubmissionServiceTest extends CasePostgresTestSupport {
    @Autowired private CaseSubmissionService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseSensitiveDetailsRepository sensitiveDetails;
    @Autowired private CaseConsentRepository consents;
    @Autowired private CaseFileRepository files;
    @Autowired private ApplicationEvents events;

    @Test
    void validSubmissionCommitsCaseSensitiveDetailsConsentAndAllFiles() {
        long reporter = reporter();
        List<Long> reports = List.of(upload(reporter, CaseFilePurpose.REPORT),
                upload(reporter, CaseFilePurpose.REPORT));
        List<Long> photos = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));
        CaseSubmissionRequest request = validSubmission(reports).photoFileIds(photos).build();

        CaseSubmissionResponse response = service.submit(reporter, request);

        assertSubmissionRows(reporter, 1);
        CaseRecord record = cases.findById(response.caseId()).orElseThrow();
        assertThat(record.getReporterId()).isEqualTo(reporter);
        assertThat(record).extracting(CaseRecord::getFullName, CaseRecord::getAge, CaseRecord::getGender,
                CaseRecord::getLastSeenDate, CaseRecord::getRegion, CaseRecord::getLastSeenLocation,
                CaseRecord::getPhysicalDescription, CaseRecord::getClothing, CaseRecord::getCircumstances,
                CaseRecord::getPublicContactNumber).containsExactly("Ama Mensah", 24, Gender.FEMALE,
                LocalDate.of(2026, 10, 1), GhanaRegion.GREATER_ACCRA, "Madina market", "Slim, short hair",
                "Blue shirt", "Did not return home", "+233200000000");
        assertThat(record.getReviewStatus()).isEqualTo(ReviewStatus.SUBMITTED);
        assertThat(record.getCaseStatus()).isNull();
        assertThat(record.isPriorityMinor()).isFalse();
        assertThat(record.isDuplicateFlag()).isFalse();
        assertThat(record.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedAt()).isNotNull();
        assertThat(response).isEqualTo(new CaseSubmissionResponse(record.getId(), ReviewStatus.SUBMITTED, SUBMITTED_AT));

        CaseSensitiveDetails sensitive = sensitiveDetails.findById(record.getId()).orElseThrow();
        assertThat(sensitive).extracting(CaseSensitiveDetails::getReporterRelationship,
                CaseSensitiveDetails::getMedicalConditions, CaseSensitiveDetails::getKnownAssociates,
                CaseSensitiveDetails::getVehicleInfo, CaseSensitiveDetails::getSocialMediaHandles)
                .containsExactly("Sister", "Requires daily medication", "Neighbour", "Blue saloon car", "@ama.example");
        assertThat(sensitive.getCreatedAt()).isNotNull();
        assertThat(sensitive.getUpdatedAt()).isNotNull();
        assertThat(files.findAllById(reports)).extracting(CaseFile::getCaseId).containsOnly(record.getId());
        assertThat(files.findAllById(photos)).extracting(CaseFile::getCaseId).containsOnly(record.getId());
        assertThat(events.stream(CaseSubmittedEvent.class).toList())
                .containsExactly(new CaseSubmittedEvent(record.getId()));
    }

    @ParameterizedTest
    @CsvSource({"17, true", "18, false"})
    void setsMinorPriorityAtTheAgeBoundary(int age, boolean priorityMinor) {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);

        CaseSubmissionResponse response = service.submit(reporter, validSubmission(List.of(report)).age(age).build());

        assertSubmissionRows(reporter, 1);
        assertThat(cases.findById(response.caseId()).orElseThrow().isPriorityMinor()).isEqualTo(priorityMinor);
    }

    @ParameterizedTest
    @EnumSource(ConsentSource.class)
    void consentStoresTheCurrentPolicyVersionReporterSourceAndClockTime(ConsentSource source) {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        CaseSubmissionRequest request = validSubmission(List.of(report))
                .consent(CaseSubmissionRequest.ConsentInput.builder().accepted(true).version(POLICY_VERSION)
                        .source(source).build()).build();

        CaseSubmissionResponse response = service.submit(reporter, request);

        assertSubmissionRows(reporter, 1);
        List<CaseConsent> saved = consents.findByCaseIdOrderByAcceptedAtAscIdAsc(response.caseId());
        assertThat(saved).singleElement().satisfies(consent -> {
            assertThat(consent.getUserId()).isEqualTo(reporter);
            assertThat(consent.getConsentType()).isEqualTo(ConsentType.SENSITIVE_DATA_COLLECTION);
            assertThat(consent.getConsentVersion()).isEqualTo(POLICY_VERSION);
            assertThat(consent.getSource()).isEqualTo(source);
            assertThat(consent.getAcceptedAt()).isEqualTo(SUBMITTED_AT);
        });
    }

    @Test
    void rejectsAnOutdatedConsentVersionWithoutPersistingAnything() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        CaseSubmissionRequest request = validSubmission(List.of(report))
                .consent(CaseSubmissionRequest.ConsentInput.builder().accepted(true).version("old-v1")
                        .source(ConsentSource.WEB).build()).build();

        assertThatThrownBy(() -> service.submit(reporter, request)).isInstanceOf(ValidationException.class)
                .hasMessage("Consent version is outdated");

        assertSubmissionRows(reporter, 0);
        assertUnattached(List.of(report));
        assertThat(events.stream(CaseSubmittedEvent.class)).isEmpty();
    }

    @Test
    void rollsBackEverythingWhenAttachFails() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        long foreignPhoto = upload(reporter(), CaseFilePurpose.PHOTO);
        CaseSubmissionRequest request = validSubmission(List.of(report)).photoFileIds(List.of(foreignPhoto)).build();

        assertThatThrownBy(() -> service.submit(reporter, request)).isInstanceOf(NotFoundException.class)
                .hasMessage("File not found");

        assertSubmissionRows(reporter, 0);
        assertUnattached(List.of(report, foreignPhoto));
        assertThat(events.stream(CaseSubmittedEvent.class)).isEmpty();
    }

    @Test
    void resubmittingTheSameFilesConflictsWithoutCreatingAnotherCase() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        long photo = upload(reporter, CaseFilePurpose.PHOTO);
        CaseSubmissionRequest request = validSubmission(List.of(report)).photoFileIds(List.of(photo)).build();
        CaseSubmissionResponse original = service.submit(reporter, request);
        assertSubmissionRows(reporter, 1);

        assertThatThrownBy(() -> service.submit(reporter, request)).isInstanceOf(ConflictException.class);

        assertSubmissionRows(reporter, 1);
        assertThat(files.findAllById(List.of(report, photo))).extracting(CaseFile::getCaseId)
                .containsOnly(original.caseId());
        assertThat(events.stream(CaseSubmittedEvent.class).toList())
                .containsExactly(new CaseSubmittedEvent(original.caseId()));
    }

    @Test
    void directServiceCallsRequireAcceptedConsent() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        CaseSubmissionRequest request = validSubmission(List.of(report))
                .consent(CaseSubmissionRequest.ConsentInput.builder().accepted(false).version(POLICY_VERSION)
                        .source(ConsentSource.WEB).build()).build();

        assertThatThrownBy(() -> service.submit(reporter, request)).isInstanceOf(ConstraintViolationException.class);

        assertSubmissionRows(reporter, 0);
        assertUnattached(List.of(report));
    }

    @Test
    void directServiceCallsRequireARequest() {
        long reporter = reporter();

        assertThatThrownBy(() -> service.submit(reporter, null)).isInstanceOf(ConstraintViolationException.class);

        assertSubmissionRows(reporter, 0);
    }

    private long reporter() {
        return user("submission-" + UUID.randomUUID() + "@example.com");
    }

    private long upload(long reporter, CaseFilePurpose purpose) {
        return files.saveAndFlush(CaseFile.builder().uploadedBy(reporter).purpose(purpose)
                .visibility(purpose.visibility()).storageKey(UUID.randomUUID().toString())
                .contentType(purpose == CaseFilePurpose.REPORT ? "application/pdf" : "image/png")
                .sizeBytes(123).checksumSha256("a".repeat(64)).build()).getId();
    }

    private void assertSubmissionRows(long reporter, long expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE reporter_id = ?", Long.class, reporter))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM case_sensitive_details d JOIN cases c ON c.id = d.case_id
                WHERE c.reporter_id = ?
                """, Long.class, reporter)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_consents WHERE user_id = ?", Long.class, reporter))
                .isEqualTo(expected);
    }

    private void assertUnattached(List<Long> ids) {
        List<CaseFile> unattached = files.findAllById(ids);
        assertThat(unattached).extracting(CaseFile::getCaseId).containsOnlyNulls();
        assertThat(unattached).extracting(CaseFile::getAttachedAt).containsOnlyNulls();
    }
}
