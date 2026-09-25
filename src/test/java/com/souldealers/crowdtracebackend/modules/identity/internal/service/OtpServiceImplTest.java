package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.config.OtpProperties;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceImplTest {

    @Mock
    private OtpRepository repository;

    private OtpServiceImpl otpService;

    @BeforeEach
    void setUp() {
        OtpProperties properties = new OtpProperties();
        properties.setHmacSecret(Base64.getEncoder().encodeToString(new byte[32]));
        otpService = new OtpServiceImpl(repository, properties);
    }

    @Test
    void consumeOtp_CurrentOtp_ExpiresAndSavesIt() {
        Otp currentOtp = Otp.builder()
                .email("verify@example.com")
                .code(otpService.keyedDigest("verify@example.com", OtpType.CREATE, "222222"))
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(currentOtp));

        assertThat(otpService.consumeOtp("222222", "verify@example.com", OtpType.CREATE))
                .isTrue();
        assertThat(currentOtp.getExpiredAt()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(repository).save(currentOtp);
    }

    @Test
    void consumeOtp_StaleCode_ReturnsFalseWithoutSaving() {
        Otp currentOtp = Otp.builder()
                .email("verify@example.com")
                .code(otpService.keyedDigest("verify@example.com", OtpType.CREATE, "222222"))
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(currentOtp));

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        verify(repository, never()).save(currentOtp);
    }

    @Test
    void consumeOtp_MissingCode_ReturnsFalseWithoutSaving() {
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.empty());

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(Otp.class));
    }

    @Test
    void consumeOtp_ExpiredCode_ReturnsFalseWithoutSaving() {
        Otp expiredOtp = Otp.builder()
                .email("verify@example.com")
                .code(otpService.keyedDigest("verify@example.com", OtpType.CREATE, "111111"))
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(expiredOtp));

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        verify(repository, never()).save(expiredOtp);
    }
}
