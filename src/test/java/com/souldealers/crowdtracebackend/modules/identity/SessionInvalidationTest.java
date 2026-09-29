package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionInvalidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void newAccountsStartAtVersionZeroAndTheirTokensWork() throws Exception {
        User user = saveActiveUser("version-zero@example.com");
        assertThat(user.getCredentialsVersion()).isZero();

        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void legacyTokenWithoutCredentialsVersionIsRejected() throws Exception {
        User user = saveActiveUser("legacy-no-version@example.com");
        UserDetails legacyPrincipal = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(UserRoles.REGISTERED_USER.name())
                .build();

        String legacyToken = jwtService.generateToken(legacyPrincipal);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bumpingTheVersionInvalidatesTokensCarryingTheOldOne() throws Exception {
        User user = saveActiveUser("version-bump@example.com");
        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        user.setCredentialsVersion(user.getCredentialsVersion() + 1);
        userRepository.save(user);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenMintedAfterTheBumpIsAccepted() throws Exception {
        User user = saveActiveUser("version-after@example.com");

        user.setCredentialsVersion(7);
        User saved = userRepository.save(user);

        String token = jwtService.generateToken(new SecurityUser(saved));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void editingTheProfileDoesNotLogTheUserOut() throws Exception {
        User user = saveActiveUser("version-profile@example.com");
        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(patch("/api/v1/auth/profile-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed Person\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private User saveActiveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName("Invalidation Probe")
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
