package com.souldealers.crowdtracebackend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rate-limit buckets past their purge time carry no value — charging already
 * treats an expired window as a fresh one — so this bounds table growth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitBucketCleanupJob {

    private final RateLimitBucketRepository repository;
    private final RateLimitProperties properties;

    @Scheduled(cron = "${rate-limit.cleanup-cron:0 15 3 * * *}")
    public void purgeExpired() {
        if (!properties.isEnabled()) {
            return;
        }

        int purged = repository.purgeExpired();
        log.info("Purged expired rate limit buckets (count={})", purged);
    }
}
