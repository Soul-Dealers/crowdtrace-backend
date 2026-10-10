package com.souldealers.crowdtracebackend.shared.audit;

import com.souldealers.crowdtracebackend.shared.audit.internal.AuditMetadataSerializer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated, immutable metadata for one audit action. */
public final class AuditMetadata {
    private final AuditAction action;
    private final Map<String, Object> values;

    private AuditMetadata(AuditAction action, Map<String, Object> values) {
        this.action = action;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Builder of(AuditAction action) {
        if (action == null) {
            throw new IllegalArgumentException("Audit metadata action is required");
        }
        return new Builder(action);
    }

    public static AuditMetadata none(AuditAction action) {
        return of(action).build();
    }

    public AuditAction action() {
        return action;
    }

    public Map<String, Object> values() {
        return values;
    }

    public static final class Builder {
        private final AuditAction action;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder(AuditAction action) {
            this.action = action;
        }

        public Builder put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, ?> values) {
            values.forEach(this::put);
            return this;
        }

        public AuditMetadata build() {
            validate();
            var copiedValues = new LinkedHashMap<String, Object>();
            values.forEach((key, value) -> copiedValues.put(key,
                    normalizeValue(key, action.schema().get(key), value)));
            AuditMetadataSerializer.validateSize(copiedValues);
            return new AuditMetadata(action, copiedValues);
        }

        private void validate() {
            for (String key : values.keySet()) {
                if (!action.schema().containsKey(key)) {
                    throw invalid(key);
                }
            }
            for (String key : action.requiredMetadataKeys()) {
                if (!values.containsKey(key)) {
                    throw invalid(key);
                }
            }
        }

        private static IllegalArgumentException invalid(String key) {
            return new IllegalArgumentException("Invalid audit metadata key: " + key);
        }
    }

    static Object normalizeValue(String key, AuditValueType type, Object value) {
        if (value == null) {
            throw Builder.invalid(key);
        }
        boolean valid = switch (type.kind()) {
            case ID -> value.getClass() == Long.class && (Long) value > 0;
            case COUNT -> value.getClass() == Integer.class && (Integer) value >= 0;
            case FLAG -> value.getClass() == Boolean.class;
            case CODE -> value instanceof Enum<?> enumValue && enumValue.getDeclaringClass() == type.enumClass();
        };
        if (!valid) {
            throw Builder.invalid(key);
        }
        return value instanceof Enum<?> enumValue ? enumValue.name() : value;
    }
}
