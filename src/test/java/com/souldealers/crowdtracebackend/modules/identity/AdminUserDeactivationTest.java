package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static com.souldealers.crowdtracebackend.modules.identity.AdminUserFixtures.deactivate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserDeactivationTest extends AdminUserPostgresTest {

    private static final String PASSWORD = "Password123!";

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
        fixtures = new AdminUserFixtures(userRepository, jwtService, passwordEncoder.encode(PASSWORD));
        admin = fixtures.save(UserRoles.SUPER_ADMIN);
        adminToken = fixtures.token(admin);
    }

    @Test
    void deactivatedUserIsLockedOutImmediately() throws Exception {
        User target = fixtures.save(UserRoles.MODERATOR);
        String existing = fixtures.token(target);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", existing)).andExpect(status().isOk());

        mockMvc.perform(deactivate(adminToken, target.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", existing))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        var row = jdbc.queryForMap("SELECT account_status, credentials_version FROM users WHERE id = ?",
                target.getId());
        assertThat(row).containsEntry("account_status", "DEACTIVATED");
        assertThat(((Number) row.get("credentials_version")).longValue()).isGreaterThan(target.getCredentialsVersion());
    }

    @Test
    void deactivationIsAudited() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER);

        mockMvc.perform(deactivate(adminToken, target.getId())).andExpect(status().isOk());

        var event = jdbc.queryForMap("""
                SELECT actor_id, actor_role, target_type FROM audit_events
                 WHERE action = 'USER.DEACTIVATED' AND target_id = ?
                """, target.getId());
        assertThat(event).containsEntry("actor_id", admin.getId())
                .containsEntry("actor_role", "SUPER_ADMIN")
                .containsEntry("target_type", "USER");
    }

    @Test
    void cannotDeactivateSelf() throws Exception {
        mockMvc.perform(deactivate(adminToken, admin.getId())).andExpect(status().isForbidden());
        assertThat(accountStatus(admin)).isEqualTo("ACTIVE");
    }

    @Test
    void cannotDeactivateSuperAdmin() throws Exception {
        User other = fixtures.save(UserRoles.SUPER_ADMIN);
        mockMvc.perform(deactivate(adminToken, other.getId())).andExpect(status().isForbidden());
        assertThat(accountStatus(other)).isEqualTo("ACTIVE");
    }

    @Test
    void alreadyDeactivatedIs409() throws Exception {
        User target = fixtures.save(UserRoles.REGISTERED_USER, UserStatus.DEACTIVATED);
        mockMvc.perform(deactivate(adminToken, target.getId())).andExpect(status().isConflict());
    }

    @Test
    void moderatorCannotDeactivate() throws Exception {
        User moderator = fixtures.save(UserRoles.MODERATOR);
        User target = fixtures.save(UserRoles.REGISTERED_USER);
        mockMvc.perform(deactivate(fixtures.token(moderator), target.getId())).andExpect(status().isForbidden());
        assertThat(accountStatus(target)).isEqualTo("ACTIVE");
    }

    private String accountStatus(User user) {
        return jdbc.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, user.getId());
    }
}
