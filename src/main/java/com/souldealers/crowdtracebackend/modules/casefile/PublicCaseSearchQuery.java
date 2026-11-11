package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Validated filters for the public case listing. */
public record PublicCaseSearchQuery(String name, GhanaRegion region, Gender gender, Set<CaseStatus> statuses,
                                    Integer ageMin, Integer ageMax, LocalDate lastSeenFrom, LocalDate lastSeenTo,
                                    LocalDate approvedFrom, LocalDate approvedTo) {
    public static final int MIN_NAME = 3, MAX_NAME = 100, MIN_AGE = 0, MAX_AGE = 130;
    private static final String STATUS_MESSAGE = "status must be one of MISSING, FOUND_SAFE, FOUND_DECEASED, CLOSED";
    private static final String DATE_RANGE_MESSAGE = "dates must be between 0001-01-01 and 9999-12-31";

    public PublicCaseSearchQuery {
        if (name != null) {
            name = name.trim();
            if (name.length() < MIN_NAME || name.length() > MAX_NAME) {
                throw new ValidationException("name must be 3-100 characters");
            }
        }
        if (statuses == null || statuses.isEmpty()) {
            statuses = Collections.unmodifiableSet(EnumSet.allOf(CaseStatus.class));
        } else {
            for (CaseStatus status : statuses) {
                if (status == null) throw new ValidationException(STATUS_MESSAGE);
            }
            statuses = Collections.unmodifiableSet(EnumSet.copyOf(statuses));
        }
        validateAge(ageMin, "ageMin");
        validateAge(ageMax, "ageMax");
        if (ageMin != null && ageMax != null && ageMin > ageMax) {
            throw new ValidationException("ageMin must not exceed ageMax");
        }
        validateDate(lastSeenFrom);
        validateDate(lastSeenTo);
        validateDate(approvedFrom);
        validateDate(approvedTo);
        if (lastSeenFrom != null && lastSeenTo != null && lastSeenFrom.isAfter(lastSeenTo)) {
            throw new ValidationException("lastSeenFrom must not be after lastSeenTo");
        }
        if (approvedFrom != null && approvedTo != null && approvedFrom.isAfter(approvedTo)) {
            throw new ValidationException("approvedFrom must not be after approvedTo");
        }
    }

    public static PublicCaseSearchQuery all() {
        return new PublicCaseSearchQuery(null, null, null, null, null, null, null, null, null, null);
    }

    public String nameLikePattern() {
        if (name == null) return null;
        String escaped = name.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public LocalDateTime approvedFromInclusive() {
        return approvedFrom == null ? null : approvedFrom.atStartOfDay();
    }

    public LocalDateTime approvedToExclusive() {
        return approvedTo == null ? null : approvedTo.plusDays(1).atStartOfDay();
    }

    private static void validateAge(Integer age, String fieldName) {
        if (age != null && (age < MIN_AGE || age > MAX_AGE)) {
            throw new ValidationException(fieldName + " must be between 0 and 130");
        }
    }

    private static void validateDate(LocalDate date) {
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) {
            throw new ValidationException(DATE_RANGE_MESSAGE);
        }
    }
}
