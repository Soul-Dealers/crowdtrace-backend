package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "case_files")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaseFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter(AccessLevel.NONE)
    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private CaseFilePurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 32)
    private FileVisibility visibility;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String checksumSha256;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "attached_at")
    private LocalDateTime attachedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void attachTo(Long caseId, LocalDateTime at) {
        if (this.caseId != null) {
            throw new IllegalStateException("File is already attached to a case");
        }
        this.caseId = caseId;
        this.attachedAt = at;
    }

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }
}
