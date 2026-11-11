package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@ActiveProfiles("test")
@Transactional
class AccountEmailLookupTest {
    @Autowired private UserService userService;
    @Autowired private UserRepository users;
    @MockitoBean private NotificationService notificationService;

    @Test @WithMockUser(authorities = "MODERATOR")
    void returnsTheAccountEmailToAModerator() {
        User user = users.saveAndFlush(User.builder().email("reporter@example.com").passwordHash("x")
                .displayName("Reporter").role(UserRoles.REGISTERED_USER).accountStatus(UserStatus.ACTIVE).build());
        assertThat(userService.getAccountEmail(user.getId())).contains("reporter@example.com");
    }

    @Test @WithMockUser(authorities = "SUPER_ADMIN")
    void returnsTheAccountEmailToASuperAdmin() {
        User user = users.saveAndFlush(User.builder().email("super-view@example.com").passwordHash("x")
                .displayName("Reporter").role(UserRoles.REGISTERED_USER).accountStatus(UserStatus.ACTIVE).build());
        assertThat(userService.getAccountEmail(user.getId())).contains("super-view@example.com");
    }

    @Test @WithMockUser(authorities = "MODERATOR")
    void returnsEmptyForAnUnknownUser() {
        assertThat(userService.getAccountEmail(999999L)).isEmpty();
    }

    @Test @WithMockUser(authorities = "REGISTERED_USER")
    void refusesARegisteredUser() {
        assertThatThrownBy(() -> userService.getAccountEmail(1L)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test @WithAnonymousUser
    void refusesAnAnonymousUser() {
        assertThatThrownBy(() -> userService.getAccountEmail(1L)).isInstanceOf(AuthorizationDeniedException.class);
    }
}
