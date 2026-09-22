package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceImplTest {

    @Mock
    private OtpRepository repository;

    private OtpServiceImpl otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpServiceImpl(repository);
    }

    @Test
    void isOtpValid_MissingOtp_ReturnsFalse() {
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.empty());

        assertThat(otpService.isOtpValid("123456", "verify@example.com", OtpType.CREATE))
                .isFalse();
    }

    @Test
    void isOtpValid_ExpiredOtp_ReturnsFalse() {
        Otp otp = Otp.builder()
                .email("verify@example.com")
                .code("123456")
                .type(OtpType.CREATE)
                .expiredAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(otp));

        assertThat(otpService.isOtpValid("123456", "verify@example.com", OtpType.CREATE))
                .isFalse();
    }

    @Test
    void isOtpValid_CurrentOtp_ReturnsTrue() {
        Otp otp = Otp.builder()
                .email("verify@example.com")
                .code("123456")
                .type(OtpType.CREATE)
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(otp));

        assertThat(otpService.isOtpValid("123456", "verify@example.com", OtpType.CREATE))
                .isTrue();
        verify(repository).findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE);
    }

    @Test
    void isOtpValid_OnlyNewestOtpForPurposeCanBeUsed() {
        Otp newestOtp = Otp.builder()
                .email("verify@example.com")
                .code("222222")
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(newestOtp));

        assertThat(otpService.isOtpValid("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        assertThat(otpService.isOtpValid("222222", "verify@example.com", OtpType.CREATE))
                .isTrue();
    }

    @Test
    void invalidateOtp_NonCurrentOtp_ThrowsConflictWithoutSaving() {
        Otp newestOtp = Otp.builder()
                .email("verify@example.com")
                .code("222222")
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(newestOtp));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                otpService.invalidateOtp("111111", "verify@example.com", OtpType.CREATE)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Could not verify this OTP");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(Otp.class));
    }

    @Test
    void consumeOtp_CurrentOtp_ExpiresAndSavesIt() {
        Otp currentOtp = Otp.builder()
                .email("verify@example.com")
                .code("222222")
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
                .code("222222")
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(currentOtp));

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(Otp.class));
    }

    @Test
    void consumeOtp_MissingCode_ReturnsFalseWithoutSaving() {
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.empty());

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(Otp.class));
    }

    @Test
    void consumeOtp_ExpiredCode_ReturnsFalseWithoutSaving() {
        Otp expiredOtp = Otp.builder()
                .email("verify@example.com")
                .code("111111")
                .type(OtpType.CREATE)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(
                "verify@example.com", OtpType.CREATE))
                .thenReturn(Optional.of(expiredOtp));

        assertThat(otpService.consumeOtp("111111", "verify@example.com", OtpType.CREATE))
                .isFalse();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(Otp.class));
    }
}
