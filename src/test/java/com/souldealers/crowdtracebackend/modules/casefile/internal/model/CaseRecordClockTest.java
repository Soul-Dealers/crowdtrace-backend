package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseRecordClockTest extends CasePostgresTestSupport {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2030-01-02T03:04:05Z");

    @Autowired private CaseRecordRepository cases;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void stampsCreationAndUpdateTimesFromTheInjectedUtcClock() {
        clock.set(CREATED);
        long reporter = user("clock-" + UUID.randomUUID() + "@example.com");
        Long id = cases.saveAndFlush(aCase(reporter).build()).getId();

        CaseRecord created = cases.findById(id).orElseThrow();
        LocalDateTime createdAt = LocalDateTime.ofInstant(CREATED, ZoneOffset.UTC);
        assertThat(created.getCreatedAt()).isEqualTo(createdAt);
        assertThat(created.getSubmittedAt()).isEqualTo(createdAt);
        assertThat(created.getUpdatedAt()).isEqualTo(createdAt);

        clock.set(UPDATED);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                cases.findById(id).orElseThrow().setFullName("Ama Mensah"));

        CaseRecord updated = cases.findById(id).orElseThrow();
        assertThat(updated.getUpdatedAt()).isEqualTo(LocalDateTime.ofInstant(UPDATED, ZoneOffset.UTC));
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        MutableClock caseRecordTestClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant = CREATED;

        void set(Instant instant) { this.instant = instant; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }

        @Override public Instant instant() { return instant; }
    }
}
