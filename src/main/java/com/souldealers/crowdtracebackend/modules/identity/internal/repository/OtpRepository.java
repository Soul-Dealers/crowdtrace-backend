package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.shared.OtpType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OtpRepository extends JpaRepository<Otp, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Otp> findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(String email, OtpType type);
    @Modifying
    @Query("delete from Otp o where o.expiredAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
