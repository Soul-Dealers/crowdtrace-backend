package com.souldealers.crowdtracebackend.modules.identity;

import java.time.LocalDateTime;

public interface TokenRevocationService {

    void revoke(String token, LocalDateTime expiresAt);

    boolean isRevoked(String token);
}
