package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.SetClosingStatementRequest;
import com.souldealers.crowdtracebackend.modules.casefile.TakeDownCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/cases/{id}")
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Moderator and admin case lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class AdminCaseLifecycleController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";
    private final CaseLifecycleService lifecycleService;

    @PatchMapping("/status")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status changed", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseLifecycleApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invalid state, stale version or transition", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Correct a case outcome", description = "Moderator or Super Admin can correct any outcome of an approved case.")
    public ApiResponse<CaseLifecycleResponse> updateStatus(Authentication authentication, @PathVariable Long id,
                                                           @Valid @RequestBody UpdateCaseStatusRequest request) {
        return ApiResponse.success(lifecycleService.updateStatusAsAdmin(authentication.getName(), id, request),
                "Case status changed successfully");
    }

    @PutMapping("/closing-statement")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Statement saved", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseLifecycleApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invalid state, stale version or unchanged statement", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Set a closing statement", description = "Sets or replaces the stripped public statement for a resolved approved case.")
    public ApiResponse<CaseLifecycleResponse> setClosingStatement(Authentication authentication,
            @PathVariable Long id, @Valid @RequestBody SetClosingStatementRequest request) {
        return ApiResponse.success(lifecycleService.setClosingStatementAsAdmin(authentication.getName(), id, request),
                "Closing statement saved successfully");
    }

    @PostMapping("/takedown")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Case taken down", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseLifecycleApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required or moderator owns case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Case is not approved or version is stale", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Take down an approved case", description = "Hides an approved case while preserving its outcome and lifecycle data.")
    public ApiResponse<CaseLifecycleResponse> takeDown(Authentication authentication, @PathVariable Long id,
                                                        @Valid @RequestBody TakeDownCaseRequest request) {
        return ApiResponse.success(lifecycleService.takeDownAsAdmin(authentication.getName(), id, request),
                "Case taken down successfully");
    }
}
