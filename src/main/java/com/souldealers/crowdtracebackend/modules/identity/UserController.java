package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/v1/auth")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(
            summary = "Current user",
            description = "Returns the authenticated user's own profile. Never exposes credentials.",
            tags = "Authentication")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Current user profile",
            content = @Content(schema = @Schema(ref = "#/components/schemas/UserApiResponse")))
    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(Authentication authentication) {
        UserResponse result = userService.getCurrentUser(authentication.getName());
        return ApiResponse.success(result, "Current user retrieved successfully");
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated user profile",
            content = @Content(schema = @Schema(ref = "#/components/schemas/UserApiResponse")))
    @PatchMapping("/profile-settings")
    public ApiResponse<UserResponse> updateProfile(
            @Valid @RequestBody UpdateUserProfileRequest request,
            Authentication authentication) {
        UserResponse result = userService.updateProfile(authentication.getName(), request);
        return ApiResponse.success(result, "Profile updated successfully");
    }

    @Operation(
            summary = "Logout",
            description = "Revokes the presented access token for the remainder of its lifetime.",
            tags = "Authentication")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token revoked",
            content = @Content(schema = @Schema(ref = "#/components/schemas/GenericMessageApiResponse")))
    @PostMapping("/logout")
    public ApiResponse<GenericResponseMessage> logout(
            @Parameter(hidden = true) @RequestHeader("Authorization") String authorizationHeader) {
        GenericResponseMessage result = authService.logout(authorizationHeader);
        return ApiResponse.success(result, result.message());
    }
}
