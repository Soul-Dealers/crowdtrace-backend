package com.souldealers.crowdtracebackend.modules.community.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Read-only view of a follow; rows are written only through CaseFollowRepository's idempotent insert. */
@Entity
@Getter
@Table(name = "case_follows")
@IdClass(CaseFollow.Key.class)
public class CaseFollow {
    @Id
    @Column(name = "user_id", updatable = false)
    private long userId;

    @Id
    @Column(name = "case_id", updatable = false)
    private long caseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CaseFollow() { }

    public record Key(long userId, long caseId) implements Serializable { }
}
