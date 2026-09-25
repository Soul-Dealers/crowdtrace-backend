package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, LocalDateTime now);

    @Modifying
    @Query("delete from RevokedToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
