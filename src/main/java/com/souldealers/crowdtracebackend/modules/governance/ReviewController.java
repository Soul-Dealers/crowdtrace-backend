package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** Moderator review queue, case detail and approve/reject decisions. The actor is always the authenticated user. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/review/cases")
@Tag(name = "Administration", description = "Moderator review queue and decisions")
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {
    private static final String ERRORS = "Errors use ProblemDetail: 401 unauthenticated, 403 not a moderator or "
            + "self-review, 404 unknown case, 409 stale version, already decided or no report, 500 audit failure.";

    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final ReviewService reviewService;

    @GetMapping
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(ref = "#/components/schemas/ReviewQueueListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected failure", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "List the review queue", description = "Pending cases, minors first, then oldest "
            + "submission. Offset pages can shift when cases are decided between requests. Dates are ISO "
            + "date-times in UTC, window [submittedFrom, submittedTo). " + ERRORS)
    public ApiResponse<PagedResponse<AdminCaseSummaryResponse>> queue(
            @Parameter(description = "SUBMITTED or UNDER_REVIEW; both by default") @RequestParam(required = false)
            ReviewStatus status,
            @RequestParam(required = false) Boolean priorityMinor,
            @RequestParam(required = false) Boolean duplicateFlag,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime submittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime submittedTo,
            @ParameterObject Pageable pageable) {
        ReviewQueueFilter filter = new ReviewQueueFilter(status, priorityMinor, duplicateFlag,
                submittedFrom, submittedTo);
        return ApiResponse.success(reviewService.listQueue(filter, pageable), "Review queue retrieved successfully");
    }

    @GetMapping("/{id}")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(ref = "#/components/schemas/ReviewCaseDetailApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Open a case for review", description = "Case, version, evidence metadata, duplicate "
            + "matches and review history in one snapshot. Send caseDetails.version as expectedCaseVersion. "
            + ERRORS)
    public ApiResponse<ReviewCaseDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(reviewService.getCaseDetail(id), "Review case retrieved successfully");
    }

    @PostMapping("/{id}/approve")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseReviewDecisionApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Stale version, already decided or no report", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Audit failure", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Approve a case", description = "Publishes the case's public projection. " + ERRORS)
    public ApiResponse<CaseReviewDecisionResponse> approve(
            Authentication authentication, @PathVariable Long id, @Valid @RequestBody ApproveCaseRequest request) {
        return ApiResponse.success(reviewService.approve(authentication.getName(), id, request),
                "Case approved successfully");
    }

    @PostMapping("/{id}/reject")
    @RequiresModerator
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(ref = "#/components/schemas/CaseReviewDecisionApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role required", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown case", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Stale version or already decided", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Audit failure", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "Reject a case", description = "Keeps the case private; notes are required and admin-only. "
            + ERRORS)
    public ApiResponse<CaseReviewDecisionResponse> reject(
            Authentication authentication, @PathVariable Long id, @Valid @RequestBody RejectCaseRequest request) {
        return ApiResponse.success(reviewService.reject(authentication.getName(), id, request),
                "Case rejected successfully");
    }
}
