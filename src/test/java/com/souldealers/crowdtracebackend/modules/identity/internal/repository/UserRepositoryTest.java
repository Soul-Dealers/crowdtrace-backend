package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsUserByItsGeneratedLongId() {
        User savedUser = userRepository.saveAndFlush(User.builder()
                .displayName("Ada")
                .email("ada@example.com")
                .passwordHash("encoded-password")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build());

        assertThat(userRepository.findById(savedUser.getId()))
                .contains(savedUser);
    }

    @Test
    void findsUserByEmail() {
        User savedUser = userRepository.saveAndFlush(user("ada@example.com"));

        assertThat(userRepository.findByEmail("ada@example.com"))
                .contains(savedUser);
    }

    @Test
    void rejectsDuplicateEmail() {
        userRepository.saveAndFlush(user("ada@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user("ada@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsRoleAndAccountStatusAsExplicitEnums() {
        User savedUser = userRepository.saveAndFlush(user("ada@example.com"));
        savedUser.setRole(UserRoles.SUPER_ADMIN);
        savedUser.setAccountStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(savedUser);
        entityManager.clear();

        User reloadedUser = userRepository.findById(savedUser.getId()).orElseThrow();

        assertThat(reloadedUser.getRole()).isEqualTo(UserRoles.SUPER_ADMIN);
        assertThat(reloadedUser.getAccountStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    private User user(String email) {
        return User.builder()
                .email(email)
                .passwordHash("encoded-password")
                .displayName("Ada")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build();
    }
}
