package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.TokenRevocationService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.RevokedToken;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class TokenRevocationServiceImpl implements TokenRevocationService {

    private final RevokedTokenRepository revokedTokenRepository;

    @Override
    public void revoke(String token, LocalDateTime expiresAt) {
        String tokenHash = hash(token);

        if (!revokedTokenRepository.existsByTokenHashAndExpiresAtAfter(tokenHash, now())) {
            revokedTokenRepository.save(RevokedToken.builder()
                    .tokenHash(tokenHash)
                    .expiresAt(expiresAt)
                    .build());
        }
    }

    @Override
    public boolean isRevoked(String token) {
        return revokedTokenRepository.existsByTokenHashAndExpiresAtAfter(
                hash(token), now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Token hashing is unavailable", exception);
        }
    }
}
