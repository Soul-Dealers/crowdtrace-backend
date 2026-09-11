package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface UserService {

    PagedResponse<UserResponse> getAllUsers(Pageable pageable);
}