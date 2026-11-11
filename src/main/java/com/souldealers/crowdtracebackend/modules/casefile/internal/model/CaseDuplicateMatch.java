package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "case_duplicate_matches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaseDuplicateMatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "matched_case_id", nullable = false, updatable = false)
    private Long matchedCaseId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false, updatable = false, columnDefinition = "smallint")
    private int confidence;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "name_similarity", nullable = false, updatable = false, columnDefinition = "smallint")
    private int nameSimilarity;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "day_difference", nullable = false, updatable = false, columnDefinition = "smallint")
    private int dayDifference;

    @Column(nullable = false, updatable = false, length = 200)
    private String reasons;

    @Column(name = "algorithm_version", nullable = false, updatable = false, length = 16)
    private String algorithmVersion;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) detectedAt = LocalDateTime.now();
    }
}
