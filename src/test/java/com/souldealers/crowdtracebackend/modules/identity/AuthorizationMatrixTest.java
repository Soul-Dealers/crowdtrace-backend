package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthorizationMatrixTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private static final String USERS_PATH = "/api/v1/auth/users";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @ParameterizedTest
    @MethodSource("nonSuperAdminRoles")
    void rejectsUserListingForNonSuperAdmins(UserRoles role) throws Exception {
        String token = tokenFor(role);

        mockMvc.perform(request(HttpMethod.GET, USERS_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private static Stream<UserRoles> nonSuperAdminRoles() {
        return Stream.of(UserRoles.REGISTERED_USER, UserRoles.MODERATOR);
    }

    @Test
    void allowsUserListingForSuperAdmin() throws Exception {
        String token = tokenFor(UserRoles.SUPER_ADMIN);

        mockMvc.perform(request(HttpMethod.GET, USERS_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void requiresAuthenticationForUserListing() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, USERS_PATH))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("authorizationMatrix")
    void enforcesTheRolePolicyAtTheMethodBoundary(
            String path, HttpMethod method, UserRoles role, int expectedStatus) throws Exception {
        var request = request(method, path);
        if (role != null) {
            request.header("Authorization", "Bearer " + tokenFor(role));
        }

        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus));
    }

    private static Stream<Arguments> authorizationMatrix() {
        return Stream.of(
                Arguments.of("/api/public/test-discovery", HttpMethod.GET, null, 200),
                Arguments.of("/api/public/test-discovery", HttpMethod.GET, UserRoles.REGISTERED_USER, 200),
                Arguments.of("/api/public/test-discovery", HttpMethod.GET, UserRoles.MODERATOR, 200),
                Arguments.of("/api/public/test-discovery", HttpMethod.GET, UserRoles.SUPER_ADMIN, 200),
                Arguments.of("/api/test/contribute", HttpMethod.POST, null, 401),
                Arguments.of("/api/test/contribute", HttpMethod.POST, UserRoles.REGISTERED_USER, 200),
                Arguments.of("/api/test/contribute", HttpMethod.POST, UserRoles.MODERATOR, 200),
                Arguments.of("/api/test/contribute", HttpMethod.POST, UserRoles.SUPER_ADMIN, 200),
                Arguments.of("/api/test/moderate", HttpMethod.GET, null, 401),
                Arguments.of("/api/test/moderate", HttpMethod.GET, UserRoles.REGISTERED_USER, 403),
                Arguments.of("/api/test/moderate", HttpMethod.GET, UserRoles.MODERATOR, 200),
                Arguments.of("/api/test/moderate", HttpMethod.GET, UserRoles.SUPER_ADMIN, 200),
                Arguments.of("/api/test/super-admin", HttpMethod.GET, null, 401),
                Arguments.of("/api/test/super-admin", HttpMethod.GET, UserRoles.REGISTERED_USER, 403),
                Arguments.of("/api/test/super-admin", HttpMethod.GET, UserRoles.MODERATOR, 403),
                Arguments.of("/api/test/super-admin", HttpMethod.GET, UserRoles.SUPER_ADMIN, 200));
    }

    @Test
    void exposesOnlyThePersistedRoleAsAuthority() {
        User user = User.builder()
                .email("authority-source@example.com")
                .passwordHash("password-hash")
                .displayName("Verified presentation metadata does not matter")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build();

        assertThat(new SecurityUser(user).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("REGISTERED_USER");
    }

    @Test
    void rejectsAnInactiveAccountTokenAtARegisteredUserMethod() throws Exception {
        User user = saveUser(UserRoles.REGISTERED_USER, UserStatus.INACTIVE);

        mockMvc.perform(request(HttpMethod.POST, "/api/test/contribute")
                        .header("Authorization", "Bearer " + jwtService.generateToken(new SecurityUser(user))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnExpiredTokenAtARegisteredUserMethod() throws Exception {
        User user = saveUser(UserRoles.REGISTERED_USER, UserStatus.ACTIVE);
        String expiredToken = Jwts.builder()
                .setSubject(user.getEmail())
                .setIssuedAt(Date.from(Instant.now().minusSeconds(60)))
                .setExpiration(Date.from(Instant.now().minusSeconds(1)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET)), SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(request(HttpMethod.POST, "/api/test/contribute")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    private String tokenFor(UserRoles role) {
        return jwtService.generateToken(new SecurityUser(saveUser(role, UserStatus.ACTIVE)));
    }

    private User saveUser(UserRoles role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .email("authorization-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode("password"))
                .displayName(role.name())
                .role(role)
                .accountStatus(status)
                .build());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class AuthorizationProbeController {

        @GetMapping("/api/public/test-discovery")
        String discovery() {
            return "public";
        }

        @PostMapping("/api/test/contribute")
        @RequiresRegisteredUser
        String contribute() {
            return "contribute";
        }

        @GetMapping("/api/test/moderate")
        @RequiresModerator
        String moderate() {
            return "moderate";
        }

        @GetMapping("/api/test/super-admin")
        @RequiresSuperAdmin
        String superAdmin() {
            return "super-admin";
        }
    }
}
