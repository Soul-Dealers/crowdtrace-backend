package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CasePresentation;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class PublicCaseSearchTest extends CasePostgresTestSupport {
    @Autowired private CaseQueryService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseFileRepository files;
    @MockitoSpyBean private CaseFileRepository fileSpy;

    @Test
    void onlyApprovedCasesAppearInContentAndTotals() {
        long reporter = user("search-visibility@example.com");
        for (ReviewStatus status : ReviewStatus.values()) {
            CaseRecord.CaseRecordBuilder builder = aCase(reporter).reviewStatus(status);
            if (status == ReviewStatus.TAKEN_DOWN) {
                builder.caseStatus(CaseStatus.MISSING).takedownReason("reason").takenDownBy(reporter).takenDownAt(at(9));
            }
            if (status == ReviewStatus.APPROVED) builder.caseStatus(CaseStatus.MISSING).approvedAt(at(9));
            cases.save(builder.build());
        }
        var result = service.searchPublicCases(PublicCaseSearchQuery.all(), PageRequest.of(0, 20));
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void everyOutcomeIsPublicByDefaultAndStatusNarrows() {
        seedApproved("search-status@example.com", CaseStatus.values());
        assertThat(search(PublicCaseSearchQuery.all()).content()).hasSize(4);
        assertThat(search(query(null, null, null, Set.of(CaseStatus.MISSING), null, null, null, null, null, null)).content())
                .extracting(PublicCaseSummaryResponse::caseStatus).containsExactly(CaseStatus.MISSING);
        assertThat(search(query(null, null, null, Set.of(CaseStatus.FOUND_SAFE, CaseStatus.CLOSED), null, null,
                null, null, null, null)).content()).hasSize(2);
    }

    @Test
    void eachFilterNarrowsAndFiltersCombineWithAnd() {
        long reporter = user("search-filters@example.com");
        CaseRecord first = save(reporter, "Kofi Mensah", 20, Gender.MALE, GhanaRegion.GREATER_ACCRA,
                LocalDate.of(2026, 9, 1), at(9), CaseStatus.MISSING);
        save(reporter, "Kofi Mensah", 21, Gender.FEMALE, GhanaRegion.ASHANTI,
                LocalDate.of(2026, 9, 2), at(10), CaseStatus.FOUND_SAFE);
        save(reporter, "Ama Mensah", 22, Gender.FEMALE, GhanaRegion.GREATER_ACCRA,
                LocalDate.of(2026, 9, 3), at(11), CaseStatus.CLOSED);
        assertThat(search(query(null, GhanaRegion.GREATER_ACCRA, null, null, null, null, null, null, null, null)).content())
                .hasSize(2);
        assertThat(search(query(null, null, Gender.MALE, null, null, null, null, null, null, null)).content()).hasSize(1);
        assertThat(search(query(null, null, null, null, 20, 21, null, null, null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, 21, null, null, null, null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, null, 21, null, null, null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, null, null, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2), null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, null, null, LocalDate.of(2026, 9, 2),
                null, null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, null, null, null,
                LocalDate.of(2026, 9, 2), null, null)).content()).hasSize(2);
        assertThat(search(query(null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1))).content()).hasSize(3);
        var combined = search(query("Kofi", GhanaRegion.GREATER_ACCRA, null, Set.of(CaseStatus.MISSING),
                null, null, null, null, null, null));
        assertThat(combined.content()).extracting(PublicCaseSummaryResponse::id).containsExactly(first.getId());
    }

    @Test
    void nameMatchesCaseInsensitiveSubstringOnly() {
        long reporter = user("search-name@example.com");
        save(reporter, "Kofi MENSAH", 30, Gender.MALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(9), CaseStatus.MISSING);
        save(reporter, "Kòfi Mensah", 30, Gender.MALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(8), CaseStatus.MISSING);
        assertThat(search(query("mensah", null, null, null, null, null, null, null, null, null)).content()).hasSize(2);
        assertThat(search(query("Mensah Kofi", null, null, null, null, null, null, null, null, null)).content()).isEmpty();
        assertThat(search(query("Kofi", null, null, null, null, null, null, null, null, null)).content()).hasSize(1);
    }

    @Test
    void wildcardsInNameAreLiteral() {
        long reporter = user("search-wildcards@example.com");
        save(reporter, "Ama 50%", 30, Gender.FEMALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(9), CaseStatus.MISSING);
        save(reporter, "Ama 500", 30, Gender.FEMALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(8), CaseStatus.MISSING);
        save(reporter, "aba", 30, Gender.FEMALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(7), CaseStatus.MISSING);
        save(reporter, "Ama A\\B", 30, Gender.FEMALE, GhanaRegion.GREATER_ACCRA, LocalDate.of(2026, 9, 1), at(6), CaseStatus.MISSING);
        assertThat(search(query("50%", null, null, null, null, null, null, null, null, null)).content())
                .extracting(PublicCaseSummaryResponse::fullName).containsExactly("Ama 50%");
        assertThat(search(query("a_a", null, null, null, null, null, null, null, null, null)).content()).isEmpty();
        assertThat(search(query("A\\B", null, null, null, null, null, null, null, null, null)).content())
                .extracting(PublicCaseSummaryResponse::fullName).containsExactly("Ama A\\B");
    }

    @Test
    void approvalRangeIncludesEndDateThroughLastSecondAndExcludesNextMidnight() {
        long reporter = user("search-approval-boundary@example.com");
        cases.saveAndFlush(aCase(reporter).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING)
                .approvedAt(LocalDateTime.of(2026, 10, 1, 23, 59, 59)).build());
        cases.saveAndFlush(aCase(reporter).fullName("Later Approval").reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(LocalDateTime.of(2026, 10, 2, 0, 0)).build());
        assertThat(search(query(null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1))).content())
                .extracting(PublicCaseSummaryResponse::approvedAt)
                .containsExactly(LocalDateTime.of(2026, 10, 1, 23, 59, 59));
    }

    @Test
    void orderIsNewestApprovalThenIdAndPagesNeverRepeat() {
        long reporter = user("search-order@example.com");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDateTime approved = at(9 + (i / 3));
            expected.add(cases.save(aCase(reporter).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING)
                    .approvedAt(approved).build()).getId());
        }
        cases.flush();
        expected.sort((a, b) -> {
            LocalDateTime aa = cases.findById(a).orElseThrow().getApprovedAt();
            LocalDateTime bb = cases.findById(b).orElseThrow().getApprovedAt();
            int time = bb.compareTo(aa);
            return time == 0 ? b.compareTo(a) : time;
        });
        List<Long> seen = new ArrayList<>();
        for (int page = 0; page <= 3; page++) {
            search(PublicCaseSearchQuery.all(), page, 2).content().forEach(c -> seen.add(c.id()));
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void emptyAndBeyondLastPagesAreStable() {
        clearInvocations(fileSpy);
        var empty = search(query("Nobody", null, null, null, null, null, null, null, null, null));
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.last()).isTrue();
        verify(fileSpy, times(0)).findThumbnails(org.mockito.ArgumentMatchers.anyCollection());
        seedApproved("search-beyond@example.com", CaseStatus.MISSING);
        var beyond = search(PublicCaseSearchQuery.all(), 5, 2);
        assertThat(beyond.content()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(1);
        assertThat(beyond.last()).isTrue();
    }

    @Test
    void deceasedSummaryCarriesTheNotice() {
        long reporter = user("search-deceased@example.com");
        CaseRecord deceased = cases.saveAndFlush(aCase(reporter).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.FOUND_DECEASED).approvedAt(at(9)).resolvedAt(at(9)).build());
        CaseFile photo = file(deceased, reporter, CaseFilePurpose.PHOTO, FileVisibility.PUBLIC, at(3), null);
        PublicCaseSummaryResponse summary = search(PublicCaseSearchQuery.all()).content().getFirst();
        assertThat(summary.presentation()).isEqualTo(CasePresentation.DECEASED_NOTICE);
        assertThat(summary.thumbnailPhotoId()).isEqualTo(photo.getId());
    }

    @Test
    void thumbnailIsTheEarliestPublicLivePhoto() {
        long reporter = user("search-thumbnails@example.com");
        CaseRecord a = save(reporter, "A", 30, Gender.MALE, GhanaRegion.GREATER_ACCRA,
                LocalDate.of(2026, 9, 1), at(9), CaseStatus.MISSING);
        CaseRecord b = save(reporter, "B", 30, Gender.MALE, GhanaRegion.GREATER_ACCRA,
                LocalDate.of(2026, 9, 1), at(8), CaseStatus.MISSING);
        save(reporter, "C", 30, Gender.MALE, GhanaRegion.GREATER_ACCRA,
                LocalDate.of(2026, 9, 1), at(7), CaseStatus.MISSING, ReviewStatus.TAKEN_DOWN);
        file(a, reporter, CaseFilePurpose.REPORT, FileVisibility.PRIVATE, at(1), null);
        file(a, reporter, CaseFilePurpose.PHOTO, FileVisibility.PUBLIC, at(2), at(3));
        CaseFile p2 = file(a, reporter, CaseFilePurpose.PHOTO, FileVisibility.PUBLIC, at(3), null);
        CaseFile p3 = file(a, reporter, CaseFilePurpose.PHOTO, FileVisibility.PUBLIC, at(3), null);
        var result = search(PublicCaseSearchQuery.all());
        assertThat(result.content()).filteredOn(c -> c.id().equals(a.getId())).extracting(PublicCaseSummaryResponse::thumbnailPhotoId)
                .containsExactly(p2.getId());
        assertThat(result.content()).filteredOn(c -> c.id().equals(b.getId())).extracting(PublicCaseSummaryResponse::thumbnailPhotoId)
                .containsExactly((Long) null);
        assertThat(result.content()).extracting(PublicCaseSummaryResponse::id).doesNotContain(cases.findAll().stream()
                .filter(c -> c.getReviewStatus() == ReviewStatus.TAKEN_DOWN).findFirst().orElseThrow().getId());
        assertThat(p2.getId()).isLessThan(p3.getId());
    }

    @Test
    void thumbnailsLoadInOneQuery() {
        seedApproved("search-batch@example.com", CaseStatus.values());
        clearInvocations(fileSpy);
        search(PublicCaseSearchQuery.all());
        verify(fileSpy, times(1)).findThumbnails(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void deepPagesAreRejectedAfterSizeClamp() {
        assertThatThrownBy(() -> search(PublicCaseSearchQuery.all(), 200, 50)).isInstanceOf(ValidationException.class);
        assertThat(search(PublicCaseSearchQuery.all(), 199, 50).content()).isEmpty();
    }

    private void seedApproved(String email, CaseStatus... statuses) {
        long reporter = user(email);
        for (int i = 0; i < statuses.length; i++) {
            CaseRecord.CaseRecordBuilder builder = aCase(reporter).reviewStatus(ReviewStatus.APPROVED)
                    .caseStatus(statuses[i]).approvedAt(at(9 + i));
            if (statuses[i] == CaseStatus.FOUND_SAFE || statuses[i] == CaseStatus.FOUND_DECEASED
                    || statuses[i] == CaseStatus.CLOSED) {
                builder.resolvedAt(at(9 + i));
            }
            if (statuses[i] == CaseStatus.CLOSED) builder.closedAt(at(9 + i));
            cases.save(builder.build());
        }
    }

    private CaseRecord save(long reporter, String name, int age, Gender gender, GhanaRegion region,
                            LocalDate lastSeen, LocalDateTime approved, CaseStatus status) {
        return save(reporter, name, age, gender, region, lastSeen, approved, status, ReviewStatus.APPROVED);
    }

    private CaseRecord save(long reporter, String name, int age, Gender gender, GhanaRegion region,
                            LocalDate lastSeen, LocalDateTime approved, CaseStatus status, ReviewStatus reviewStatus) {
        CaseRecord.CaseRecordBuilder builder = aCase(reporter).fullName(name).age(age).gender(gender).region(region)
                .lastSeenDate(lastSeen).reviewStatus(reviewStatus);
        if (reviewStatus == ReviewStatus.TAKEN_DOWN) {
            builder.caseStatus(status).takedownReason("reason").takenDownBy(reporter).takenDownAt(at(12));
        } else if (reviewStatus == ReviewStatus.APPROVED) {
            builder.caseStatus(status).approvedAt(approved);
            if (status == CaseStatus.FOUND_SAFE || status == CaseStatus.FOUND_DECEASED || status == CaseStatus.CLOSED) {
                builder.resolvedAt(approved);
            }
            if (status == CaseStatus.CLOSED) builder.closedAt(approved);
        }
        return cases.saveAndFlush(builder.build());
    }

    private CaseFile file(CaseRecord c, long reporter, CaseFilePurpose purpose, FileVisibility visibility,
                          LocalDateTime uploaded, LocalDateTime deleted) {
        CaseFile file = CaseFile.builder().uploadedBy(reporter).purpose(purpose).visibility(visibility)
                .storageKey("test/" + c.getId() + "/" + uploaded + purpose + "/" + System.nanoTime()).contentType("image/jpeg")
                .sizeBytes(1).checksumSha256("a".repeat(64)).uploadedAt(uploaded).build();
        file.attachTo(c.getId(), uploaded);
        CaseFile saved = files.saveAndFlush(file);
        if (deleted != null) {
            saved.setDeletedAt(deleted);
            files.saveAndFlush(saved);
        }
        return saved;
    }

    private com.souldealers.crowdtracebackend.shared.PagedResponse<PublicCaseSummaryResponse> search(PublicCaseSearchQuery q) {
        return search(q, 0, 20);
    }

    private com.souldealers.crowdtracebackend.shared.PagedResponse<PublicCaseSummaryResponse> search(
            PublicCaseSearchQuery q, int page, int size) {
        return service.searchPublicCases(q, PageRequest.of(page, size));
    }

    private static PublicCaseSearchQuery query(String name, GhanaRegion region, Gender gender, Set<CaseStatus> statuses,
            Integer ageMin, Integer ageMax, LocalDate seenFrom, LocalDate seenTo, LocalDate approvedFrom, LocalDate approvedTo) {
        return new PublicCaseSearchQuery(name, region, gender, statuses, ageMin, ageMax,
                seenFrom, seenTo, approvedFrom, approvedTo);
    }
}
