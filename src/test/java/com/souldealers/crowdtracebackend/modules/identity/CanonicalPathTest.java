package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CanonicalPathTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private NotificationService notificationService;

    /**
     * Asserted against the handler mapping rather than over HTTP on purpose.
     * An unauthenticated request to a removed path still returns 401, because
     * anyRequest().authenticated() matches before handler mapping ever runs —
     * so an HTTP-level 404 assertion would fail for a reason unrelated to the
     * change. The mapping table is the direct evidence.
     */
    @Test
    void everyAuthEndpointIsMappedExactlyOnceUnderTheCanonicalPrefix() {
        Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(patterns).contains(
                "/api/v1/auth/login",
                "/api/v1/auth/signup",
                "/api/v1/auth/verify-otp",
                "/api/v1/auth/resend-otp",
                "/api/v1/auth/request-password-reset",
                "/api/v1/auth/reset-password",
                "/api/v1/auth/users");

        assertThat(patterns).doesNotContain(
                "/login", "/signup", "/verify-otp", "/resend-otp",
                "/request-password-reset", "/reset-password", "/users");
    }
}
