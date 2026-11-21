package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.souldealers.crowdtracebackend.modules.identity.AdminUserFixtures.changeRole;
import static org.assertj.core.api.Assertions.assertThat;

class AdminUserConcurrencyTest extends AdminUserPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void twoSuperAdminsPromotingOneUserGetOneChangeAndOneConflict() throws Exception {
        AdminUserFixtures fixtures = new AdminUserFixtures(userRepository, jwtService, "x");
        User target = fixtures.save(UserRoles.REGISTERED_USER);
        List<String> tokens = List.of(fixtures.token(fixtures.save(UserRoles.SUPER_ADMIN)),
                fixtures.token(fixtures.save(UserRoles.SUPER_ADMIN)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startLine = new CyclicBarrier(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(changeRole(token, target.getId(), "MODERATOR"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE action = 'USER.ROLE_CHANGED' AND target_id = ?
                """, Integer.class, target.getId())).isEqualTo(1);
    }
}
