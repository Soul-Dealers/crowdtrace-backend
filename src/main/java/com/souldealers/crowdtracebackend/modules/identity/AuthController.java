package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@RequestMapping({"", "/api/v1/auth"})
@RequiredArgsConstructor
@RestController
@Tag(name = "Identity", description = "Current identity endpoints")
public class AuthController {
    private final UserService userService;
    private final AuthService authService;


    @Operation(
            summary = "List users",
            description = "Returns the current paginated user projection. Requires a Super Admin JWT.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Users retrieved successfully",
            content = @Content(schema = @Schema(ref = "#/components/schemas/UserListResponse")))
    @GetMapping("/users")
    @RequiresSuperAdmin
    @SecurityRequirement(name = "basicAuth")
    public ApiResponse<PagedResponse<UserResponse>> getUsers(@ParameterObject Pageable pageable) {
        return ApiResponse.success(
                userService.getAllUsers(pageable),
                "Users retrieved successfully");
    }

    @PostMapping("/signup")
    public ApiResponse<GenericResponseMessage> signUpUser(@Valid @RequestBody SignUpRequest request){
        var result = authService.signUp(request);
        return ApiResponse.success(result, result.message());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request){
        var result = authService.login(request);
        return ApiResponse.success(result, "Login successful");
    }

    @PostMapping("/verify-otp")
    public ApiResponse<GenericResponseMessage> verifyOtp(@Valid @RequestBody VerifyOtpDto request) {
        GenericResponseMessage result = authService.verifyOtp(request);
        return ApiResponse.success(result, result.message());
    }

    @Operation(
            summary = "request for otp resend",
            method = "POST"
    )
    @PostMapping("/resend-otp")
    public GenericResponseMessage resendOtp (@Valid @RequestBody ResendOtpRequest request){
        return authService.resendOtp(request);
    }


    @Operation(
            summary = "request to reset password",
            method = "POST"
    )
    @PostMapping("/request-password-reset")
    public GenericResponseMessage requestPasswordReset(@Valid @RequestBody PasswordResetRequest request){
        return authService.resetPasswordRequest(request);
    }


    @Operation(
            summary = "reset password with email code and new password",
            method = "POST"
    )
    @PostMapping("/reset-password")
    public GenericResponseMessage resetPassword(@Valid @RequestBody PasswordReset request){
        return authService.resetPassword(request);
    }
}
