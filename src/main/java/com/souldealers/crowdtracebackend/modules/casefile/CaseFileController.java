package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.CaseUploadRateLimitGuard;
import com.souldealers.crowdtracebackend.modules.identity.RequiresRegisteredUser;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

@RestController
@RequestMapping("/api/user/case-files")
@RequiredArgsConstructor
@RequiresRegisteredUser
public class CaseFileController {
    private final CaseFileUploadService uploadService;
    private final CaseUploadRateLimitGuard rateLimitGuard;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CaseFileMetadataResponse> upload(
            @AuthenticationPrincipal SecurityUser principal,
            @Parameter(hidden = true) @RequestParam CaseFilePurpose purpose,
            @RequestPart MultipartFile file,
            @Parameter(hidden = true) @RequestParam Optional<String> sha256) throws IOException {
        Long userId = principal.userId();
        RateLimitDecision reservation = rateLimitGuard.check(userId);
        try (InputStream content = file.getInputStream()) {
            CaseFileMetadataResponse metadata = uploadService.upload(userId, purpose, content, sha256);
            return ApiResponse.success(metadata, "File uploaded successfully");
        } catch (ValidationException rejection) {
            rateLimitGuard.refund(userId, reservation);
            throw rejection;
        }
    }
}
