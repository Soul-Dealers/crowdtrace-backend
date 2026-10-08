package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.POLICY_VERSION;
import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.SUBMITTED_AT;
import static com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionFixtures.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "crowdtrace.consent.sensitive-data-version=" + POLICY_VERSION)
@Import(CaseSubmissionFixtures.FixedClockConfiguration.class)
public abstract class CaseSubmissionEndpointSupport extends CasePostgresTestSupport {
    protected static final String PATH = "/api/user/cases";
    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper mapper;
    @Autowired protected CaseFileRepository files;
    @Autowired private JwtService jwt;
    @Autowired private UserDetailsService users;
    protected long reporterId;
    protected long reportId;
    protected String token;

    @BeforeEach
    void prepareReporter() {
        String email = "submission-http-" + UUID.randomUUID() + "@example.com";
        reporterId = user(email);
        token = jwt.generateToken(users.loadUserByUsername(email));
        reportId = upload(reporterId, CaseFilePurpose.REPORT);
    }

    protected CaseSubmissionRequest validRequest() {
        return validSubmission(List.of(reportId)).build();
    }

    protected ResultActions submit(CaseSubmissionRequest request) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)));
    }

    protected long anotherReporter() {
        return user("submission-other-" + UUID.randomUUID() + "@example.com");
    }

    protected long upload(long reporter, CaseFilePurpose purpose) {
        return files.saveAndFlush(CaseFile.builder().uploadedBy(reporter).purpose(purpose)
                .visibility(purpose.visibility()).storageKey((purpose == CaseFilePurpose.REPORT ? "reports/" : "photos/") + UUID.randomUUID())
                .contentType(purpose == CaseFilePurpose.REPORT ? "application/pdf" : "image/jpeg")
                .sizeBytes(20).checksumSha256("a".repeat(64)).uploadedAt(SUBMITTED_AT).build()).getId();
    }

    protected void assertNoSensitiveValues(String body) {
        assertThat(body).doesNotContain("Ama Mensah", "Requires daily medication", "Neighbour",
                "Blue saloon car", "@ama.example", "a".repeat(64), "storageKey", "checksumSha256");
    }
}
