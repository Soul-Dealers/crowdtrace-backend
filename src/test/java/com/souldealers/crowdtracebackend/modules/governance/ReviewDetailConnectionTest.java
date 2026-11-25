package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail holds at most one connection at a time: the snapshot, then the audit. Two at once would let
 * concurrent detail loads exhaust the pool. A two-connection pool (Flyway needs two at startup) with one held by
 * the caller leaves the detail exactly one.
 */
@WithMockUser(authorities = "MODERATOR")
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000", "notification.retry.interval=PT1H"})
class ReviewDetailConnectionTest extends ReviewDecisionTestSupport {

    @Test
    void aDetailLoadNeedsOnlyOneConnectionAtATime() {
        String email = email("one-connection");
        actor(email, UserRoles.MODERATOR);
        CaseRecord c = pendingCaseWithReport();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(reviewCount(c.getId())).as("the caller now holds a connection").isZero();
            assertThat(reviewService.getCaseDetail(email, c.getId()).caseDetails().id()).isEqualTo(c.getId());
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = "
                + "'CASE.SENSITIVE_DETAILS_VIEWED' AND target_id = ?", Integer.class, c.getId())).isEqualTo(1);
    }
}
