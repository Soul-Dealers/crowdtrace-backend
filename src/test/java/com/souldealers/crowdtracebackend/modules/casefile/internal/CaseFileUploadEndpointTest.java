package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.FileStorage;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "cors.allowed-origins=http://localhost", "rate-limit.enabled=true"})
@AutoConfigureMockMvc
@Import(CaseFileUploadEndpointTest.StorageConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseFileUploadEndpointTest extends CasePostgresTestSupport {
    private static final String PATH = "/api/user/case-files";
    private static final byte[] PDF = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = HexFormat.of().parseHex("ffd8ffe000104a46494600010100000100010000ffd9");
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserDetailsService users;
    @Value("${local.server.port}") private int port;
    private String token;

    @BeforeEach
    void reporter() {
        token = newReporter();
    }

    @Test
    void reportPdfReturnsPrivateMetadataAndAnExactSafeFieldAllowlist() throws Exception {
        String json = mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.purpose").value("REPORT"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.data.uploadedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        java.util.Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(json, "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("id", "purpose", "visibility", "contentType", "sizeBytes", "uploadedAt");
    }

    @Test
    void photoJpegReturnsPublicMetadata() throws Exception {
        mvc.perform(upload(JPEG, "PHOTO").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"));
    }

    @Test
    void aPdfCannotBeUploadedAsAPhoto() throws Exception {
        mvc.perform(upload(PDF, "PHOTO").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mvc.perform(upload(PDF, "REPORT")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingFileAndPurposeReturn400() throws Exception {
        mvc.perform(multipart(PATH).param("purpose", "REPORT").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(multipart(PATH).file(file(PDF)).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void onlyTwentySuccessfulUploadsAreAllowedAndUsersHaveSeparateQuotas() throws Exception {
        uploadTwentyReports();
        for (int i = 0; i < 2; i++) {
            mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                    .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
        mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + newReporter()))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectedContentAndChecksumsDoNotConsumeUploadQuota() throws Exception {
        for (int i = 0; i < 21; i++) {
            mvc.perform(upload(PDF, "PHOTO").header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        for (int i = 0; i < 19; i++) {
            mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated());
        }
        mvc.perform(upload(PDF, "REPORT").param("sha256", "a".repeat(64))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void realServletRejectsAFileOverTenMegabytesWith413() throws Exception {
        String boundary = "ct013-boundary";
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"report.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        multipart.write(new byte[10 * 1024 * 1024 + 1]);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + "?purpose=REPORT"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat((Integer) com.jayway.jsonpath.JsonPath.read(response.body(), "$.status")).isEqualTo(413);
        }
    }

    private void uploadTwentyReports() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(upload(PDF, "REPORT").header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated());
        }
    }

    private String newReporter() {
        String email = "upload-" + UUID.randomUUID() + "@example.com";
        user(email);
        return jwt.generateToken(users.loadUserByUsername(email));
    }

    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(byte[] content, String purpose) {
        return multipart(PATH).file(file(content)).param("purpose", purpose);
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "untrusted-name", "application/octet-stream", content);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean FileStorage testStorage() {
            return new FileStorage() {
                public void put(StoredObject metadata, Path content) {}
                public void delete(StorageKey key) {}
            };
        }
    }
}
