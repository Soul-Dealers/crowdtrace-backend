package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface UserService {

    PagedResponse<UserResponse> getAllUsers(Pageable pageable);

    UserResponse getCurrentUser(String email);

    UserResponse updateProfile(String email, UpdateUserProfileRequest request);

    /**
     * The public, pseudonymous view of a user, for other modules to render.
     * Never returns private identity fields — see {@link PublicUserResponse}.
     */
    PublicUserResponse getPublicProfile(Long userId);
}
