package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ApiResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/verification-requests")
@Tag(name = "Identity", description = "Verification request endpoints")
@SecurityRequirement(name = "bearerAuth")
public class VerificationRequestController {
    private final VerificationService verificationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRegisteredUser
    @Operation(summary = "Submit verification request", tags = "Identity",
            description = "Submits private evidence for administrator review.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/VerificationRequestResponse"))))
    public ApiResponse<VerificationRequestResponse> submit(
            Authentication authentication, @Valid @RequestBody SubmitVerificationRequest request) {
        return ApiResponse.success(verificationService.submit(authentication.getName(), request),
                "Verification request submitted successfully");
    }

    @GetMapping("/me")
    @RequiresRegisteredUser
    @Operation(summary = "List own verification requests", tags = "Identity",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/VerificationRequestListResponse"))))
    public ApiResponse<List<VerificationRequestResponse>> getOwnRequests(Authentication authentication) {
        return ApiResponse.success(verificationService.getOwnRequests(authentication.getName()),
                "Verification requests retrieved successfully");
    }
}
