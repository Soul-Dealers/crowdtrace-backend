package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/cases")
@RequiredArgsConstructor
@RequiresRegisteredUser
public class CaseSubmissionController {
    private final CaseSubmissionService submissionService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CaseSubmissionResponse> submit(
            @AuthenticationPrincipal SecurityUser principal,
            @Valid @RequestBody CaseSubmissionRequest request) {
        CaseSubmissionResponse response = submissionService.submit(principal.userId(), request);
        return ApiResponse.success(response, "Case submitted successfully");
    }
}
