package com.souldealers.crowdtracebackend.modules.identity.internal;

import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "jwt.access-token-expiry=0"
})
@ActiveProfiles("test")
class JwtExpiryTest {

    @Autowired
    private JwtService jwtService;

    @Test
    void expiredAccessTokenIsRejected() {
        UserDetails user = User.withUsername("expired-token@example.com")
                .password("ignored")
                .authorities("ROLE_REGISTERED_USER")
                .build();

        String token = jwtService.generateToken(user);

        assertThat(jwtService.isTokenValid(token, user)).isFalse();
    }
}
