package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesTheCurrentAndPlannedApiContractWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("CrowdTrace API"))
                .andExpect(jsonPath("$.info.version").value("0.1.0"))
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8080"))
                .andExpect(jsonPath("$.tags[*].name", hasItems(
                        "Operations", "Identity", "Authentication", "Public Cases", "Administration")))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.tags", hasItem("Identity")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.description",
                        containsString("Super Admin JWT")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.parameters").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.parameters[*].name",
                        hasItems("page", "size")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/users'].get.responses.200.content.*.schema.$ref",
                        hasItem("#/components/schemas/UserListResponse")))
                .andExpect(jsonPath("$.paths['/actuator/health/liveness'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/readiness'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/liveness'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/actuator/health/readiness'].get.security").isEmpty())
                // CT-009 replaced the planned auth placeholders with the shipped
                // endpoints. ADR-002 records why no refresh endpoint exists.
                .andExpect(jsonPath("$.paths['/api/auth/register']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/refresh']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.tags",
                        hasItem("Authentication")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.tags",
                        hasItem("Authentication")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.tags",
                        hasItem("Authentication")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.tags",
                        hasItem("Authentication")))
                .andExpect(jsonPath("$.paths['/api/public/cases'].get['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/public/cases/{caseId}'].get['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/public/cases'].get.description",
                        containsString("not implemented")))
                .andExpect(jsonPath("$.paths['/api/public/cases/{caseId}'].get.description",
                        containsString("not implemented")))
                .andExpect(jsonPath("$.paths['/api/admin/cases/review'].get['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/admin/cases/{caseId}/decision'].post['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/admin/cases/review'].get.description",
                        containsString("not implemented")))
                .andExpect(jsonPath("$.paths['/api/admin/cases/{caseId}/decision'].post.description",
                        containsString("not implemented")))
                // Registration and login must stay reachable without a token; /me and
                // /logout inherit the document-level bearerAuth requirement instead.
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty())
                // Every endpoint in SecurityConfig.publicEndpoints must document itself
                // as public. Without an operation-level override these inherit the
                // document-level bearerAuth requirement and tell clients to send a
                // token to endpoints that reject one.
                .andExpect(jsonPath("$.paths['/api/v1/auth/verify-otp'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/resend-otp'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/request-password-reset'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/reset-password'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/verification-requests'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/verification-requests'].post.tags", hasItem("Identity")))
                .andExpect(jsonPath("$.paths['/api/v1/verification-requests/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-requests'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-requests'].get.tags", hasItem("Administration")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-requests/{id}/approve'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-requests/{id}/reject'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-requests/{id}/revoke'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-grants'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/verification-grants'].post.description",
                        containsString("badge holder can read")))
                .andExpect(jsonPath("$.paths['/api/public/cases'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/public/cases/{caseId}'].get.security").isEmpty())
                // Admin operations inherit the document-level bearerAuth requirement.
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/admin/cases/review'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/admin/cases/{caseId}/decision'].post.security")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
                .andExpect(jsonPath("$.components.schemas.ApiResponse").exists())
                .andExpect(jsonPath("$.components.schemas.PagedResponse").exists())
                .andExpect(jsonPath("$.components.schemas.HealthResponse").exists())
                .andExpect(jsonPath("$.components.schemas.UserListResponse.properties.data.$ref")
                        .value("#/components/schemas/UserPageResponse"))
                .andExpect(jsonPath("$.components.schemas.UserPageResponse.properties.content.items.$ref")
                        .value("#/components/schemas/UserResponse"))
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.displayName").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.email").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.role").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.accountStatus").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.createdAt").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.passwordHash").doesNotExist());
    }

    @Test
    void documentsTheRegisteredUserUploadAndMultipartContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.tags", hasItem("Case Submission")))
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.description",
                        containsString("registered-user JWT")))
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post['x-crowdtrace-status']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.requestBody.content['multipart/form-data'].schema.$ref")
                        .value("#/components/schemas/CaseFileUploadRequest"))
                .andExpect(jsonPath("$.components.schemas.CaseFileUploadRequest.required",
                        containsInAnyOrder("file", "purpose")))
                .andExpect(jsonPath("$.components.schemas.CaseFileUploadRequest.properties.file.format").value("binary"))
                .andExpect(jsonPath("$.components.schemas.CaseFileUploadRequest.properties.purpose.enum",
                        containsInAnyOrder("REPORT", "PHOTO")))
                .andExpect(jsonPath("$.components.schemas.CaseFileUploadRequest.properties.sha256.type").value("string"))
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.201.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CaseFileUploadResponse"))
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.400").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.401").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.403").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.413").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.429.headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.503.headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/api/user/case-files'].post.responses.400.content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"));
    }

    @Test
    void documentsTheSubmissionAndNonBlockingEventContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.tags", hasItem("Case Submission")))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post['x-crowdtrace-status']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.description", containsString("Duplicates never block")))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.description", containsString("AFTER_COMMIT")))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.description", containsString("best-effort")))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CaseSubmissionRequest"))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.201.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CaseSubmissionApiResponse"))
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.400").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.401").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.403").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.404").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.409").exists())
                .andExpect(jsonPath("$.paths['/api/user/cases'].post.responses.404.content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"));
    }

    @Test
    void describesSubmissionValidationAndConsentInTheRequestSchemas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.required", containsInAnyOrder(
                        "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
                        "physicalDescription", "clothing", "circumstances", "publicContactNumber",
                        "sensitiveDetails", "consent", "reportFileIds")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.fullName.maxLength").value(255))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.age.minimum").value(0))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.age.maximum").value(130))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.gender.enum",
                        containsInAnyOrder("MALE", "FEMALE", "UNKNOWN")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.region.enum", containsInAnyOrder(
                        "AHAFO", "ASHANTI", "BONO", "BONO_EAST", "CENTRAL", "EASTERN", "GREATER_ACCRA",
                        "NORTH_EAST", "NORTHERN", "OTI", "SAVANNAH", "UPPER_EAST", "UPPER_WEST",
                        "VOLTA", "WESTERN", "WESTERN_NORTH")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.lastSeenDate.format").value("date"))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.lastSeenDate.description",
                        containsString("1900-01-01")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.lastSeenDate.description",
                        containsString("UTC")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.lastSeenLocation.maxLength").value(500))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.publicContactNumber.maxLength").value(32))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.sensitiveDetails.$ref")
                        .value("#/components/schemas/SensitiveDetailsInput"))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.consent.$ref")
                        .value("#/components/schemas/ConsentInput"))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.reportFileIds.minItems").value(1))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.reportFileIds.items.minimum").value(1))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.photoFileIds.maxItems").value(5))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionRequest.properties.photoFileIds.items.minimum").value(1))
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.required", contains("reporterRelationship")))
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.properties.reporterRelationship.maxLength").value(100))
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.properties.medicalConditions").exists())
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.properties.knownAssociates").exists())
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.properties.vehicleInfo").exists())
                .andExpect(jsonPath("$.components.schemas.SensitiveDetailsInput.properties.socialMediaHandles").exists())
                .andExpect(jsonPath("$.components.schemas.ConsentInput.required", containsInAnyOrder("accepted", "version", "source")))
                .andExpect(jsonPath("$.components.schemas.ConsentInput.properties.accepted.enum", contains(true)))
                .andExpect(jsonPath("$.components.schemas.ConsentInput.properties.version.maxLength").value(32))
                .andExpect(jsonPath("$.components.schemas.ConsentInput.properties.version.description",
                        containsString("crowdtrace.consent.sensitive-data-version")))
                .andExpect(jsonPath("$.components.schemas.ConsentInput.properties.source.enum",
                        containsInAnyOrder("WEB", "MOBILE", "API")));
    }

    @Test
    void documentsOnlySafeUploadMetadataAndSubmissionResponseFields() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CaseFileUploadResponse.properties.data.$ref")
                        .value("#/components/schemas/CaseFileMetadataResponse"))
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties", aMapWithSize(6)))
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.id").exists())
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.purpose.enum",
                        containsInAnyOrder("REPORT", "PHOTO")))
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.visibility.enum",
                        containsInAnyOrder("PRIVATE", "PUBLIC")))
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.contentType").exists())
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.sizeBytes").exists())
                .andExpect(jsonPath("$.components.schemas.CaseFileMetadataResponse.properties.uploadedAt").exists())
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionApiResponse.properties.data.$ref")
                        .value("#/components/schemas/CaseSubmissionResponse"))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionResponse.properties", aMapWithSize(3)))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionResponse.properties.caseId.format").value("int64"))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionResponse.properties.reviewStatus.enum", contains("SUBMITTED")))
                .andExpect(jsonPath("$.components.schemas.CaseSubmissionResponse.properties.submittedAt.format").value("date-time"));
    }

    @Test
    void keepsCurrentRuntimeSecurityAndHealthBehavior() throws Exception {
        mockMvc.perform(get("/api/v1/auth/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void allowsUnauthenticatedSignup() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"security-regression@example.com\","
                                + "\"password\":\"password123\","
                                + "\"displayName\":\"Security Regression\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
