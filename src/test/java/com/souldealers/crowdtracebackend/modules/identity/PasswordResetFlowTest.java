package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.RESET_PASSWORD_SUCC;
import static com.souldealers.crowdtracebackend.shared.CustomMessages.TOKEN_SENT_MSG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetFlowTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void requestPasswordResetNormalizesEmailAndSendsResetOtp() throws Exception {
        User user = saveUser("reset-request@example.com", "Reset Request User", "old-password");

        mockMvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"RESET-REQUEST@EXAMPLE.COM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        Otp otp = latestResetOtp(user.getEmail());
        assertThat(otp.getEmail()).isEqualTo(user.getEmail());
        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.RESET));
    }

    @Test
    void unknownPasswordResetRequestReturnsGenericSuccessWithoutSendingOtp() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-reset@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        verify(notificationService, never())
                .sendOtpEmail(anyString(), anyString(), anyString(), eq(OtpType.RESET));
    }

    @Test
    void resetPasswordUpdatesHashConsumesOtpAndRejectsReplay() throws Exception {
        String email = "reset-password@example.com";
        User user = saveUser(email, "Reset Password User", "old-password");
        authService.resetPasswordRequest(new PasswordResetRequest(email));
        String code = captureLatestOtpCode(user.getEmail(), OtpType.RESET);
        String payload = "{\"email\":\"RESET-PASSWORD@EXAMPLE.COM\","
                + "\"password\":\"new-password\","
                + "\"confirmPassword\":\"new-password\","
                + "\"code\":\"" + code + "\"}";

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESET_PASSWORD_SUCC));

        User updatedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(passwordEncoder.matches("new-password", updatedUser.getPasswordHash())).isTrue();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Could not verify your OTP"));
    }

    @Test
    void resetPasswordRequestValidatesEmailAndResetPayload() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\","
                                + "\"confirmPassword\":\"\",\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.confirmPassword").exists())
                .andExpect(jsonPath("$.errors.code").exists());
    }

    private User saveUser(String email, String displayName, String password) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private Otp latestResetOtp(String email) {
        return otpRepository.findAll().stream()
                .filter(otp -> email.equals(otp.getEmail()))
                .filter(otp -> otp.getType() == OtpType.RESET)
                .max(java.util.Comparator.comparing(Otp::getCreatedAt))
                .orElseThrow();
    }

    private String captureLatestOtpCode(String email, OtpType type) {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeastOnce()).sendOtpEmail(
                eq(email), codeCaptor.capture(), anyString(), eq(type));
        return codeCaptor.getValue();
    }
}
