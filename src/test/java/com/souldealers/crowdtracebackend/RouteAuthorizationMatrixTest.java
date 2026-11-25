package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
import com.souldealers.crowdtracebackend.modules.identity.RequiresSuperAdmin;
import com.souldealers.crowdtracebackend.modules.identity.SecurityConfig;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every real route, checked against a hand-written policy taken from the product spec's roles (§3, §4.6), not
 * from the annotations. A new route without a policy entry fails the build.
 */
@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RouteAuthorizationMatrixTest {

    /** AUTHENTICATED: any signed-in account, enforced by the filter chain only (no role annotation). */
    enum Access { PUBLIC, AUTHENTICATED, REGISTERED, MODERATOR, SUPER_ADMIN }

    private static final Map<String, Access> POLICY = Map.ofEntries(
            // Guests: sign-up, sign-in and account recovery; public case discovery and discussion.
            entry("POST /api/v1/auth/signup", Access.PUBLIC),
            entry("POST /api/v1/auth/login", Access.PUBLIC),
            entry("POST /api/v1/auth/verify-otp", Access.PUBLIC),
            entry("POST /api/v1/auth/resend-otp", Access.PUBLIC),
            entry("POST /api/v1/auth/request-password-reset", Access.PUBLIC),
            entry("POST /api/v1/auth/reset-password", Access.PUBLIC),
            entry("GET /api/public/cases", Access.PUBLIC),
            entry("GET /api/public/cases/{caseId}", Access.PUBLIC),
            entry("GET /api/public/cases/{caseId}/photos/{fileId}", Access.PUBLIC),
            entry("GET /api/public/cases/{caseId}/comments", Access.PUBLIC),
            entry("GET /api/public/cases/{caseId}/comments/count", Access.PUBLIC),
            // Any signed-in account: its own profile and session.
            entry("GET /api/v1/auth/me", Access.AUTHENTICATED),
            entry("PATCH /api/v1/auth/profile-settings", Access.AUTHENTICATED),
            entry("POST /api/v1/auth/logout", Access.AUTHENTICATED),
            // Registered users: submit and manage their own cases, comment, follow, report, request verification.
            entry("GET /api/user/cases", Access.REGISTERED),
            entry("POST /api/user/cases", Access.REGISTERED),
            entry("GET /api/user/cases/{id}", Access.REGISTERED),
            entry("PATCH /api/user/cases/{id}/status", Access.REGISTERED),
            entry("PUT /api/user/cases/{id}/closing-statement", Access.REGISTERED),
            entry("POST /api/user/cases/{id}/photos", Access.REGISTERED),
            entry("POST /api/user/case-files", Access.REGISTERED),
            entry("POST /api/user/cases/{caseId}/comments", Access.REGISTERED),
            entry("DELETE /api/user/cases/{caseId}/comments/{commentId}", Access.REGISTERED),
            entry("POST /api/user/comments/{commentId}/reports", Access.REGISTERED),
            entry("POST /api/user/cases/{caseId}/follow", Access.REGISTERED),
            entry("GET /api/user/cases/{caseId}/follow", Access.REGISTERED),
            entry("DELETE /api/user/cases/{caseId}/follow", Access.REGISTERED),
            entry("GET /api/user/follows", Access.REGISTERED),
            entry("GET /api/user/notifications", Access.REGISTERED),
            entry("GET /api/user/notifications/unread-count", Access.REGISTERED),
            entry("POST /api/user/notifications/{notificationId}/read", Access.REGISTERED),
            entry("POST /api/v1/verification-requests", Access.REGISTERED),
            entry("GET /api/v1/verification-requests/me", Access.REGISTERED),
            // Moderators: review, live-case management, comment moderation, verification requests.
            entry("GET /api/v1/admin/review/cases", Access.MODERATOR),
            entry("GET /api/v1/admin/review/cases/{id}", Access.MODERATOR),
            entry("GET /api/v1/admin/review/cases/{id}/files/{fileId}/url", Access.MODERATOR),
            entry("POST /api/v1/admin/review/cases/{id}/approve", Access.MODERATOR),
            entry("POST /api/v1/admin/review/cases/{id}/reject", Access.MODERATOR),
            entry("PATCH /api/v1/admin/cases/{id}/status", Access.MODERATOR),
            entry("PUT /api/v1/admin/cases/{id}/closing-statement", Access.MODERATOR),
            entry("POST /api/v1/admin/cases/{id}/takedown", Access.MODERATOR),
            entry("POST /api/v1/admin/cases/{id}/restore", Access.MODERATOR),
            entry("GET /api/v1/admin/moderation/comments", Access.MODERATOR),
            entry("GET /api/v1/admin/moderation/comments/{commentId}", Access.MODERATOR),
            entry("POST /api/v1/admin/moderation/comments/{commentId}/remove", Access.MODERATOR),
            entry("POST /api/v1/admin/moderation/comments/{commentId}/keep", Access.MODERATOR),
            entry("GET /api/v1/admin/verification-requests", Access.MODERATOR),
            entry("POST /api/v1/admin/verification-requests/{id}/approve", Access.MODERATOR),
            entry("POST /api/v1/admin/verification-requests/{id}/reject", Access.MODERATOR),
            // Super Admins: account administration and verification grants/revocations.
            entry("GET /api/v1/auth/users", Access.SUPER_ADMIN),
            entry("GET /api/v1/admin/users", Access.SUPER_ADMIN),
            entry("POST /api/v1/admin/users/{id}/role", Access.SUPER_ADMIN),
            entry("POST /api/v1/admin/users/{id}/deactivate", Access.SUPER_ADMIN),
            entry("POST /api/v1/admin/verification-requests/{id}/revoke", Access.SUPER_ADMIN),
            entry("POST /api/v1/admin/verification-grants", Access.SUPER_ADMIN));

    private static final Map<Access, Class<? extends Annotation>> ANNOTATION = Map.of(
            Access.REGISTERED, RequiresRegisteredUser.class,
            Access.MODERATOR, RequiresModerator.class,
            Access.SUPER_ADMIN, RequiresSuperAdmin.class);

    @Autowired private MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;

    @Test
    void everyRouteHasAPolicyEntry() {
        assertThat(routes().keySet()).containsExactlyInAnyOrderElementsOf(POLICY.keySet());
    }

    @Test
    void everyProtectedRouteDeclaresItsRole() {
        routes().forEach((route, handler) -> {
            Access access = POLICY.get(route);
            if (access == null) return; // everyRouteHasAPolicyEntry reports it
            Class<? extends Annotation> declared = declaredRole(handler);
            assertThat(declared).as(route).isEqualTo(ANNOTATION.get(access));
        });
    }

    @Test
    void guestsGet401OnEveryProtectedRoute() throws Exception {
        for (String route : nonPublicRoutes()) {
            assertThat(status(route, null)).as(route).isEqualTo(401);
        }
    }

    @Test
    void lowerRolesGet403OnBodylessElevatedRoutes() throws Exception {
        String registered = tokenFor(UserRoles.REGISTERED_USER);
        String moderator = tokenFor(UserRoles.MODERATOR);
        for (Map.Entry<String, Access> entry : POLICY.entrySet()) {
            String route = entry.getKey();
            if (!route.startsWith("GET ") && !route.startsWith("DELETE ")) continue;
            if (entry.getValue() == Access.MODERATOR || entry.getValue() == Access.SUPER_ADMIN) {
                assertThat(status(route, registered)).as(route + " as REGISTERED_USER").isEqualTo(403);
            }
            if (entry.getValue() == Access.SUPER_ADMIN) {
                assertThat(status(route, moderator)).as(route + " as MODERATOR").isEqualTo(403);
            }
        }
    }

    @Test
    void everyPublicRouteIsCoveredBySecurityConfig() {
        AntPathMatcher matcher = new AntPathMatcher();
        POLICY.forEach((route, access) -> {
            String path = path(route).replaceAll("\\{[^}]+}", "1");
            boolean permitted = Arrays.stream(SecurityConfig.publicEndpoints).anyMatch(p -> matcher.match(p, path));
            assertThat(permitted).as(route).isEqualTo(access == Access.PUBLIC);
        });
    }

    private Map<String, HandlerMethod> routes() {
        Map<String, HandlerMethod> routes = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = e.getValue();
            if (!handler.getBeanType().getName().startsWith("com.souldealers.crowdtracebackend")) continue;
            for (var method : e.getKey().getMethodsCondition().getMethods()) {
                for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                    routes.put(method.name() + " " + pattern, handler);
                }
            }
        }
        return routes;
    }

    /** The method's role annotation wins over the class's, as method security resolves it. */
    private static Class<? extends Annotation> declaredRole(HandlerMethod handler) {
        Class<? extends Annotation> onMethod = roleOn(handler.getMethod());
        return onMethod != null ? onMethod : roleOn(handler.getBeanType());
    }

    private static Class<? extends Annotation> roleOn(AnnotatedElement element) {
        return ANNOTATION.values().stream().filter(a -> AnnotatedElementUtils.hasAnnotation(element, a))
                .findFirst().orElse(null);
    }

    private static List<String> nonPublicRoutes() {
        return POLICY.entrySet().stream().filter(e -> e.getValue() != Access.PUBLIC).map(Map.Entry::getKey).toList();
    }

    private int status(String route, String token) throws Exception {
        var request = request(HttpMethod.valueOf(route.substring(0, route.indexOf(' '))),
                path(route).replaceAll("\\{[^}]+}", "1"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String path(String route) {
        return route.substring(route.indexOf(' ') + 1);
    }

    private String tokenFor(UserRoles role) {
        User user = users.saveAndFlush(User.builder().email("route-matrix-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x").displayName(role.name()).role(role).accountStatus(UserStatus.ACTIVE).build());
        return jwtService.generateToken(new SecurityUser(user));
    }
}
