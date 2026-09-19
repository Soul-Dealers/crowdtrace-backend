package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationLifecycleTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registrationPersistsAHashAndStartsPendingVerification() {
        String email = "registration@example.com";
        String password = "plain-password";

        authService.signUp(SignUpRequest.builder()
                .email(email)
                .password(password)
                .displayName("Registered User")
                .build());

        User user = userRepository.findByEmail(email).orElseThrow();

        assertThat(user.getPasswordHash()).isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(user.getAccountStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void invalidLoginDoesNotRevealWhetherEmailExists() throws Exception {
        String email = "unknown-login@example.com";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("You are not authorized to perform this action"))
                .andExpect(content().string(not(containsString(email))));
    }
}
