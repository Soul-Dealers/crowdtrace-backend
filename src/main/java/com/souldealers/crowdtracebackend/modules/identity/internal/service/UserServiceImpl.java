package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.modules.identity.UserResponse;
import com.souldealers.crowdtracebackend.modules.identity.UpdateUserProfileRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.USER_NOT_FOUND_MSG;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public PagedResponse<UserResponse> getAllUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PagedResponse.from(userPage.map(this::buildUserResponse));

    }

    @Override
    public UserResponse getCurrentUser(String email) {
        return buildUserResponse(findUser(email));
    }

    @Override
    public UserResponse updateProfile(String email, UpdateUserProfileRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new ValidationException("Display name is required");
        }

        User user = findUser(email);
        user.setDisplayName(request.displayName().trim());
        return buildUserResponse(userRepository.save(user));
    }

    private UserResponse buildUserResponse(User user){
        return UserResponse.builder()
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .accountStatus(user.getAccountStatus())
                .createdAt(user.getCreatedAt())
                .role(user.getRole())
                .build();
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));
    }
}
