package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Case Discussion")
public class CommentController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final CommentService commentService;

    @GetMapping("/api/public/cases/{caseId}/comments")
    @Operation(summary = "List case comments", description = "Returns an approved case's top-level comments, or one "
            + "comment's direct replies when parentCommentId is set, newest first, under each author's display name and "
            + "badge only. Removed comments are never returned. A comment its author deleted keeps its place with "
            + "deleted=true and no author or body, so its replies stay readable. Use nextCursor to load older ones.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/CommentListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid size, "
                    + "cursor or parentCommentId", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approved "
                    + "case with this id, or no visible parent comment on it", content = @Content(mediaType =
                    "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<CursorResponse<CommentResponse>> list(
            @PathVariable Long caseId,
            @Parameter(description = "List this comment's direct replies instead of top-level comments")
            @RequestParam(required = false) Long parentCommentId,
            @Parameter(description = "Opaque cursor returned as nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size (1–50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(commentService.listPublic(caseId, parentCommentId, cursor, size), "Comments retrieved successfully");
    }

    @RequiresRegisteredUser
    @PostMapping(path = "/api/user/cases/{caseId}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a case comment", description = "Posts a comment to an approved case's discussion "
            + "under the caller's display name, as a reply when parentCommentId is set. Requires a registered user. The "
            + "body is trimmed and must be 1–2000 characters.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/CommentApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid body",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approved "
                    + "case with this id, or the parent comment is missing, removed or deleted",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<CommentResponse> create(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable Long caseId,
            @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.success(commentService.create(caseId, principal.userId(), request), "Comment posted");
    }

    @RequiresRegisteredUser
    @DeleteMapping("/api/user/cases/{caseId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete own comment", description = "Deletes the caller's own comment: its text is erased "
            + "and it shows as deleted, while its replies stay. Deleting again is harmless.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approved "
                    + "case with this id, or no visible comment of the caller's on it",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public void delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable Long caseId,
                       @PathVariable Long commentId) {
        commentService.deleteByAuthor(caseId, commentId, principal.userId());
    }
}
