package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
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
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.paths['/users'].get").exists())
                .andExpect(jsonPath("$.paths['/users'].get.tags", hasItem("Identity")))
                .andExpect(jsonPath("$.paths['/users'].get.description",
                        containsString("HTTP Basic authentication")))
                .andExpect(jsonPath("$.paths['/users'].get.security[0].basicAuth").exists())
                .andExpect(jsonPath("$.paths['/users'].get.parameters").isNotEmpty())
                .andExpect(jsonPath("$.paths['/users'].get.parameters[*].name",
                        hasItems("page", "size")))
                .andExpect(jsonPath("$.paths['/users'].get.responses.200.content.*.schema.$ref",
                        hasItem("#/components/schemas/UserListResponse")))
                .andExpect(jsonPath("$.paths['/actuator/health/liveness'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/readiness'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/liveness'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/actuator/health/readiness'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.description",
                        containsString("not implemented")))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.description",
                        containsString("not implemented")))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.description",
                        containsString("not implemented")))
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
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/public/cases'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/public/cases/{caseId}'].get.security").isEmpty())
                // Admin operations inherit the document-level basicAuth requirement.
                .andExpect(jsonPath("$.security[0].basicAuth").exists())
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
    void keepsCurrentRuntimeSecurityAndHealthBehavior() throws Exception {
        mockMvc.perform(get("/users"))
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
