package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared setup for the CT-034 admin user tests. */
record AdminUserFixtures(UserRepository users, JwtService jwt, String passwordHash) {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    User save(UserRoles role, UserStatus status) {
        return users.saveAndFlush(User.builder()
                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "-" + SEQUENCE.incrementAndGet()
                        + "@example.com")
                .passwordHash(passwordHash)
                .displayName("Fixture")
                .role(role)
                .accountStatus(status)
                .build());
    }

    User save(UserRoles role) {
        return save(role, UserStatus.ACTIVE);
    }

    String token(User user) {
        return "Bearer " + jwt.generateToken(new SecurityUser(user));
    }

    static MockHttpServletRequestBuilder changeRole(String token, long userId, String role) {
        return post("/api/v1/admin/users/{id}/role", userId).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}");
    }

    static MockHttpServletRequestBuilder deactivate(String token, long userId) {
        return post("/api/v1/admin/users/{id}/deactivate", userId).header("Authorization", token);
    }
}
