package com.souldealers.crowdtracebackend.modules.community.internal.repository;

import com.souldealers.crowdtracebackend.modules.community.internal.model.Comment;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("""
            SELECT c FROM Comment c
            WHERE c.caseId = :caseId AND c.moderationStatus = com.souldealers.crowdtracebackend.modules.community.internal.model.ModerationStatus.VISIBLE
            ORDER BY c.createdAt DESC, c.id DESC""")
    List<Comment> findFirstVisible(@Param("caseId") long caseId, Limit limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.caseId = :caseId AND c.moderationStatus = com.souldealers.crowdtracebackend.modules.community.internal.model.ModerationStatus.VISIBLE
              AND (c.createdAt < :createdAt OR (c.createdAt = :createdAt AND c.id < :id))
            ORDER BY c.createdAt DESC, c.id DESC""")
    List<Comment> findVisibleAfter(@Param("caseId") long caseId, @Param("createdAt") LocalDateTime createdAt,
                                   @Param("id") long id, Limit limit);
}
