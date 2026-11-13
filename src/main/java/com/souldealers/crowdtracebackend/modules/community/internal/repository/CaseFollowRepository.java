package com.souldealers.crowdtracebackend.modules.community.internal.repository;

import com.souldealers.crowdtracebackend.modules.community.internal.model.CaseFollow;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CaseFollowRepository extends Repository<CaseFollow, CaseFollow.Key> {

    /** Idempotent: following again keeps the first row and its timestamp, and concurrent follows cannot collide. */
    @Modifying
    @Query(value = "INSERT INTO case_follows (user_id, case_id, created_at) VALUES (:userId, :caseId, :now)"
            + " ON CONFLICT (user_id, case_id) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("userId") long userId, @Param("caseId") long caseId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM CaseFollow f WHERE f.userId = :userId AND f.caseId = :caseId")
    void delete(@Param("userId") long userId, @Param("caseId") long caseId);

    boolean existsByUserIdAndCaseId(long userId, long caseId);

    @Query("SELECT f.userId FROM CaseFollow f WHERE f.caseId = :caseId")
    List<Long> findFollowerIds(@Param("caseId") long caseId);

    @Query("SELECT f FROM CaseFollow f WHERE f.userId = :userId ORDER BY f.createdAt DESC, f.caseId DESC")
    List<CaseFollow> findFirst(@Param("userId") long userId, Limit limit);

    @Query("SELECT f FROM CaseFollow f WHERE f.userId = :userId"
            + " AND (f.createdAt < :createdAt OR (f.createdAt = :createdAt AND f.caseId < :caseId))"
            + " ORDER BY f.createdAt DESC, f.caseId DESC")
    List<CaseFollow> findAfter(@Param("userId") long userId, @Param("createdAt") LocalDateTime createdAt,
                               @Param("caseId") long caseId, Limit limit);
}
