package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;

@Entity
@Table(name = "case_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaseConsent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, updatable = false, length = 32)
    private ConsentType consentType;

    @Column(name = "consent_version", nullable = false, updatable = false, length = 32)
    private String consentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 32)
    private ConsentSource source;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private LocalDateTime acceptedAt;

    @PrePersist
    protected void onCreate() {
        if (acceptedAt == null) acceptedAt = LocalDateTime.now();
    }
}
