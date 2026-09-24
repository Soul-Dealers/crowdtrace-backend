package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.UpdateUserProfileRequest;
import com.souldealers.crowdtracebackend.modules.identity.UserResponse;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void getCurrentUserMapsTheStoredUserToAResponse() {
        User user = user();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(user.getEmail());

        assertThat(response.displayName()).isEqualTo("Current User");
        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.role()).isEqualTo(UserRoles.REGISTERED_USER);
        assertThat(response.accountStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void updateProfileTrimsAndPersistsTheNewDisplayName() {
        User user = user();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UserResponse response = userService.updateProfile(
                user.getEmail(), new UpdateUserProfileRequest("  Updated User  "));

        assertThat(user.getDisplayName()).isEqualTo("Updated User");
        assertThat(response.displayName()).isEqualTo("Updated User");
        verify(userRepository).save(user);
    }

    @Test
    void updateProfileRejectsBlankDisplayNames() {
        assertThatThrownBy(() -> userService.updateProfile(
                "current@example.com", new UpdateUserProfileRequest("  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Display name is required");
    }

    @Test
    void getCurrentUserRejectsUnknownEmails() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser("unknown@example.com"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    private User user() {
        return User.builder()
                .email("current@example.com")
                .passwordHash("password-hash")
                .displayName("Current User")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
