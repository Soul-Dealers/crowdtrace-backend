package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static com.souldealers.crowdtracebackend.modules.identity.AdminUserFixtures.changeRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserRoleTest extends AdminUserPostgresTest {

    private static final String MODERATOR_ONLY = "/api/v1/admin/verification-requests";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserFixtures fixtures;
    private User admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        fixtures = new AdminUserFixtures(userRepository, jwtService, passwordEncoder.encode("Password123!"));
        admin = fixtures.save(UserRoles.SUPER_ADMIN);
        adminToken = fixtures.token(admin);
    }

    @Test
    void promotesAndDemotes() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER);

        mockMvc.perform(changeRole(adminToken, target.getId(), "MODERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(target.getId()))
                .andExpect(jsonPath("$.data.role").value("MODERATOR"));
        assertThat(role(target)).isEqualTo("MODERATOR");

        mockMvc.perform(changeRole(adminToken, target.getId(), "REGISTERED_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("REGISTERED_USER"));
        assertThat(role(target)).isEqualTo("REGISTERED_USER");

        var events = jdbc.queryForList("""
                SELECT actor_id, actor_role, target_type, metadata->>'fromRole' AS from_role,
                       metadata->>'toRole' AS to_role
                  FROM audit_events
                 WHERE action = 'USER.ROLE_CHANGED' AND target_id = ?
                 ORDER BY id
                """, target.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("actor_id", admin.getId())
                .containsEntry("actor_role", "SUPER_ADMIN")
                .containsEntry("target_type", "USER")
                .containsEntry("from_role", "REGISTERED_USER")
                .containsEntry("to_role", "MODERATOR");
        assertThat(events.get(1)).containsEntry("from_role", "MODERATOR")
                .containsEntry("to_role", "REGISTERED_USER");
    }

    @Test
    void promotedUserCanUseModeratorEndpointsWithTheTokenTheyHave() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER);
        String existing = fixtures.token(target);
        mockMvc.perform(get(MODERATOR_ONLY).header("Authorization", existing)).andExpect(status().isForbidden());

        mockMvc.perform(changeRole(adminToken, target.getId(), "MODERATOR")).andExpect(status().isOk());

        mockMvc.perform(get(MODERATOR_ONLY).header("Authorization", existing)).andExpect(status().isOk());
    }

    @Test
    void demotedModeratorLosesAccessWithTheirExistingToken() throws Exception {
        User target = fixtures.save(UserRoles.MODERATOR);
        String existing = fixtures.token(target);
        mockMvc.perform(get(MODERATOR_ONLY).header("Authorization", existing)).andExpect(status().isOk());

        mockMvc.perform(changeRole(adminToken, target.getId(), "REGISTERED_USER")).andExpect(status().isOk());

        mockMvc.perform(get(MODERATOR_ONLY).header("Authorization", existing)).andExpect(status().isForbidden());
    }

    @Test
    void cannotChangeOwnRole() throws Exception {
        mockMvc.perform(changeRole(adminToken, admin.getId(), "MODERATOR")).andExpect(status().isForbidden());
        assertThat(role(admin)).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void cannotTouchASuperAdmin() throws Exception {
        User other = fixtures.save(UserRoles.SUPER_ADMIN);
        mockMvc.perform(changeRole(adminToken, other.getId(), "MODERATOR")).andExpect(status().isForbidden());
        assertThat(role(other)).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void cannotGrantSuperAdmin() throws Exception {
        User target = fixtures.save(UserRoles.MODERATOR);
        mockMvc.perform(changeRole(adminToken, target.getId(), "SUPER_ADMIN")).andExpect(status().isBadRequest());
        assertThat(role(target)).isEqualTo("MODERATOR");
    }

    @Test
    void sameRoleIs409() throws Exception {
        User target = fixtures.save(UserRoles.MODERATOR);
        mockMvc.perform(changeRole(adminToken, target.getId(), "MODERATOR")).andExpect(status().isConflict());
    }

    @Test
    void inactiveTargetIs409() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER, UserStatus.DEACTIVATED);
        mockMvc.perform(changeRole(adminToken, target.getId(), "MODERATOR")).andExpect(status().isConflict());
        assertThat(role(target)).isEqualTo("REGISTERED_USER");
    }

    @Test
    void unknownUserIs404() throws Exception {
        mockMvc.perform(changeRole(adminToken, Long.MAX_VALUE, "MODERATOR")).andExpect(status().isNotFound());
    }

    @Test
    void moderatorCannotChangeRoles() throws Exception {
        User moderator = fixtures.save(UserRoles.MODERATOR);
        User target = fixtures.save(UserRoles.REGISTERED_USER);
        String token = fixtures.token(moderator);

        mockMvc.perform(changeRole(token, target.getId(), "MODERATOR")).andExpect(status().isForbidden());
        mockMvc.perform(changeRole(token, moderator.getId(), "REGISTERED_USER")).andExpect(status().isForbidden());
        assertThat(role(target)).isEqualTo("REGISTERED_USER");
        assertThat(role(moderator)).isEqualTo("MODERATOR");
    }

    private String role(User user) {
        return jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, user.getId());
    }
}
