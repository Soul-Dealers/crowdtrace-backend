package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseConsentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

class CaseQueryServiceTest extends CasePostgresTestSupport {
    @Autowired private CaseQueryService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseSensitiveDetailsRepository sensitive;
    @Autowired private CaseFileRepository files;
    @Autowired private CaseConsentRepository consents;
    @MockitoSpyBean private UserService userService;

    @Test
    void publicReadReturnsAnApprovedCase() {
        long r = user("public@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).build());
        PublicCaseResponse response = service.getPublicCase(c.getId());
        assertThat(response.id()).isEqualTo(c.getId());
        assertThat(response.fullName()).isEqualTo("Kofi Mensah");
        assertThat(response.caseStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(response.approvedAt()).isEqualTo(at(9));
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"SUBMITTED", "UNDER_REVIEW", "REJECTED", "TAKEN_DOWN"})
    void publicReadHidesUnapprovedCases(ReviewStatus status) {
        long r = user("hidden@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).reviewStatus(status)
                .caseStatus(status == ReviewStatus.TAKEN_DOWN ? CaseStatus.MISSING : null).build());
        assertThatThrownBy(() -> service.getPublicCase(c.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void publicReadHidesUnknownCases() {
        assertThatThrownBy(() -> service.getPublicCase(999999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void publicListContainsOnlyApprovedCasesNewestApprovalFirst() {
        long r = user("public-list@example.com");
        CaseRecord older = cases.save(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.FOUND_SAFE).approvedAt(at(9)).build());
        CaseRecord newer = cases.save(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(10)).build());
        cases.save(aCase(r).build());
        cases.save(aCase(r).reviewStatus(ReviewStatus.REJECTED).build());
        cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(CaseStatus.MISSING).build());
        var response = service.listPublicCases(PageRequest.of(0, 10000, Sort.by("duplicateFlag")));
        assertThat(response.content()).extracting(PublicCaseResponse::id).containsExactly(newer.getId(), older.getId());
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void publicPagesAreStableWhenApprovalTimesTie() {
        long r = user("public-tie@example.com");
        List<Long> ids = IntStream.range(0, 5).mapToObj(i -> cases.save(aCase(r)
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).approvedAt(at(9)).build()).getId()).toList();
        cases.flush();
        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            service.listPublicCases(PageRequest.of(page, 2)).content().forEach(c -> seen.add(c.id()));
        }
        assertThat(seen).containsExactlyElementsOf(ids.reversed());
    }

    @Test
    void ownCaseIsReturnedWithSensitiveDetails() {
        long r = user("own-detail@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        sensitive.saveAndFlush(CaseSensitiveDetails.builder().caseRecord(c).reporterRelationship("Sister")
                .medicalConditions("Asthma").build());
        ReporterCaseResponse response = service.getOwnCase(c.getId(), r);
        assertThat(response.id()).isEqualTo(c.getId());
        assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.SUBMITTED);
        assertThat(response.sensitiveDetails().reporterRelationship()).isEqualTo("Sister");
        assertThat(response.sensitiveDetails().medicalConditions()).isEqualTo("Asthma");
    }

    @Test
    void anotherReportersCaseIsNotFound() {
        long a = user("owner@example.com"), b = user("other@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(a).build());
        assertThatThrownBy(() -> service.getOwnCase(c.getId(), b)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void ownCaseWithPurgedSensitiveDetailsStillRenders() {
        long r = user("purged@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        assertThat(service.getOwnCase(c.getId(), r).sensitiveDetails()).isNull();
        assertThatThrownBy(() -> service.getOwnCase(999999L, r)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void ownCaseListIncludesRejectedAndTakenDownCases() {
        long r = user("own-list@example.com"), other = user("other-list@example.com");
        CaseRecord rejected = cases.save(aCase(r).reviewStatus(ReviewStatus.REJECTED).createdAt(at(9)).build());
        CaseRecord takenDown = cases.save(aCase(r).reviewStatus(ReviewStatus.TAKEN_DOWN)
                .caseStatus(CaseStatus.MISSING).createdAt(at(10)).build());
        cases.saveAndFlush(aCase(other).createdAt(at(11)).build());
        var response = service.listOwnCases(r, PageRequest.of(0, 20));
        assertThat(response.content()).extracting(ReporterCaseSummaryResponse::id)
                .containsExactly(takenDown.getId(), rejected.getId());
        assertThat(response.content()).extracting(ReporterCaseSummaryResponse::reviewStatus)
                .containsExactly(ReviewStatus.TAKEN_DOWN, ReviewStatus.REJECTED);
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void ignoresClientSortAndClampsPageSize() {
        long r = user("clamp@example.com");
        CaseRecord older = cases.save(aCase(r).createdAt(at(9)).duplicateFlag(false).build());
        CaseRecord newer = cases.saveAndFlush(aCase(r).createdAt(at(10)).duplicateFlag(true).build());
        var response = service.listOwnCases(r, PageRequest.of(0, 10000, Sort.by("duplicateFlag")));
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.content()).extracting(ReporterCaseSummaryResponse::id).containsExactly(newer.getId(), older.getId());
    }

    @Test @WithMockUser(authorities = "MODERATOR")
    void adminCaseIncludesReporterEmailFlagsLiveFilesAndConsents() {
        long r = user("admin-detail@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).priorityMinor(true).duplicateFlag(true).build());
        sensitive.saveAndFlush(CaseSensitiveDetails.builder().caseRecord(c).reporterRelationship("Sister")
                .medicalConditions("Asthma").build());
        CaseFile live = files.save(CaseFile.builder().caseId(c.getId()).uploadedBy(r).purpose(CaseFilePurpose.REPORT)
                .visibility(FileVisibility.PRIVATE).storageKey("live.pdf").contentType("application/pdf")
                .sizeBytes(123).checksumSha256("a".repeat(64)).uploadedAt(at(9)).attachedAt(at(9)).build());
        files.saveAndFlush(CaseFile.builder().caseId(c.getId()).uploadedBy(r).purpose(CaseFilePurpose.PHOTO)
                .visibility(FileVisibility.PUBLIC).storageKey("deleted.jpg").contentType("image/jpeg")
                .sizeBytes(123).checksumSha256("b".repeat(64)).uploadedAt(at(10)).attachedAt(at(10)).deletedAt(at(11)).build());
        consents.save(CaseConsent.builder().caseId(c.getId()).userId(r).consentType(ConsentType.SENSITIVE_DATA_COLLECTION)
                .consentVersion("v2").source(ConsentSource.MOBILE).acceptedAt(at(10)).build());
        consents.saveAndFlush(CaseConsent.builder().caseId(c.getId()).userId(r).consentType(ConsentType.SENSITIVE_DATA_COLLECTION)
                .consentVersion("v1").source(ConsentSource.WEB).acceptedAt(at(9)).build());
        AdminCaseResponse response = service.getAdminCase(c.getId());
        assertThat(response.reporterId()).isEqualTo(r);
        assertThat(response.reporterAccountEmail()).isEqualTo("admin-detail@example.com");
        assertThat(response.priorityMinor()).isTrue();
        assertThat(response.duplicateFlag()).isTrue();
        assertThat(response.sensitiveDetails().medicalConditions()).isEqualTo("Asthma");
        assertThat(response.files()).extracting(CaseFileMetadataResponse::id).containsExactly(live.getId());
        assertThat(response.consents()).extracting(CaseConsentResponse::consentVersion).containsExactly("v1", "v2");
    }

    @Test @WithMockUser(authorities = "MODERATOR")
    void adminCaseWithAMissingAccountHasNullEmail() {
        long r = user("missing-account@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        doReturn(Optional.empty()).when(userService).getAccountEmail(r);
        assertThat(service.getAdminCase(c.getId()).reporterAccountEmail()).isNull();
        assertThat(service.getAdminCase(c.getId()).sensitiveDetails()).isNull();
    }

    @Test @WithMockUser(authorities = "REGISTERED_USER")
    void adminDetailIsDeniedToRegisteredUsers() {
        assertThatThrownBy(() -> service.getAdminCase(1L)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test @WithAnonymousUser
    void adminDetailIsDeniedToAnonymousUsers() {
        assertThatThrownBy(() -> service.getAdminCase(1L)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test @WithMockUser(authorities = "SUPER_ADMIN")
    void adminDetailIsAvailableToSuperAdminsAndUnknownCasesAreNotFound() {
        long r = user("super-admin-detail@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        assertThat(service.getAdminCase(c.getId()).id()).isEqualTo(c.getId());
        assertThatThrownBy(() -> service.getAdminCase(999999L)).isInstanceOf(NotFoundException.class);
    }

    @Test @WithMockUser(authorities = "MODERATOR")
    void reviewQueueShowsMinorsFirstAndExcludesDecidedCases() {
        long r = user("queue@example.com");
        CaseRecord adult = cases.save(aCase(r).submittedAt(at(8)).build());
        CaseRecord laterMinor = cases.save(aCase(r).submittedAt(at(10)).priorityMinor(true).build());
        CaseRecord earlyMinor = cases.save(aCase(r).reviewStatus(ReviewStatus.UNDER_REVIEW)
                .submittedAt(at(9)).priorityMinor(true).build());
        cases.save(aCase(r).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).priorityMinor(true).build());
        cases.save(aCase(r).reviewStatus(ReviewStatus.REJECTED).priorityMinor(true).build());
        cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(CaseStatus.MISSING).priorityMinor(true).build());
        var response = service.listReviewQueue(PageRequest.of(0, 10000, Sort.by("reporterId")));
        assertThat(response.content()).extracting(AdminCaseSummaryResponse::id)
                .containsExactly(earlyMinor.getId(), laterMinor.getId(), adult.getId());
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(3);
    }

    @Test @WithMockUser(authorities = "MODERATOR")
    void reviewQueuePagesAreStableWhenPriorityAndSubmissionTimesTie() {
        long r = user("queue-tie@example.com");
        List<Long> ids = IntStream.range(0, 5).mapToObj(i -> cases.save(aCase(r)
                .submittedAt(at(9)).priorityMinor(true).build()).getId()).toList();
        cases.flush();
        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            service.listReviewQueue(PageRequest.of(page, 2)).content().forEach(c -> seen.add(c.id()));
        }
        assertThat(seen).containsExactlyElementsOf(ids);
    }

    @Test @WithMockUser(authorities = "REGISTERED_USER")
    void adminReadsAreDeniedToRegisteredUsers() {
        assertThatThrownBy(() -> service.getAdminCase(1L)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.listReviewQueue(PageRequest.of(0, 20))).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test @WithAnonymousUser
    void reviewQueueIsDeniedToAnonymousUsers() {
        assertThatThrownBy(() -> service.listReviewQueue(PageRequest.of(0, 20))).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test @WithMockUser(authorities = "SUPER_ADMIN")
    void reviewQueueIsAvailableToSuperAdmins() {
        long r = user("super-queue@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        assertThat(service.listReviewQueue(PageRequest.of(0, 20)).content())
                .extracting(AdminCaseSummaryResponse::id).containsExactly(c.getId());
    }

}
