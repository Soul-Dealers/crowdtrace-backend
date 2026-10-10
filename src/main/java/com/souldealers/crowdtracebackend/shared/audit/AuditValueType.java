package com.souldealers.crowdtracebackend.shared.audit;

/** The only metadata value shapes accepted by audit action schemas. */
public final class AuditValueType {
    public enum Kind {
        ID,
        COUNT,
        FLAG,
        CODE
    }

    public static final AuditValueType ID = new AuditValueType(Kind.ID, null);
    public static final AuditValueType COUNT = new AuditValueType(Kind.COUNT, null);
    public static final AuditValueType FLAG = new AuditValueType(Kind.FLAG, null);

    private final Kind kind;
    private final Class<? extends Enum<?>> enumClass;

    private AuditValueType(Kind kind, Class<? extends Enum<?>> enumClass) {
        this.kind = kind;
        this.enumClass = enumClass;
    }

    public static AuditValueType code(Class<? extends Enum<?>> enumClass) {
        if (enumClass == null || !enumClass.isEnum()) {
            throw new IllegalArgumentException("CODE requires an enum class");
        }
        return new AuditValueType(Kind.CODE, enumClass);
    }

    public Kind kind() {
        return kind;
    }

    public Class<? extends Enum<?>> enumClass() {
        return enumClass;
    }
}
