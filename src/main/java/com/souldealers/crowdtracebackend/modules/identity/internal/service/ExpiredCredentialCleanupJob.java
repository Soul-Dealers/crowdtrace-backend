package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Expired revoked tokens and consumed OTPs carry no security value because
 * reads already filter them by expiry, so this purges their table growth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredCredentialCleanupJob {

    private final RevokedTokenRepository revokedTokenRepository;
    private final OtpRepository otpRepository;

    @Scheduled(cron = "${identity.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int revokedTokens = revokedTokenRepository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC));
        int otps = otpRepository.deleteExpired(LocalDateTime.now());

        log.info("Purged expired credentials (revokedTokens={}, otps={})", revokedTokens, otps);
    }
}
