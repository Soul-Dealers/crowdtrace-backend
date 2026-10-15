package com.souldealers.crowdtracebackend.modules.discovery;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public/cases")
@Tag(name = "Public Cases")
public class PublicCaseController {
    private static final String PROBLEM = "#/components/schemas/ProblemDetail";
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String LISTING_DESCRIPTION = "Returns approved public case summaries ordered by approval "
            + "time descending, then case id descending; client sort parameters are ignored. Name searches trim "
            + "surrounding whitespace and perform a case-insensitive literal substring match; names must contain "
            + "3–100 characters after trimming. Status values may be repeated to form a union; an explicitly "
            + "empty status value is invalid, while an omitted status filter includes all outcomes. Date filters "
            + "are inclusive calendar dates in UTC, and all dates must be between 0001-01-01 and 9999-12-31. "
            + "The maximum page size is 50 and offsets at or beyond 10,000 rows are rejected. Offset pages can "
            + "shift when cases are published or taken down.";

    private final CaseQueryService caseQueryService;

    @GetMapping
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/PublicCaseListResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filters, "
                    + "parameters or page depth", content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = PROBLEM))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected failure",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))})
    @Operation(summary = "List public cases", description = LISTING_DESCRIPTION)
    public ApiResponse<PagedResponse<PublicCaseSummaryResponse>> list(
            @Parameter(description = "Literal case-name substring, 3–100 trimmed characters")
            @RequestParam(required = false) String name,
            @Parameter(description = "Ghana region") @RequestParam(required = false) GhanaRegion region,
            @Parameter(description = "Gender") @RequestParam(required = false) Gender gender,
            @Parameter(description = "One or more outcomes; repeat status to form a union. Empty values are invalid.")
            @RequestParam(required = false) List<CaseStatus> status,
            @Parameter(description = "Minimum age, inclusive (0–130)") @RequestParam(required = false) Integer ageMin,
            @Parameter(description = "Maximum age, inclusive (0–130)") @RequestParam(required = false) Integer ageMax,
            @Parameter(description = "Earliest last-seen date, inclusive (UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastSeenFrom,
            @Parameter(description = "Latest last-seen date, inclusive (UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastSeenTo,
            @Parameter(description = "Earliest approval date, inclusive (UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate approvedFrom,
            @Parameter(description = "Latest approval date, inclusive (UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate approvedTo,
            @Parameter(description = "Zero-based page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1–50)") @RequestParam(required = false) Integer size) {
        int requestedPage = page == null ? 0 : page;
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (requestedPage < 0) {
            throw new ValidationException("page must be greater than or equal to 0");
        }
        if (requestedSize < 1 || requestedSize > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and 50");
        }

        PublicCaseFilter filter = new PublicCaseFilter(name, region, gender, status, ageMin, ageMax,
                lastSeenFrom, lastSeenTo, approvedFrom, approvedTo);
        var result = caseQueryService.searchPublicCases(filter.toQuery(), PageRequest.of(requestedPage, requestedSize));
        return ApiResponse.success(result, "Public cases retrieved successfully");
    }
}
