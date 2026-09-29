package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration", description = "Verification administration endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AdminVerificationController {
    private final VerificationService verificationService;

    @GetMapping("/verification-requests")
    @RequiresModerator
    @Operation(summary = "List pending verification requests", tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminVerificationListResponse"))))
    public ApiResponse<PagedResponse<AdminVerificationRequestResponse>> getQueue(
            @ParameterObject Pageable pageable) {
        return ApiResponse.success(verificationService.getPendingQueue(pageable),
                "Pending verification requests retrieved successfully");
    }

    @PostMapping("/verification-requests/{id}/approve")
    @RequiresModerator
    @Operation(summary = "Approve verification request", tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminVerificationDecisionResponse"))))
    public ApiResponse<AdminVerificationRequestResponse> approve(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody(required = false) VerificationDecisionRequest request) {
        return ApiResponse.success(verificationService.approve(authentication.getName(), id, request),
                "Verification request approved successfully");
    }

    @PostMapping("/verification-requests/{id}/reject")
    @RequiresModerator
    @Operation(summary = "Reject verification request", tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminVerificationDecisionResponse"))))
    public ApiResponse<AdminVerificationRequestResponse> reject(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody(required = false) VerificationDecisionRequest request) {
        return ApiResponse.success(verificationService.reject(authentication.getName(), id, request),
                "Verification request rejected successfully");
    }

    @PostMapping("/verification-requests/{id}/revoke")
    @RequiresSuperAdmin
    @Operation(summary = "Revoke verification badge", tags = "Administration",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminVerificationDecisionResponse"))))
    public ApiResponse<AdminVerificationRequestResponse> revoke(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody(required = false) VerificationDecisionRequest request) {
        return ApiResponse.success(verificationService.revoke(authentication.getName(), id, request),
                "Verification badge revoked successfully");
    }

    @PostMapping("/verification-grants")
    @RequiresSuperAdmin
    @Operation(summary = "Grant verification badge", tags = "Administration",
            description = "Grants a badge directly. The badge holder can read the submitted justification; internal reasoning belongs in reviewNotes.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/AdminVerificationDecisionResponse"))))
    public ApiResponse<AdminVerificationRequestResponse> grant(
            Authentication authentication, @Valid @RequestBody GrantVerificationRequest request) {
        return ApiResponse.success(verificationService.grant(authentication.getName(), request),
                "Verification badge granted successfully");
    }
}
