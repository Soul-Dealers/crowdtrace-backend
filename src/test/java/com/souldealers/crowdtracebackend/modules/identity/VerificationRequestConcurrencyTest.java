package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One active request per user, whatever the type, under concurrency. A user holds at
 * most one badge, so the slot is per user rather than per type.
 *
 * <p>The service checks for an existing PENDING or APPROVED request before inserting,
 * which is check-then-act: two transactions can both see nothing and both insert,
 * because neither sees the other's uncommitted row. The partial unique index added in
 * V10 is what makes the rule hold, so these tests run Flyway against a real PostgreSQL
 * — H2 parses neither the index nor its enforcement.
 *
 * <p>The timed tests assert the invariant rather than which layer enforced it: whether
 * the loser is refused by the pre-check or by the index depends on scheduling, and both
 * must produce one success, one 409, and exactly one active row.
 * {@link #rejectsASecondActiveRowAtTheDatabase()} pins the index itself without timing.
 */
@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
class VerificationRequestConcurrencyTest {

    private static final int CONCURRENCY = 6;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationRequestRepository verificationRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void admitsOnlyOneOfManyConcurrentSubmissions() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);

        List<Outcome> outcomes = runConcurrently(times(CONCURRENCY, () ->
                verificationService.submit(applicant.getEmail(),
                        new SubmitVerificationRequest(VerificationType.NGO, "concurrent evidence"))));

        assertSingleWinner(outcomes, applicant);
    }

    /** Different types must not slip past each other: the slot is the user's, not the type's. */
    @Test
    void admitsOnlyOneOfManyConcurrentSubmissionsOfDifferentTypes() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        VerificationType[] types = VerificationType.values();

        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            VerificationType type = types[i % types.length];
            work.add(() -> verificationService.submit(applicant.getEmail(),
                    new SubmitVerificationRequest(type, "concurrent evidence")));
        }

        assertSingleWinner(runConcurrently(work), applicant);
    }

    @Test
    void admitsOnlyOneOfManyConcurrentGrants() throws Exception {
        User target = saveUser(UserRoles.REGISTERED_USER);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        List<Outcome> outcomes = runConcurrently(times(CONCURRENCY, () ->
                verificationService.grant(superAdmin.getEmail(),
                        new GrantVerificationRequest(target.getEmail(), VerificationType.POLICE,
                                "concurrent grant evidence", "granted under contention"))));

        assertSingleWinner(outcomes, target);
        assertThat(badgeOf(target)).as("the winning grant set the badge").isEqualTo("POLICE");
    }

    @Test
    void admitsOnlyOneWhenGrantsRaceSubmissions() throws Exception {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        User superAdmin = saveUser(UserRoles.SUPER_ADMIN);

        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY / 2; i++) {
            work.add(() -> verificationService.submit(applicant.getEmail(),
                    new SubmitVerificationRequest(VerificationType.SUBJECT_MATTER_EXPERT, "racing submit")));
            work.add(() -> verificationService.grant(superAdmin.getEmail(),
                    new GrantVerificationRequest(applicant.getEmail(), VerificationType.SUBJECT_MATTER_EXPERT,
                            "racing grant", null)));
        }

        assertSingleWinner(runConcurrently(work), applicant);
    }

    /**
     * The index alone, with no scheduling involved: a second active row for the same
     * user is refused by the database even when it is of a different type and nothing
     * checked first.
     */
    @Test
    void rejectsASecondActiveRowAtTheDatabase() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        verificationRequestRepository.saveAndFlush(row(applicant, VerificationType.NGO, VerificationStatus.PENDING));

        assertThatThrownBy(() -> verificationRequestRepository
                .saveAndFlush(row(applicant, VerificationType.POLICE, VerificationStatus.APPROVED)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_verification_requests_active_user");
    }

    /** A decided request releases the slot, so the user may apply again, even for another type. */
    @Test
    void allowsAnotherActiveRowOnceTheFirstIsDecided() {
        User applicant = saveUser(UserRoles.REGISTERED_USER);
        verificationRequestRepository.saveAndFlush(row(applicant, VerificationType.NGO, VerificationStatus.REJECTED));
        verificationRequestRepository.saveAndFlush(row(applicant, VerificationType.POLICE, VerificationStatus.REVOKED));

        verificationRequestRepository.saveAndFlush(
                row(applicant, VerificationType.SUBJECT_MATTER_EXPERT, VerificationStatus.PENDING));

        assertThat(activeRows(applicant)).isEqualTo(1);
    }

    private void assertSingleWinner(List<Outcome> outcomes, User user) {
        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("exactly one call may create the active request")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded()).map(Outcome::failure))
                .as("every loser is refused as a conflict, never a 500")
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
        assertThat(activeRows(user))
                .as("the database holds one active row for this user")
                .isEqualTo(1);
    }

    private int activeRows(User user) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM verification_requests
                 WHERE user_id = ? AND status IN ('PENDING', 'APPROVED')
                """, Integer.class, user.getId());
        return count == null ? 0 : count;
    }

    private String badgeOf(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT badge_type FROM users WHERE id = ?", String.class, user.getId());
    }

    private static List<Callable<Object>> times(int count, Callable<Object> call) {
        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            work.add(call);
        }
        return work;
    }

    private static List<Outcome> runConcurrently(List<Callable<Object>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        CyclicBarrier startLine = new CyclicBarrier(work.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> call : work) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    try {
                        call.call();
                        return new Outcome(true, null);
                    } catch (Exception failure) {
                        return new Outcome(false, failure);
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private VerificationRequest row(User user, VerificationType type, VerificationStatus status) {
        return VerificationRequest.builder()
                .user(user)
                .verificationType(type)
                .evidenceReference("private/evidence.pdf")
                .status(status)
                .build();
    }

    private User saveUser(UserRoles role) {
        return userRepository.saveAndFlush(User.builder()
                .email("verification-race-" + UUID.randomUUID() + "@example.com")
                .passwordHash("encoded-password")
                .displayName("Contributor " + UUID.randomUUID())
                .role(role)
                .accountStatus(UserStatus.ACTIVE)
                .build());
    }

    private record Outcome(boolean succeeded, Exception failure) {
    }
}
