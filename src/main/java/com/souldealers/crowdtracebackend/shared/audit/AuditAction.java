package com.souldealers.crowdtracebackend.shared.audit;

import java.util.Map;
import java.util.Set;

/** Settled audit action vocabulary, target types, and metadata schemas. */
public enum AuditAction {
    VERIFICATION_APPROVED("VERIFICATION.APPROVED", AuditTargetType.VERIFICATION_REQUEST,
            required("userId", AuditValueType.ID,
                    "verificationType", AuditValueType.code(AuditVerificationType.class))),
    VERIFICATION_REJECTED("VERIFICATION.REJECTED", AuditTargetType.VERIFICATION_REQUEST,
            required("userId", AuditValueType.ID,
                    "verificationType", AuditValueType.code(AuditVerificationType.class))),
    VERIFICATION_REVOKED("VERIFICATION.REVOKED", AuditTargetType.VERIFICATION_REQUEST,
            required("userId", AuditValueType.ID,
                    "verificationType", AuditValueType.code(AuditVerificationType.class))),
    VERIFICATION_GRANTED("VERIFICATION.GRANTED", AuditTargetType.VERIFICATION_REQUEST,
            required("userId", AuditValueType.ID,
                    "verificationType", AuditValueType.code(AuditVerificationType.class))),
    CASE_APPROVED("CASE.APPROVED", AuditTargetType.CASE,
            required("reviewId", AuditValueType.ID)),
    CASE_REJECTED("CASE.REJECTED", AuditTargetType.CASE,
            required("reviewId", AuditValueType.ID)),
    CASE_STATUS_CHANGED("CASE.STATUS_CHANGED", AuditTargetType.CASE,
            required("fromStatus", AuditValueType.code(AuditCaseStatus.class),
                    "toStatus", AuditValueType.code(AuditCaseStatus.class))),
    CASE_CLOSING_STATEMENT_SET("CASE.CLOSING_STATEMENT_SET", AuditTargetType.CASE, empty()),
    CASE_TAKEN_DOWN("CASE.TAKEN_DOWN", AuditTargetType.CASE, empty()),
    CASE_PHOTO_ADDED("CASE.PHOTO_ADDED", AuditTargetType.CASE,
            required("fileId", AuditValueType.ID)),
    CASE_FILE_PRIVATE_ACCESS_GRANTED("CASE_FILE.PRIVATE_ACCESS_GRANTED", AuditTargetType.CASE_FILE,
            required("caseId", AuditValueType.ID)),
    COMMENT_REMOVED("COMMENT.REMOVED", AuditTargetType.COMMENT,
            required("resolvedReports", AuditValueType.COUNT)),
    CONTENT_REPORT_DISMISSED("CONTENT_REPORT.DISMISSED", AuditTargetType.CONTENT_REPORT,
            required("commentId", AuditValueType.ID)),
    USER_ROLE_CHANGED("USER.ROLE_CHANGED", AuditTargetType.USER,
            required("fromRole", AuditValueType.code(AuditActorRole.class),
                    "toRole", AuditValueType.code(AuditActorRole.class))),
    USER_DEACTIVATED("USER.DEACTIVATED", AuditTargetType.USER, empty()),
    AUDIT_EVENT_CORRECTED("AUDIT_EVENT.CORRECTED", AuditTargetType.AUDIT_EVENT,
            schema(Set.of("replacementEventId"), "reason", AuditValueType.code(AuditCorrectionReason.class),
                    "replacementEventId", AuditValueType.ID)),
    RETENTION_CASE_SENSITIVE_DATA_PURGED("RETENTION.CASE_SENSITIVE_DATA_PURGED", AuditTargetType.CASE,
            required("filesDeleted", AuditValueType.COUNT));

    private final String code;
    private final AuditTargetType targetType;
    private final Map<String, AuditValueType> schema;
    private final Set<String> requiredMetadataKeys;

    AuditAction(String code, AuditTargetType targetType, Schema schema) {
        this.code = code;
        this.targetType = targetType;
        this.schema = schema.fields();
        this.requiredMetadataKeys = schema.requiredKeys();
    }

    public String code() {
        return code;
    }

    public AuditTargetType targetType() {
        return targetType;
    }

    public Map<String, AuditValueType> schema() {
        return schema;
    }

    public Set<String> requiredMetadataKeys() {
        return requiredMetadataKeys;
    }

    private record Schema(Map<String, AuditValueType> fields, Set<String> requiredKeys) { }

    private static Schema empty() {
        return new Schema(Map.of(), Set.of());
    }

    private static Schema required(Object... keyTypePairs) {
        return schema(Set.of(), keyTypePairs);
    }

    private static Schema schema(Set<String> optionalKeys, Object... keyTypePairs) {
        var fields = new java.util.LinkedHashMap<String, AuditValueType>();
        for (int i = 0; i < keyTypePairs.length; i += 2) {
            fields.put((String) keyTypePairs[i], (AuditValueType) keyTypePairs[i + 1]);
        }
        var required = new java.util.LinkedHashSet<>(fields.keySet());
        required.removeAll(optionalKeys);
        return new Schema(Map.copyOf(fields), Set.copyOf(required));
    }
}
