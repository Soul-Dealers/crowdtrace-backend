package com.souldealers.crowdtracebackend.modules.discovery;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import com.souldealers.crowdtracebackend.shared.ValidationException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/** HTTP search filters; translated into casefile's own query so casefile never depends on discovery. */
public record PublicCaseFilter(String name, GhanaRegion region, Gender gender, List<CaseStatus> status,
        Integer ageMin, Integer ageMax, LocalDate lastSeenFrom, LocalDate lastSeenTo,
        LocalDate approvedFrom, LocalDate approvedTo) {

    public PublicCaseSearchQuery toQuery() {
        if (status != null && status.isEmpty()) {
            throw new ValidationException("status must contain at least one case status");
        }
        return new PublicCaseSearchQuery(name, region, gender,
                status == null ? null : new HashSet<>(status), ageMin, ageMax, lastSeenFrom, lastSeenTo,
                approvedFrom, approvedTo);
    }
}
