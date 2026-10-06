package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface UserService {

    PagedResponse<UserResponse> getAllUsers(Pageable pageable);

    UserResponse getCurrentUser(String email);

    UserResponse updateProfile(String email, UpdateUserProfileRequest request);

    /**
     * The public, pseudonymous view of a user, for other modules to render.
     * Never returns private identity fields — see {@link PublicUserResponse}.
     */
    PublicUserResponse getPublicProfile(Long userId);

    /** Private account email for admin case views; empty when the account no longer exists. */
    @RequiresModerator
    Optional<String> getAccountEmail(Long userId);

}
