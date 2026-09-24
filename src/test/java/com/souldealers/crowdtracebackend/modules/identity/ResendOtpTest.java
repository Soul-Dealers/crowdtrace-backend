package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.TOKEN_SENT_MSG;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResendOtpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void unknownEmailReturnsTheSameGenericMessageAsAKnownOne() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-here@example.com\",\"type\":\"create\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        verify(notificationService, never())
                .sendOtpEmail(anyString(), anyString(), anyString(), eq(OtpType.CREATE));
    }

    @Test
    void resendOtpNormalizesEmailBeforeLookup() throws Exception {
        User user = saveUser("resend-normalize@example.com", "Resend Normalize");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  RESEND-NORMALIZE@EXAMPLE.COM  \" ,\"type\":\"create\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.CREATE));
    }

    @Test
    void honoursResetTypeInsteadOfAlwaysSendingCreate() throws Exception {
        User user = saveUser("resend-reset@example.com", "Resend Reset");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-reset@example.com\",\"type\":\"reset\"}"))
                .andExpect(status().isOk());

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.RESET));
    }

    @Test
    void defaultsToCreateWhenTypeIsOmitted() throws Exception {
        User user = saveUser("resend-default@example.com", "Resend Default");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-default@example.com\"}"))
                .andExpect(status().isOk());

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.CREATE));
    }

    @Test
    void rejectsUnsupportedType() throws Exception {
        saveUser("resend-bad-type@example.com", "Resend Bad Type");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-bad-type@example.com\",\"type\":\"banana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private User saveUser(String email, String displayName) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
