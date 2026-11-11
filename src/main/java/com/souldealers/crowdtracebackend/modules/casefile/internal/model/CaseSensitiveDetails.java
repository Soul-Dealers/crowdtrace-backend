package com.souldealers.crowdtracebackend.modules.casefile.internal.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "case_sensitive_details")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaseSensitiveDetails {
    @Id
    @Column(name = "case_id")
    private Long caseId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private CaseRecord caseRecord;

    @Column(name = "reporter_relationship", nullable = false, length = 100)
    private String reporterRelationship;

    @Column(name = "medical_conditions", columnDefinition = "text")
    private String medicalConditions;

    @Column(name = "known_associates", columnDefinition = "text")
    private String knownAssociates;

    @Column(name = "vehicle_info", columnDefinition = "text")
    private String vehicleInfo;

    @Column(name = "social_media_handles", columnDefinition = "text")
    private String socialMediaHandles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
