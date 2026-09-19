package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InactiveAccountAuthenticationTest {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void inactiveAccountCannotAuthenticateWithItsCorrectPassword() {
        String email = "inactive-authentication@example.com";
        String password = "correct-password";

        userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .displayName("Inactive User")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.INACTIVE)
                .build());

        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void inactiveAccountCannotUsePreviouslyIssuedJwt() throws Exception {
        User user = userRepository.saveAndFlush(User.builder()
                .email("inactive-jwt@example.com")
                .passwordHash(passwordEncoder.encode("correct-password"))
                .displayName("Inactive JWT User")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.INACTIVE)
                .build());
        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}