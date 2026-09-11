package com.souldealers.crowdtracebackend.modules.identity;

import lombok.Builder;

@Builder
public record UserResponse(
        String username,
        String email,
        String displayName
        ) {
}
