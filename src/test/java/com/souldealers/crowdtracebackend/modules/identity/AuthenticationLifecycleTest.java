package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private OtpRepository otpRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

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

    @Test
    void verifyOtpRouteActivatesPendingAccountConsumesOtpAndSendsWelcomeNotification() throws Exception {
        String email = "verify-route@example.com";
        String displayName = "Verify Route User";

        authService.signUp(SignUpRequest.builder()
                .email(email)
                .password("plain-password")
                .displayName(displayName)
                .build());

        Otp otp = otpRepository.findAll().stream()
                .filter(candidate -> email.equals(candidate.getEmail()))
                .filter(candidate -> candidate.getType() == OtpType.CREATE)
                .findFirst()
                .orElseThrow();

        String verificationPayload = "{\"email\":\"" + email + "\",\"code\":\"" + otp.getCode() + "\"}";

        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Email verified successfully"))
                .andExpect(jsonPath("$.data.message").value("Email verified successfully"));

        verify(notificationService, times(1)).sendWelcomeEmail(email, displayName);

        User verifiedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(verifiedUser.getAccountStatus()).isEqualTo(UserStatus.ACTIVE);

        Otp consumedOtp = otpRepository.findById(otp.getId()).orElseThrow();
        assertThat(consumedOtp.getExpiredAt()).isNotNull();
        assertThat(consumedOtp.getExpiredAt()).isBeforeOrEqualTo(java.time.LocalDateTime.now());

        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verificationPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This email is already registered"));

        then(notificationService).should(times(1)).sendWelcomeEmail(email, displayName);
    }

    @Test
    void verifyOtpRouteRejectsMalformedEmailAndShortCodeWithoutWelcomeNotification() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"verify-route-invalid@example.com\",\"code\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400));

        verify(notificationService, never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void verifyOtpRouteRejectsOlderOtpAfterResendAndAcceptsNewestOtp() throws Exception {
        String email = "verify-newest-route@example.com";
        String displayName = "Newest Route User";
        SignUpRequest request = SignUpRequest.builder()
                .email(email)
                .password("plain-password")
                .displayName(displayName)
                .build();

        authService.signUp(request);
        authService.signUp(request);

        List<Otp> issuedOtps = otpRepository.findAll().stream()
                .filter(candidate -> email.equals(candidate.getEmail()))
                .filter(candidate -> candidate.getType() == OtpType.CREATE)
                .sorted(Comparator.comparing(Otp::getCreatedAt).thenComparing(Otp::getId))
                .toList();
        assertThat(issuedOtps).hasSize(2);
        Otp olderOtp = issuedOtps.get(0);
        Otp newestOtp = issuedOtps.get(1);

        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + olderOtp.getCode() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Could not verify this OTP"));

        assertThat(userRepository.findByEmail(email).orElseThrow().getAccountStatus())
                .isEqualTo(UserStatus.PENDING_VERIFICATION);

        mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + newestOtp.getCode() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email verified successfully"));

        assertThat(userRepository.findByEmail(email).orElseThrow().getAccountStatus())
                .isEqualTo(UserStatus.ACTIVE);
        verify(notificationService).sendWelcomeEmail(email, displayName);
    }
}
