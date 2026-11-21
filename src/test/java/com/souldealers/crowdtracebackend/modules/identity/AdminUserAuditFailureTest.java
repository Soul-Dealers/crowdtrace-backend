package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.souldealers.crowdtracebackend.modules.identity.AdminUserFixtures.changeRole;
import static com.souldealers.crowdtracebackend.modules.identity.AdminUserFixtures.deactivate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserAuditFailureTest extends AdminUserPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AuditRecorder auditRecorder;

    private AdminUserFixtures fixtures;
    private String adminToken;

    @BeforeEach
    void setUp() {
        fixtures = new AdminUserFixtures(userRepository, jwtService, "x");
        adminToken = fixtures.token(fixtures.save(UserRoles.SUPER_ADMIN));
        doThrow(new AuditRecordingException("audit storage unavailable"))
                .when(auditRecorder).record(any(), any(), anyLong(), any());
    }

    @Test
    void failedAuditLeavesTheRoleUnchanged() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER);

        mockMvc.perform(changeRole(adminToken, target.getId(), "MODERATOR"))
                .andExpect(status().is5xxServerError());

        assertThat(column("role", target)).isEqualTo("REGISTERED_USER");
    }

    @Test
    void failedAuditLeavesTheAccountActive() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER);

        mockMvc.perform(deactivate(adminToken, target.getId())).andExpect(status().is5xxServerError());

        assertThat(column("account_status", target)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT credentials_version FROM users WHERE id = ?", Integer.class,
                target.getId())).isEqualTo(target.getCredentialsVersion());
    }

    private String column(String name, User user) {
        return jdbc.queryForObject("SELECT " + name + " FROM users WHERE id = ?", String.class, user.getId());
    }
}
