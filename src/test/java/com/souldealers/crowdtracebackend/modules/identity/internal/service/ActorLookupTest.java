package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActorLookupTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ActorLookupImpl lookup = new ActorLookupImpl(users);

    @Test
    void activeUserResolvesToIdAndRole() {
        when(users.findByEmail("m@x.org")).thenReturn(Optional.of(user(UserStatus.ACTIVE, null)));

        assertThat(lookup.byEmail("m@x.org")).isEqualTo(new ActorView(12L, UserRoles.MODERATOR));
    }

    @Test
    void unknownEmailIsUnauthorized() {
        when(users.findByEmail("none@x.org")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.byEmail("none@x.org")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void nonActiveStatusIsUnauthorized() {
        for (UserStatus status : UserStatus.values()) {
            if (status == UserStatus.ACTIVE) {
                continue;
            }
            when(users.findByEmail("m@x.org")).thenReturn(Optional.of(user(status, null)));
            assertThatThrownBy(() -> lookup.byEmail("m@x.org")).isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void deletedUserIsUnauthorized() {
        when(users.findByEmail("m@x.org"))
                .thenReturn(Optional.of(user(UserStatus.ACTIVE, LocalDateTime.of(2026, 1, 1, 0, 0))));

        assertThatThrownBy(() -> lookup.byEmail("m@x.org")).isInstanceOf(UnauthorizedException.class);
    }

    private User user(UserStatus status, LocalDateTime deletedAt) {
        return User.builder().id(12L).role(UserRoles.MODERATOR).accountStatus(status).deletedAt(deletedAt).build();
    }
}
