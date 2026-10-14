package com.souldealers.crowdtracebackend.modules.casefile.internal.lifecycle;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.shared.ConflictException;

/** Rules for changing an approved case's public outcome and review visibility. */
public final class CaseLifecyclePolicy {
    private CaseLifecyclePolicy() { }

    public static void requireStatusChange(ReviewStatus review, CaseStatus current,
                                           CaseStatus target, boolean admin) {
        if (review != ReviewStatus.APPROVED || current == target || !isAllowed(current, target, admin)) {
            throw new ConflictException("Case status transition is not allowed");
        }
    }

    public static void requireTakedown(ReviewStatus review) {
        if (review != ReviewStatus.APPROVED) {
            throw new ConflictException("Only an approved case can be taken down");
        }
    }

    public static void requireRestore(ReviewStatus review) {
        if (review != ReviewStatus.TAKEN_DOWN) {
            throw new ConflictException("Only a taken-down case can be restored");
        }
    }

    private static boolean isAllowed(CaseStatus current, CaseStatus target, boolean admin) {
        if (admin) {
            return switch (current) {
                case MISSING -> switch (target) {
                    case MISSING -> false;
                    case FOUND_SAFE, FOUND_DECEASED, CLOSED -> true;
                };
                case FOUND_SAFE -> switch (target) {
                    case MISSING, FOUND_DECEASED, CLOSED -> true;
                    case FOUND_SAFE -> false;
                };
                case FOUND_DECEASED -> switch (target) {
                    case MISSING, FOUND_SAFE, CLOSED -> true;
                    case FOUND_DECEASED -> false;
                };
                case CLOSED -> switch (target) {
                    case MISSING, FOUND_SAFE, FOUND_DECEASED -> true;
                    case CLOSED -> false;
                };
            };
        }
        return switch (current) {
            case MISSING -> switch (target) {
                case FOUND_SAFE, FOUND_DECEASED, CLOSED -> true;
                case MISSING -> false;
            };
            case FOUND_SAFE, FOUND_DECEASED, CLOSED -> false;
        };
    }
}
