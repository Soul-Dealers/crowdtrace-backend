package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.TokenRevocationService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpiredCredentialCleanupJobTest {

    @Autowired
    private ExpiredCredentialCleanupJob cleanupJob;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @Autowired
    private OtpRepository otpRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void removesExpiredRowsAndKeepsLiveOnes() {
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        LocalDateTime future = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);

        tokenRevocationService.revoke("expired-token-value", past);
        tokenRevocationService.revoke("live-token-value", future);

        otpRepository.save(Otp.builder()
                .email("cleanup-expired@example.com")
                .type(OtpType.CREATE)
                .code("a".repeat(64))
                .expiredAt(LocalDateTime.now().minusDays(1))
                .build());
        otpRepository.save(Otp.builder()
                .email("cleanup-live@example.com")
                .type(OtpType.CREATE)
                .code("b".repeat(64))
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build());

        long revokedBefore = revokedTokenRepository.count();
        long otpBefore = otpRepository.count();

        cleanupJob.purgeExpired();

        assertThat(revokedTokenRepository.count()).isLessThan(revokedBefore);
        assertThat(otpRepository.count()).isLessThan(otpBefore);

        assertThat(tokenRevocationService.isRevoked("live-token-value"))
                .as("a token that has not expired must stay revoked")
                .isTrue();
        assertThat(emailsRemaining())
                .as("an unexpired OTP must survive the purge")
                .contains("cleanup-live@example.com")
                .doesNotContain("cleanup-expired@example.com");
    }

    @Test
    void purgingTwiceIsSafe() {
        cleanupJob.purgeExpired();
        cleanupJob.purgeExpired();
    }

    private List<String> emailsRemaining() {
        return otpRepository.findAll().stream().map(Otp::getEmail).toList();
    }
}
