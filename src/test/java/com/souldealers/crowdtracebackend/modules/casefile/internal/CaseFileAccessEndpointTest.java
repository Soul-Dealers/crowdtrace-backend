package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.SignedFileAccess;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.FileAccessUnavailableException;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CaseFileAccessEndpointTest.Fixtures.class)
class CaseFileAccessEndpointTest extends CasePostgresTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 3, 1, 9, 0);
    private static final String REVIEW = "/api/v1/admin/review/cases/";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseFileRepository files;
    @Autowired private RecordingSigner signer;
    private long reporter;

    @BeforeEach
    void reporter() {
        reporter = user(email("reporter"));
        signer.last = null;
    }

    @Test
    void approvedCasePhotoRedirectsToAShortLivedSignedUrl() throws Exception {
        CaseRecord approved = caseWith(ReviewStatus.APPROVED);
        long photo = file(approved.getId(), CaseFilePurpose.PHOTO, null);

        mvc.perform(get("/api/public/cases/" + approved.getId() + "/photos/" + photo))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://signed.test/photo"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(signer.lastTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(signer.last.visibility()).isEqualTo(FileVisibility.PUBLIC);
    }

    @Test
    void nothingElseIsServedPublicly() throws Exception {
        CaseRecord approved = caseWith(ReviewStatus.APPROVED);
        long report = file(approved.getId(), CaseFilePurpose.REPORT, null);
        long deleted = file(approved.getId(), CaseFilePurpose.PHOTO, NOW);
        long otherCasePhoto = file(caseWith(ReviewStatus.APPROVED).getId(), CaseFilePurpose.PHOTO, null);
        expectPublicNotFound(approved.getId(), report);
        expectPublicNotFound(approved.getId(), deleted);
        expectPublicNotFound(approved.getId(), otherCasePhoto);

        for (ReviewStatus status : ReviewStatus.values()) {
            if (status != ReviewStatus.APPROVED) {
                CaseRecord hidden = caseWith(status);
                expectPublicNotFound(hidden.getId(), file(hidden.getId(), CaseFilePurpose.PHOTO, null));
            }
        }
        assertThat(signer.last).isNull();
    }

    @Test
    void onlyModeratorsGetReviewerLinks() throws Exception {
        CaseRecord pending = caseWith(ReviewStatus.SUBMITTED);
        long report = file(pending.getId(), CaseFilePurpose.REPORT, null);
        String path = REVIEW + pending.getId() + "/files/" + report + "/url";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token(UserRoles.REGISTERED_USER)))
                .andExpect(status().isForbidden());

        assertThat(signer.last).isNull();
        assertThat(grants(report)).isZero();
    }

    @Test
    void moderatorGetsAOneMinuteReportLinkAndTheGrantIsAudited() throws Exception {
        CaseRecord pending = caseWith(ReviewStatus.UNDER_REVIEW);
        long report = file(pending.getId(), CaseFilePurpose.REPORT, null);
        String email = email("moderator");
        long moderator = actor(email, UserRoles.MODERATOR);

        mvc.perform(get(REVIEW + pending.getId() + "/files/" + report + "/url").header("Authorization", token(email)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.url").value("https://signed.test/report"))
                .andExpect(jsonPath("$.data.expiresAt").value("2030-03-01T09:01:00Z"))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());

        assertThat(signer.lastTtl).isEqualTo(Duration.ofMinutes(1));
        assertThat(signer.last.visibility()).isEqualTo(FileVisibility.PRIVATE);
        assertThat(jdbc.queryForMap("SELECT actor_id, actor_role, action, metadata->>'caseId' AS case_id "
                + "FROM audit_events WHERE target_type='CASE_FILE' AND target_id=?", report))
                .containsEntry("actor_id", moderator).containsEntry("actor_role", "MODERATOR")
                .containsEntry("action", "CASE_FILE.PRIVATE_ACCESS_GRANTED")
                .containsEntry("case_id", String.valueOf(pending.getId()));
    }

    @Test
    void reviewerLinkForAFileOfAnotherCaseIsNotFoundAndNotAudited() throws Exception {
        CaseRecord pending = caseWith(ReviewStatus.SUBMITTED);
        long foreign = file(caseWith(ReviewStatus.SUBMITTED).getId(), CaseFilePurpose.REPORT, null);

        mvc.perform(get(REVIEW + pending.getId() + "/files/" + foreign + "/url")
                        .header("Authorization", token(UserRoles.SUPER_ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(grants(foreign)).isZero();
    }

    @Test
    void reviewDetailAdvertisesAccessWhenStorageCanSign() throws Exception {
        CaseRecord pending = caseWith(ReviewStatus.SUBMITTED);
        file(pending.getId(), CaseFilePurpose.REPORT, null);

        mvc.perform(get(REVIEW + pending.getId()).header("Authorization", token(UserRoles.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].accessAvailable").value(true));
    }

    @Test
    void storageThatCannotSignIsUnavailableNotAnError() {
        CaseRecord approved = caseWith(ReviewStatus.APPROVED);
        long photo = file(approved.getId(), CaseFilePurpose.PHOTO, null);
        var withoutSigner = new CaseFileAccessServiceImpl(files, cases, null, null, Clock.systemUTC(),
                new StaticListableBeanFactory().getBeanProvider(SignedFileAccess.class));

        assertThat(withoutSigner.signedAccessAvailable()).isFalse();
        assertThatThrownBy(() -> withoutSigner.publicPhotoUrl(approved.getId(), photo))
                .isInstanceOf(FileAccessUnavailableException.class);
    }

    private void expectPublicNotFound(long caseId, long fileId) throws Exception {
        mvc.perform(get("/api/public/cases/" + caseId + "/photos/" + fileId)).andExpect(status().isNotFound());
    }

    private CaseRecord caseWith(ReviewStatus status) {
        boolean published = status == ReviewStatus.APPROVED || status == ReviewStatus.TAKEN_DOWN;
        return cases.saveAndFlush(aCase(reporter).reviewStatus(status)
                .caseStatus(published ? CaseStatus.MISSING : null)
                .approvedAt(published ? NOW.minusDays(1) : null)
                .takedownReason(status == ReviewStatus.TAKEN_DOWN ? "Hoax" : null)
                .takenDownBy(status == ReviewStatus.TAKEN_DOWN ? reporter : null)
                .takenDownAt(status == ReviewStatus.TAKEN_DOWN ? NOW : null)
                .submittedAt(NOW.minusDays(2)).createdAt(NOW.minusDays(2)).updatedAt(NOW.minusDays(2)).build());
    }

    private long file(long caseId, CaseFilePurpose purpose, LocalDateTime deletedAt) {
        return jdbc.queryForObject("INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key, "
                + "content_type, size_bytes, checksum_sha256, uploaded_at, attached_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 32, ?, ?, ?, ?) RETURNING id", Long.class,
                caseId, reporter, purpose.name(), purpose.visibility().name(),
                purpose.storagePrefix() + "/" + UUID.randomUUID(),
                purpose == CaseFilePurpose.PHOTO ? "image/jpeg" : "application/pdf", "a".repeat(64),
                NOW.minusDays(2), NOW.minusDays(2), deletedAt);
    }

    private int grants(long fileId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type='CASE_FILE' AND target_id=?",
                Integer.class, fileId);
    }

    private String token(UserRoles role) {
        String email = email(role.name().toLowerCase());
        actor(email, role);
        return token(email);
    }

    private String token(String email) {
        return "Bearer " + jwt.generateToken(new SecurityUser(users.findByEmail(email).orElseThrow()));
    }

    private long actor(String email, UserRoles role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id", Long.class, email, role.name());
    }

    private static String email(String role) {
        return "file-access-" + role + "-" + UUID.randomUUID() + "@example.com";
    }

    static class Fixtures {
        @Bean @Primary Clock fileAccessTestClock() {
            return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        @Bean RecordingSigner recordingSigner() {
            return new RecordingSigner();
        }
    }

    static class RecordingSigner implements SignedFileAccess {
        volatile StoredObject last;
        volatile Duration lastTtl;

        @Override
        public URI signedDownloadUrl(StoredObject object, Duration ttl) {
            last = object;
            lastTtl = ttl;
            return URI.create("https://signed.test/" + (object.visibility() == FileVisibility.PRIVATE ? "report" : "photo"));
        }
    }
}
