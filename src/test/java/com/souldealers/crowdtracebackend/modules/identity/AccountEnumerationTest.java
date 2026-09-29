package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountEnumerationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void verifyOtpLooksIdenticalForUnknownAndKnownEmails() throws Exception {
        saveUser("enum-known@example.com", UserStatus.PENDING_VERIFICATION);

        MvcResult known = postVerify("enum-known@example.com");
        MvcResult unknown = postVerify("enum-unknown@example.com");

        assertThat(known.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(known)).isEqualTo(bodyWithoutInstance(unknown));
    }

    @Test
    void verifyOtpOnAnAlreadyActiveAccountLooksLikeAnyOtherFailure() throws Exception {
        saveUser("enum-active@example.com", UserStatus.ACTIVE);

        MvcResult active = postVerify("enum-active@example.com");
        MvcResult unknown = postVerify("enum-absent@example.com");

        assertThat(active.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(active)).isEqualTo(bodyWithoutInstance(unknown));
    }

    @Test
    void resetPasswordLooksIdenticalForUnknownAndKnownEmails() throws Exception {
        saveUser("enum-reset@example.com", UserStatus.ACTIVE);

        MvcResult known = postReset("enum-reset@example.com");
        MvcResult unknown = postReset("enum-reset-absent@example.com");

        assertThat(known.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(known)).isEqualTo(bodyWithoutInstance(unknown));
    }

    private MvcResult postVerify(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
    }

    private MvcResult postReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\","
                                + "\"password\":\"a-new-long-password\","
                                + "\"confirmPassword\":\"a-new-long-password\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
    }

    /** correlationId and instance differ per request; everything else must match. */
    private String bodyWithoutInstance(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("\"correlationId\":\"[^\"]*\"", "")
                .replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    private User saveUser(String email, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName("Enumeration Probe")
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
