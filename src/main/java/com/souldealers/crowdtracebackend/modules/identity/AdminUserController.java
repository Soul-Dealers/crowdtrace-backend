package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
