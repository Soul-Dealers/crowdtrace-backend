package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.governance.internal.CommentReportRateLimitGuard;
import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Case Discussion")
public class CommentReportController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final CommentReportService reportService;
    private final CommentReportRateLimitGuard rateLimitGuard;

    @RequiresRegisteredUser
    @PostMapping(path = "/api/user/comments/{commentId}/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Report a comment", description = "Reports another user's visible comment to moderators. "
            + "Details are trimmed, at most 1000 characters, and required for OTHER. Reporting again while your "
            + "report is open returns that report unchanged with 200. At most 10 reports per hour; refused attempts "
            + "do not count.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Reported",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/CommentReportApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Already "
                    + "reported", content = @Content(schema = @Schema(
                    ref = "#/components/schemas/CommentReportApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid body",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Your own "
                    + "comment", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No visible "
                    + "comment with this id on a public case", content = @Content(
                    mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many "
                    + "reports", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM)))})
    public ResponseEntity<ApiResponse<CommentReportResponse>> report(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable Long commentId,
            @Valid @RequestBody ReportCommentRequest request) {
        Long userId = principal.userId();
        RateLimitDecision reservation = rateLimitGuard.check(userId);
        final ReportOutcome outcome;
        try {
            outcome = reportService.report(commentId, userId, request);
        } catch (NotFoundException | AccessDeniedException | ValidationException refused) {
            // Refunded here, after the report's transaction rolled back, so the refund is not rolled back with it.
            rateLimitGuard.refund(userId, reservation);
            throw refused;
        }
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(outcome.response(),
                        outcome.created() ? "Comment reported" : "You already reported this comment"));
    }
}
