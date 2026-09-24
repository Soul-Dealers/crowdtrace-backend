package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.OtpService;
import com.souldealers.crowdtracebackend.modules.identity.SignUpRequest;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerifyOtpDto;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.EMAIL_NOT_NULL_MSG;
import static com.souldealers.crowdtracebackend.shared.CustomMessages.OTP_VERIFICATION_FAILED_MSG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;
    @Mock
    private OtpService otpService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AuthServiceImpl authService;

    private SignUpRequest signUpRequest;

    @BeforeEach
    void setUp() {
        signUpRequest = SignUpRequest.builder()
                .email("test@example.com")
                .password("password")
                .displayName("Test User")
                .build();
    }

    @Test
    void signUp_NewUser_ShouldSendOtpEmail() {
        // Arrange
        String email = "test@example.com";
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        
        Otp mockOtp = Otp.builder().code("123456").build();
        when(otpService.generateOtp(eq(email), eq(OtpType.CREATE))).thenReturn(mockOtp);

        // Act
        authService.signUp(signUpRequest);

        // Assert
        verify(userRepository).save(any(User.class));
        verify(otpService).generateOtp(eq(email), eq(OtpType.CREATE));
        verify(notificationService).sendOtpEmail(eq(email), eq("123456"), eq("Test User"), eq(OtpType.CREATE));
    }

    @Test
    void signUp_ExistingPendingUser_ShouldSendOtpEmail() {
        // Arrange
        String email = "test@example.com";
        User existingUser = User.builder()
                .email(email)
                .displayName("Existing User")
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .build();
        
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(existingUser));
        
        Otp mockOtp = Otp.builder().code("654321").build();
        when(otpService.generateOtp(eq(email), eq(OtpType.CREATE))).thenReturn(mockOtp);

        // Act
        authService.signUp(signUpRequest);

        // Assert
        verify(userRepository, never()).save(any(User.class));
        verify(otpService).generateOtp(eq(email), eq(OtpType.CREATE));
        verify(notificationService).sendOtpEmail(eq(email), eq("654321"), eq("Existing User"), eq(OtpType.CREATE));
    }

    @Test
    void verifyOtp_ValidCreateOtp_ActivatesAccountConsumesOtpAndSendsWelcomeEmail() {
        User pendingUser = User.builder()
                .email("verify@example.com")
                .displayName("Verify User")
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .build();
        VerifyOtpDto request = new VerifyOtpDto("123456", "VERIFY@example.com ");
        when(userRepository.findByEmailForUpdate("verify@example.com")).thenReturn(Optional.of(pendingUser));
        when(otpService.consumeOtp("123456", "verify@example.com", OtpType.CREATE)).thenReturn(true);

        GenericResponseMessage response = authService.verifyOtp(request);

        assertThat(response.message()).isEqualTo("Email verified successfully");
        assertThat(pendingUser.getAccountStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository).save(pendingUser);
        verify(otpService).consumeOtp("123456", "verify@example.com", OtpType.CREATE);
        verify(otpService, never()).isOtpValid(anyString(), anyString(), any(OtpType.class));
        verify(otpService, never()).invalidateOtp(anyString(), anyString(), any(OtpType.class));
        verify(notificationService).sendWelcomeEmail("verify@example.com", "Verify User");
    }

    @Test
    void verifyOtp_InvalidOtp_DoesNotMutateAccountOrSendWelcomeEmail() {
        User pendingUser = User.builder()
                .email("verify@example.com")
                .displayName("Verify User")
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .build();
        VerifyOtpDto request = new VerifyOtpDto("123456", "VERIFY@example.com ");
        when(userRepository.findByEmailForUpdate("verify@example.com")).thenReturn(Optional.of(pendingUser));
        when(otpService.consumeOtp("123456", "verify@example.com", OtpType.CREATE)).thenReturn(false);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> authService.verifyOtp(request)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(OTP_VERIFICATION_FAILED_MSG);

        assertThat(pendingUser.getAccountStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        verify(userRepository, never()).save(any(User.class));
        verify(otpService).consumeOtp("123456", "verify@example.com", OtpType.CREATE);
        verify(otpService, never()).isOtpValid(anyString(), anyString(), any(OtpType.class));
        verify(otpService, never()).invalidateOtp(anyString(), anyString(), any(OtpType.class));
        verify(notificationService, never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void verifyOtp_NonPendingAccount_RejectsWithoutOtpInteractions() {
        User activeUser = User.builder()
                .email("verify@example.com")
                .displayName("Verify User")
                .accountStatus(UserStatus.ACTIVE)
                .build();
        VerifyOtpDto request = new VerifyOtpDto("123456", "VERIFY@example.com ");
        when(userRepository.findByEmailForUpdate("verify@example.com")).thenReturn(Optional.of(activeUser));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> authService.verifyOtp(request)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(OTP_VERIFICATION_FAILED_MSG);

        verifyNoInteractions(otpService);
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void verifyOtp_NullRequest_ThrowsValidationExceptionWithoutInteractions() {
        assertThatThrownBy(() -> authService.verifyOtp(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_NOT_NULL_MSG);

        verifyNoInteractions(userRepository, otpService, notificationService);
    }

    @Test
    void verifyOtp_NullEmail_ThrowsValidationExceptionWithoutInteractions() {
        VerifyOtpDto request = new VerifyOtpDto("123456", null);

        assertThatThrownBy(() -> authService.verifyOtp(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_NOT_NULL_MSG);

        verifyNoInteractions(userRepository, otpService, notificationService);
    }

    @Test
    void verifyOtp_BlankEmail_ThrowsValidationExceptionWithoutInteractions() {
        VerifyOtpDto request = new VerifyOtpDto("123456", "   ");

        assertThatThrownBy(() -> authService.verifyOtp(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_NOT_NULL_MSG);

        verifyNoInteractions(userRepository, otpService, notificationService);
    }

    @Test
    void verifyOtp_WithActiveTransactionSynchronization_SendsWelcomeAfterCommit() {
        User pendingUser = User.builder()
                .email("verify@example.com")
                .displayName("Verify User")
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .build();
        VerifyOtpDto request = new VerifyOtpDto("123456", "verify@example.com");
        when(userRepository.findByEmailForUpdate("verify@example.com")).thenReturn(Optional.of(pendingUser));
        when(otpService.consumeOtp("123456", "verify@example.com", OtpType.CREATE)).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            authService.verifyOtp(request);

            verifyNoInteractions(notificationService);
            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(TransactionSynchronization.class);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(notificationService).sendWelcomeEmail("verify@example.com", "Verify User");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
