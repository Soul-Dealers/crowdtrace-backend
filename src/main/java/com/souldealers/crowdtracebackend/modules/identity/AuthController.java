package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.GenericMessageResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@RequestMapping({"", "/api/v1/auth"})
@RequiredArgsConstructor
@RestController
@Tag(name = "Identity", description = "Current identity endpoints")
@SecurityRequirement(name = "basicAuth")
public class AuthController {
    private final UserService userService;
    private final AuthService authService;


    @Operation(
            summary = "List users",
            description = "Returns the current paginated user projection. Requires HTTP Basic authentication.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Users retrieved successfully",
            content = @Content(schema = @Schema(ref = "#/components/schemas/UserListResponse")))
    @GetMapping("/users")
    public ApiResponse<PagedResponse<UserResponse>> getUsers(@ParameterObject Pageable pageable) {
        return ApiResponse.success(
                userService.getAllUsers(pageable),
                "Users retrieved successfully");
    }

    @PostMapping("/signup")
    public ApiResponse<GenericMessageResponse> signUpUser(@RequestBody SignUpRequest request){
        var result = authService.signUp(request);
        return ApiResponse.success(result, result.message());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request){
        var result = authService.login(request);
        return ApiResponse.success(result, "Login successful");
    }
}
