package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class AuthController {
    private final UserService userService;

    @GetMapping("/users")
    public ApiResponse<PagedResponse<UserResponse>> getUsers(Pageable pageable) {
        return ApiResponse.success(
                userService.getAllUsers(pageable),
                "Users retrieved successfully");
    }
}
