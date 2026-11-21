package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@ActiveProfiles("test")
class ProfileUpdateRaceTest {

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void staleProfileSaveKeepsTheCurrentRole() {
        String email = "race-" + System.nanoTime() + "@example.com";
        User stale = userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password"))
                .displayName("Old")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build());
        jdbc.update("update users set role = 'MODERATOR' where id = ?", stale.getId());
        doReturn(Optional.of(stale)).when(userRepository).findByEmail(email);

        UserResponse response = userService.updateProfile(email, new UpdateUserProfileRequest("New"));

        Map<String, Object> row = jdbc.queryForMap(
                "select role, display_name from users where id = ?", stale.getId());
        assertThat(row.get("role")).isEqualTo("MODERATOR");
        assertThat(row.get("display_name")).isEqualTo("New");
        assertThat(response.displayName()).isEqualTo("New");
    }
}
