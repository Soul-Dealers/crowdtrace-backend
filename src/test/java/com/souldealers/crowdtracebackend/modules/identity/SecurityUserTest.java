package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUserTest {

    @Test
    void userIdReturnsTheWrappedUsersId() {
        User user = User.builder().id(42L).build();

        assertThat(new SecurityUser(user).userId()).isEqualTo(42L);
    }
}
