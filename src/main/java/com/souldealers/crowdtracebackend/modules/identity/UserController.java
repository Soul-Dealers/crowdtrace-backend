package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(Authentication authentication) {
        UserResponse result = userService.getCurrentUser(authentication.getName());
        return ApiResponse.success(result, "Current user retrieved successfully");
    }

    @PatchMapping("/profile-settings")
    public ApiResponse<UserResponse> updateProfile(
            @Valid @RequestBody UpdateUserProfileRequest request,
            Authentication authentication) {
        UserResponse result = userService.updateProfile(authentication.getName(), request);
        return ApiResponse.success(result, "Profile updated successfully");
    }

    @PostMapping("/logout")
    public ApiResponse<GenericResponseMessage> logout(
            @RequestHeader("Authorization") String authorizationHeader) {
        GenericResponseMessage result = authService.logout(authorizationHeader);
        return ApiResponse.success(result, result.message());
    }
}
