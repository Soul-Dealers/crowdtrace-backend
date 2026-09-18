package com.souldealers.crowdtracebackend.modules.identity;

import lombok.Builder;

@Builder
public record LoginResponse(
        String displayName,
        String email,
        UserRoles role,
        String token
){
}
