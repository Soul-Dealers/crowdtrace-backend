package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Administration", description = "Verification administration endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final AdminUserService adminUserService;

    @GetMapping
    @RequiresSuperAdmin
    @Operation(summary = "List users, newest first",
            description = "Keyset-paged. q is a case-insensitive prefix of the email or display name. "
                    + "Pass nextCursor back as cursor with the same filters. size is 1 to 50.",
            tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminUserListResponse"))))
    public ApiResponse<CursorResponse<AdminUserResponse>> list(
            @RequestParam(required = false) UserRoles role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminUserService.list(role, status, q, cursor, size), "Users retrieved successfully");
    }

    @PostMapping("/{id}/role")
    @RequiresSuperAdmin
    @Operation(summary = "Change a user's role between REGISTERED_USER and MODERATOR",
            description = "403 on yourself or a Super Admin; 400 for SUPER_ADMIN; 409 if unchanged or not ACTIVE.",
            tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminUserApiResponse"))))
    public ApiResponse<AdminUserResponse> changeRole(Authentication authentication, @PathVariable long id,
                                                     @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.success(adminUserService.changeRole(authentication.getName(), id, request.role()),
                "Role changed successfully");
    }

    @PostMapping("/{id}/deactivate")
    @RequiresSuperAdmin
    @Operation(summary = "Deactivate a user",
            description = "Signs them out everywhere. 403 on yourself or a Super Admin; 409 if already deactivated.",
            tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminUserApiResponse"))))
    public ApiResponse<AdminUserResponse> deactivate(Authentication authentication, @PathVariable long id) {
        return ApiResponse.success(adminUserService.deactivate(authentication.getName(), id),
                "User deactivated successfully");
    }
}
