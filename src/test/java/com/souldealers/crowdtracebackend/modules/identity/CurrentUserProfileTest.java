package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentUserProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private TokenRevocationService tokenRevocationService;

    @Test
    void returnsTheAuthenticatedUserWithoutCredentials() throws Exception {
        User user = saveActiveUser("me@example.com", "Current User");
        String token = bearerToken(user);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("Current User"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Current User"));
    }

    @Test
    void updatesOnlyTheAuthenticatedUsersDisplayName() throws Exception {
        User user = saveActiveUser("profile@example.com", "Old Name");

        mockMvc.perform(patch("/api/v1/auth/profile-settings")
                        .header("Authorization", bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("New Name"));

        User updatedUser = userRepository.findByEmail("profile@example.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updatedUser.getDisplayName()).isEqualTo("New Name");
        org.assertj.core.api.Assertions.assertThat(updatedUser.getEmail()).isEqualTo("profile@example.com");
        org.assertj.core.api.Assertions.assertThat(updatedUser.getRole()).isEqualTo(UserRoles.REGISTERED_USER);
    }

    @Test
    void logoutRevokesTheCurrentJwt() throws Exception {
        User user = saveActiveUser("logout@example.com", "Logout User");
        String token = bearerToken(user);
        String rawToken = token.substring("Bearer ".length());

        org.mockito.Mockito.when(tokenRevocationService.isRevoked(rawToken)).thenReturn(false, true);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(tokenRevocationService).revoke(ArgumentMatchers.eq(rawToken), ArgumentMatchers.any());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private User saveActiveUser(String email, String displayName) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password"))
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.generateToken(new SecurityUser(user));
    }
}
