package com.souldealers.crowdtracebackend.modules.identity.internal;

import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    @Test
    void generatesAndReadsJwtToken() {
        UserDetails user = User.withUsername("jwt-test@example.com")
                .password("ignored")
                .authorities("ROLE_REGISTERED_USER")
                .build();

        String token = jwtService.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtService.extractEmail(token)).isEqualTo("jwt-test@example.com");
    }
}
