package com.souldealers.crowdtracebackend.modules.notification;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequiresRegisteredUser
@Tag(name = "Notifications")
public class InAppNotificationController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";

    private final InAppNotifications notifications;

    @GetMapping("/api/user/notifications")
    @Operation(summary = "List notifications", description = "Returns the caller's in-app notifications, newest "
            + "first: new comments and status changes on cases they follow. Each holds ids only; load the case or "
            + "comment for details.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/InAppNotificationListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid size "
                    + "or cursor", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<CursorResponse<InAppNotificationResponse>> listNotifications(
            @AuthenticationPrincipal SecurityUser principal,
            @Parameter(description = "Opaque cursor returned as nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size (1–50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notifications.listOwn(principal.userId(), cursor, size),
                "Notifications retrieved successfully");
    }

    @GetMapping("/api/user/notifications/unread-count")
    @Operation(summary = "Count unread notifications")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/UnreadCountApiResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal SecurityUser principal) {
        return ApiResponse.success(new UnreadCountResponse(notifications.countUnread(principal.userId())),
                "Unread count retrieved successfully");
    }

    @PostMapping("/api/user/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark a notification read", description = "Marks one of the caller's notifications read. "
            + "Marking it again is harmless.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No "
                    + "notification of the caller's with this id", content = @Content(
                    mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    public void markRead(@AuthenticationPrincipal SecurityUser principal, @PathVariable Long notificationId) {
        notifications.markRead(notificationId, principal.userId());
    }
}
