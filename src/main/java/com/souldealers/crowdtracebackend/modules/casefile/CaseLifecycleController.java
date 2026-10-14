package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/cases/{id}")
@RequiredArgsConstructor
@RequiresRegisteredUser
@Tag(name = "Case Submission", description = "Registered-user case lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class CaseLifecycleController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";
    private final CaseLifecycleService lifecycleService;
    private final CaseQueryService caseQueryService;
    private final ActorLookup actorLookup;

    @GetMapping
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reporter case detail", content = @Content(schema = @Schema(ref = "#/components/schemas/ReporterCaseApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Registered-user role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown or foreign case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Get a reporter case", description = "Returns the authenticated reporter's case detail and current case version.")
    public ApiResponse<ReporterCaseResponse> getOwnCase(Authentication authentication, @PathVariable Long id) {
        Long reporterId = actorLookup.byEmail(authentication.getName()).userId();
        return ApiResponse.success(caseQueryService.getOwnCase(id, reporterId), "Case retrieved successfully");
    }

    @PatchMapping("/status")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status changed", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseLifecycleApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Registered-user role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown or foreign case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invalid state, stale version or transition", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Change a case outcome", description = "Changes the outcome of an approved case. The reporter may resolve a missing case; moderator corrections require the admin endpoint.")
    public ApiResponse<CaseLifecycleResponse> updateStatus(Authentication authentication, @PathVariable Long id,
                                                           @Valid @RequestBody UpdateCaseStatusRequest request) {
        return ApiResponse.success(lifecycleService.updateStatus(authentication.getName(), id, request),
                "Case status changed successfully");
    }

    @PutMapping("/closing-statement")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Statement saved", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseLifecycleApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Registered-user role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown or foreign case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invalid state, stale version or unchanged statement", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Set a closing statement", description = "Sets or replaces the stripped public statement for a resolved approved case.")
    public ApiResponse<CaseLifecycleResponse> setClosingStatement(Authentication authentication,
            @PathVariable Long id, @Valid @RequestBody SetClosingStatementRequest request) {
        return ApiResponse.success(lifecycleService.setClosingStatement(authentication.getName(), id, request),
                "Closing statement saved successfully");
    }

    @PostMapping("/photos")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Photos added", content = @Content(schema = @Schema(ref = "#/components/schemas/CasePhotosAddedApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Registered-user role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown or foreign case or unavailable upload", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Case state, version or attachment conflict", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Add photos to a case", description = "Adds owned, unattached photo uploads to an approved reporter-owned case. The expected case version is checked under the case lock.")
    public ApiResponse<CasePhotosAddedResponse> addPhotos(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody AddCasePhotosRequest request) {
        return ApiResponse.success(lifecycleService.addPhotos(authentication.getName(), id, request),
                "Case photos added successfully");
    }
}
