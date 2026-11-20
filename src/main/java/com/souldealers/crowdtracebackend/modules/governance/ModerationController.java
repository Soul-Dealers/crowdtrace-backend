package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/moderation/comments")
@RequiredArgsConstructor
@Tag(name = "Comment Moderation")
public class ModerationController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final ModerationService moderationService;

    @GetMapping
    @RequiresModerator
    @Operation(summary = "List reported comments", description = "One row per comment with open reports, oldest "
            + "open report first. Includes comments on cases that are no longer public.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/CommentModerationQueueListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid page "
                    + "or size", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role "
                    + "required", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<PagedResponse<CommentModerationQueueItem>> queue(
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1–50)") @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1) {
            throw new com.souldealers.crowdtracebackend.shared.ValidationException("page must be >= 0 and size >= 1");
        }
        return ApiResponse.success(moderationService.listQueue(PageRequest.of(page, size)),
                "Moderation queue retrieved successfully");
    }

    @GetMapping("/{commentId}")
    @RequiresModerator
    @Operation(summary = "Open a reported comment", description = "The comment's stored text (kept after removal), "
            + "its case, and every report on it, oldest first.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/CommentModerationDetailApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role "
                    + "required", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown comment",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<CommentModerationDetail> detail(@PathVariable Long commentId) {
        return ApiResponse.success(moderationService.getDetail(commentId), "Reported comment retrieved successfully");
    }

    @PostMapping("/{commentId}/remove")
    @RequiresModerator
    @Operation(summary = "Remove a reported comment", description = "Hides the comment's text and author from the "
            + "public thread (its replies stay), resolves every open report on it as REMOVED with these notes, and "
            + "tells each reporter. Notes are trimmed, 1–2000 characters.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ModerationDecisionApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid notes",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role "
                    + "required, or your own comment", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown comment",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already "
                    + "removed, or no open reports", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<ModerationDecisionResponse> remove(Authentication authentication, @PathVariable Long commentId,
                                                          @Valid @RequestBody RemoveCommentRequest request) {
        return ApiResponse.success(moderationService.remove(authentication.getName(), commentId, request),
                "Comment removed");
    }

    @PostMapping("/{commentId}/keep")
    @RequiresModerator
    @Operation(summary = "Keep a reported comment", description = "Leaves the comment up and resolves every open "
            + "report on it as DISMISSED. Notes are optional, trimmed, at most 2000 characters. Reporters are not told.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Kept",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ModerationDecisionApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid notes",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Moderator role "
                    + "required, or your own comment", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown comment",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "No open reports",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<ModerationDecisionResponse> keep(Authentication authentication, @PathVariable Long commentId,
                                                        @Valid @RequestBody KeepCommentRequest request) {
        return ApiResponse.success(moderationService.keep(authentication.getName(), commentId, request),
                "Comment kept");
    }
}
