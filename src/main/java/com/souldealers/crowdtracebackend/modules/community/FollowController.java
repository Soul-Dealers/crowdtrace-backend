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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequiresRegisteredUser
@Tag(name = "Case Follows")
public class FollowController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final FollowService followService;

    @PostMapping("/api/user/cases/{caseId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Follow a case", description = "Follows an approved case. Following again changes nothing.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Following"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No approved "
                    + "case with this id", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM)))})
    public void follow(@AuthenticationPrincipal SecurityUser principal, @PathVariable Long caseId) {
        followService.follow(caseId, principal.userId());
    }

    @GetMapping("/api/user/cases/{caseId}/follow")
    @Operation(summary = "Follow state", description = "Whether the caller follows the case.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/FollowStateApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<FollowStateResponse> followState(@AuthenticationPrincipal SecurityUser principal,
                                                        @PathVariable Long caseId) {
        return ApiResponse.success(new FollowStateResponse(followService.isFollowing(caseId, principal.userId())),
                "Follow state retrieved successfully");
    }

    @DeleteMapping("/api/user/cases/{caseId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unfollow a case", description = "Stops following a case, even one no longer public. "
            + "Unfollowing a case the caller does not follow is harmless.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Not following"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public void unfollow(@AuthenticationPrincipal SecurityUser principal, @PathVariable Long caseId) {
        followService.unfollow(caseId, principal.userId());
    }

    @GetMapping("/api/user/follows")
    @Operation(summary = "List followed cases", description = "Returns the caller's followed cases, most recently "
            + "followed first. Cases that are no longer public are left out, so a page can be short or empty while "
            + "hasNext is true; keep following nextCursor.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/FollowedCaseListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid size "
                    + "or cursor", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<CursorResponse<FollowedCaseResponse>> listFollows(
            @AuthenticationPrincipal SecurityUser principal,
            @Parameter(description = "Opaque cursor returned as nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size (1–50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(followService.listOwn(principal.userId(), cursor, size),
                "Followed cases retrieved successfully");
    }
}
