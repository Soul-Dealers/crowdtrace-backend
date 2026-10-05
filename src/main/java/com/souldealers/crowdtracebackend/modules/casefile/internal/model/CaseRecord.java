package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cases")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaseRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "age", nullable = false, columnDefinition = "smallint")
    private int age;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 32)
    private Gender gender;

    @Column(name = "last_seen_date", nullable = false)
    private LocalDate lastSeenDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 32)
    private GhanaRegion region;

    @Column(name = "last_seen_location", nullable = false, length = 500)
    private String lastSeenLocation;

    @Column(name = "physical_description", nullable = false, columnDefinition = "text")
    private String physicalDescription;

    @Column(name = "clothing", nullable = false, columnDefinition = "text")
    private String clothing;

    @Column(name = "circumstances", nullable = false, columnDefinition = "text")
    private String circumstances;

    @Column(name = "public_contact_number", nullable = false, length = 32)
    private String publicContactNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", length = 32)
    private CaseStatus caseStatus;

    @Column(name = "closing_statement", columnDefinition = "text")
    private String closingStatement;

    @Column(name = "priority_minor", nullable = false)
    private boolean priorityMinor;

    @Column(name = "duplicate_flag", nullable = false)
    private boolean duplicateFlag;

    @Version
    private Long version;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // No sensitive-details association: public mapping only receives this row.
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (submittedAt == null) submittedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
