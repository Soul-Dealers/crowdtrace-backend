package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface UserService {

    PagedResponse<UserResponse> getAllUsers(Pageable pageable);

    UserResponse getCurrentUser(String email);

    UserResponse updateProfile(String email, UpdateUserProfileRequest request);
}
