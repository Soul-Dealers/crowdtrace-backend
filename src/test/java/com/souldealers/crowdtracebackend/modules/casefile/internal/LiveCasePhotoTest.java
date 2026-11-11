package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.notification.CasePhotosAddedEvent;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LiveCasePhotoTest.Fixtures.class)
class LiveCasePhotoTest extends CasePostgresTestSupport {
    private static final LocalDateTime ATTACHED_AT = LocalDateTime.of(2030, 2, 1, 12, 30);
    private static final String PATH = "/api/user/cases/%d/photos";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserDetailsService users;
    @Autowired private PhotoEventProbe events;
    @MockitoSpyBean private AuditRecorder auditRecorder;

    private long reporterId;
    private long caseId;
    private String token;

    @BeforeEach
    void createApprovedCase() {
        events.delivered.clear();
        String email = "live-photo-" + UUID.randomUUID() + "@example.com";
        reporterId = user(email);
        token = jwt.generateToken(users.loadUserByUsername(email));
        caseId = jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region, last_seen_location,
                    physical_description, clothing, circumstances, public_contact_number, review_status, case_status,
                    approved_at, resolved_at, closed_at, submitted_at, created_at, updated_at, version)
                VALUES (?, 'Kofi Mensah', 30, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA', 'Madina', 'Slim',
                    'Blue shirt', 'Did not return', '+233200000000', 'APPROVED', 'MISSING', ?, NULL, NULL,
                    ?, ?, ?, 0) RETURNING id
                """, Long.class, reporterId, ATTACHED_AT.minusDays(2), ATTACHED_AT.minusDays(3),
                ATTACHED_AT.minusDays(3), ATTACHED_AT.minusDays(3));
    }

    @Test
    void reporterCanAddAnOrderedPhotoBatchToAnApprovedCase() throws Exception {
        long first = upload(CaseFilePurpose.PHOTO);
        long second = upload(CaseFilePurpose.PHOTO);

        mvc.perform(post(PATH.formatted(caseId)).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseVersion\":0,\"photoFileIds\":[" + first + "," + second + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(caseId))
                .andExpect(jsonPath("$.data.caseVersion").value(1))
                .andExpect(jsonPath("$.data.photos[0].id").value(first))
                .andExpect(jsonPath("$.data.photos[1].id").value(second))
                .andExpect(jsonPath("$.data.photos[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.addedAt").value("2030-02-01T12:30:00"));

        assertThat(jdbc.queryForList("SELECT attached_at FROM case_files WHERE id IN (?, ?) ORDER BY id", first, second))
                .hasSize(2).allSatisfy(row -> assertThat(((java.sql.Timestamp) row.get("attached_at")).toLocalDateTime())
                        .isEqualTo(ATTACHED_AT));
        assertThat(jdbc.queryForList("SELECT action, metadata::text AS metadata FROM audit_events WHERE target_type = "
                + "'CASE' AND target_id = ? AND action = 'CASE.PHOTO_ADDED' ORDER BY id", caseId)).hasSize(2)
                .extracting(row -> row.get("metadata").toString()).containsExactly(
                        "{\"fileId\": " + first + "}", "{\"fileId\": " + second + "}");
        assertThat(events.delivered).singleElement().satisfies(event -> {
            assertThat(event.caseId()).isEqualTo(caseId);
            assertThat(event.fileIds()).containsExactly(first, second);
            assertThat(event.eventId()).startsWith("case-photos:");
            assertThat(event.occurredAt()).isEqualTo(ATTACHED_AT);
        });
    }

    @Test
    void staleVersionReturnsConflictAndDoesNotAttachPhotos() throws Exception {
        long photo = upload(CaseFilePurpose.PHOTO);
        mvc.perform(post(PATH.formatted(caseId)).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseVersion\":2,\"photoFileIds\":[" + photo + "]}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT case_id FROM case_files WHERE id = ?", Long.class, photo)).isNull();
    }

    @Test
    void acceptsPhotosForEveryOutcomeWhileApproved() throws Exception {
        for (CaseStatus outcome : List.of(CaseStatus.MISSING, CaseStatus.FOUND_SAFE,
                CaseStatus.FOUND_DECEASED, CaseStatus.CLOSED)) {
            long targetCase = outcome == CaseStatus.MISSING ? caseId : insertCase(reporterId,
                    ReviewStatus.APPROVED, outcome);
            long expectedVersion = jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class,
                    targetCase);
            long photo = upload(CaseFilePurpose.PHOTO);
            mvc.perform(post(PATH.formatted(targetCase)).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedCaseVersion\":" + expectedVersion + ",\"photoFileIds\":[" + photo + "]}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void pendingRejectedAndTakenDownCasesRejectPhotos() throws Exception {
        for (ReviewStatus review : List.of(ReviewStatus.SUBMITTED, ReviewStatus.REJECTED, ReviewStatus.TAKEN_DOWN)) {
            long targetCase = insertCase(reporterId, review, review == ReviewStatus.TAKEN_DOWN
                    ? CaseStatus.MISSING : null);
            long photo = upload(CaseFilePurpose.PHOTO);
            mvc.perform(post(PATH.formatted(targetCase)).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedCaseVersion\":0,\"photoFileIds\":[" + photo + "]}"))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT case_id FROM case_files WHERE id = ?", Long.class, photo)).isNull();
        }
    }

    @Test
    void enforcesPurposeOwnershipDeletionDuplicatesAndExistingPhotoCapAtomically() throws Exception {
        long photo = upload(CaseFilePurpose.PHOTO);
        long report = upload(CaseFilePurpose.REPORT);
        mvc.perform(addRequest(caseId, 0, List.of(photo, report))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT case_id FROM case_files WHERE id IN (?, ?)", photo, report))
                .allSatisfy(row -> assertThat(row.get("case_id")).isNull());

        long foreignPhoto = uploadFor(user("foreign-photo-" + UUID.randomUUID() + "@example.com"));
        mvc.perform(addRequest(caseId, 0, List.of(foreignPhoto))).andExpect(status().isNotFound());

        long deleted = upload(CaseFilePurpose.PHOTO);
        jdbc.update("UPDATE case_files SET deleted_at = ? WHERE id = ?", ATTACHED_AT, deleted);
        mvc.perform(addRequest(caseId, 0, List.of(deleted))).andExpect(status().isNotFound());

        mvc.perform(addRequest(caseId, 0, List.of(photo, photo))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE case_files SET case_id = ?, attached_at = ? WHERE id = ?", caseId, ATTACHED_AT, photo);
        mvc.perform(addRequest(caseId, 0, List.of(photo))).andExpect(status().isConflict());

        long capCase = insertCase(reporterId, ReviewStatus.APPROVED, CaseStatus.MISSING);
        for (int i = 0; i < 4; i++) attachExistingPhoto(capCase, reporterId);
        long first = upload(CaseFilePurpose.PHOTO);
        long second = upload(CaseFilePurpose.PHOTO);
        mvc.perform(addRequest(capCase, 0, List.of(first, second))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT case_id FROM case_files WHERE id IN (?, ?)", first, second))
                .allSatisfy(row -> assertThat(row.get("case_id")).isNull());
    }

    @Test
    void aMissingAuditForTheSecondPhotoRollsBackTheWholeBatch() throws Exception {
        long first = upload(CaseFilePurpose.PHOTO);
        long second = upload(CaseFilePurpose.PHOTO);
        AtomicInteger photoAudits = new AtomicInteger();
        doAnswer(call -> photoAudits.incrementAndGet() == 1 ? call.callRealMethod() : 0L)
                .when(AopTestUtils.<AuditRecorder>getUltimateTargetObject(auditRecorder)).record(any(), eq(AuditAction.CASE_PHOTO_ADDED), anyLong(), any());

        mvc.perform(addRequest(caseId, 0, List.of(first, second))).andExpect(status().isInternalServerError());

        assertThat(photoAudits).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class, caseId)).isZero();
        assertThat(jdbc.queryForList("SELECT case_id FROM case_files WHERE id IN (?, ?)", first, second))
                .allSatisfy(row -> assertThat(row.get("case_id")).isNull());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ?",
                Integer.class, caseId)).isZero();
        assertThat(events.delivered).isEmpty();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addRequest(long targetCase,
            long expectedVersion, List<Long> ids) {
        return post(PATH.formatted(targetCase)).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + expectedVersion + ",\"photoFileIds\":" + ids + "}");
    }

    private long insertCase(long owner, ReviewStatus review, CaseStatus outcome) {
        LocalDateTime approvedAt = review == ReviewStatus.APPROVED || review == ReviewStatus.TAKEN_DOWN
                ? ATTACHED_AT.minusDays(2) : null;
        LocalDateTime resolvedAt = outcome == null || outcome == CaseStatus.MISSING ? null : ATTACHED_AT.minusDays(1);
        LocalDateTime closedAt = outcome == CaseStatus.CLOSED ? ATTACHED_AT.minusHours(3) : null;
        LocalDateTime takenDownAt = review == ReviewStatus.TAKEN_DOWN ? ATTACHED_AT.minusHours(1) : null;
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region, last_seen_location,
                    physical_description, clothing, circumstances, public_contact_number, review_status, case_status,
                    approved_at, resolved_at, closed_at, takedown_reason, taken_down_by, taken_down_at,
                    submitted_at, created_at, updated_at, version)
                VALUES (?, 'Kofi Mensah', 30, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA', 'Madina', 'Slim',
                    'Blue shirt', 'Did not return', '+233200000000', ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, owner, review.name(), outcome == null ? null : outcome.name(), approvedAt, resolvedAt,
                closedAt, review == ReviewStatus.TAKEN_DOWN ? "reason" : null,
                review == ReviewStatus.TAKEN_DOWN ? owner : null, takenDownAt, ATTACHED_AT.minusDays(3),
                ATTACHED_AT.minusDays(3), ATTACHED_AT.minusDays(3));
    }

    private long uploadFor(long owner) {
        return jdbc.queryForObject("""
                INSERT INTO case_files (uploaded_by, purpose, visibility, storage_key, content_type, size_bytes,
                    checksum_sha256, uploaded_at)
                VALUES (?, 'PHOTO', 'PUBLIC', ?, 'image/jpeg', 20, ?, ?) RETURNING id
                """, Long.class, owner, "photos/" + UUID.randomUUID(), "a".repeat(64), ATTACHED_AT.minusHours(1));
    }

    private void attachExistingPhoto(long targetCase, long owner) {
        jdbc.update("""
                INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key, content_type,
                    size_bytes, checksum_sha256, uploaded_at, attached_at)
                VALUES (?, ?, 'PHOTO', 'PUBLIC', ?, 'image/jpeg', 20, ?, ?, ?)
                """, targetCase, owner, "photos/" + UUID.randomUUID(), "a".repeat(64),
                ATTACHED_AT.minusHours(1), ATTACHED_AT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock livePhotoClock() { return Clock.fixed(Instant.parse("2030-02-01T12:30:00Z"), ZoneOffset.UTC); }
        @Bean PhotoEventProbe photoEventProbe() { return new PhotoEventProbe(); }
    }

    static class PhotoEventProbe {
        final List<CasePhotosAddedEvent> delivered = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(CasePhotosAddedEvent event) { delivered.add(event); }
    }

    private long upload(CaseFilePurpose purpose) {
        String prefix = purpose == CaseFilePurpose.REPORT ? "reports/" : "photos/";
        return jdbc.queryForObject("""
                INSERT INTO case_files (uploaded_by, purpose, visibility, storage_key, content_type, size_bytes,
                    checksum_sha256, uploaded_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', 20, ?, ?) RETURNING id
                """, Long.class, reporterId, purpose.name(), purpose.visibility().name(),
                prefix + UUID.randomUUID(), "a".repeat(64), ATTACHED_AT.minusHours(1));
    }
}
