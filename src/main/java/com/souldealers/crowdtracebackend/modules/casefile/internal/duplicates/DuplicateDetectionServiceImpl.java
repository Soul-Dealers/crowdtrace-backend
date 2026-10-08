package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseDuplicateMatch;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseDuplicateCandidate;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseDuplicateMatchRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {
    private final CaseRecordRepository caseRepository;
    private final CaseDuplicateMatchRepository matchRepository;
    private final DuplicateMatcher matcher;
    private final DuplicateProperties properties;

    @Override
    public void detect(Long caseId) {
        CaseRecord submitted = caseRepository.findByIdForUpdate(caseId).orElse(null);
        if (submitted == null || submitted.getLastSeenDate() == null) return;
        LocalDate date = submitted.getLastSeenDate();
        int window = properties.getDateWindowDays();
        boolean found = false;
        for (CaseDuplicateCandidate candidate : caseRepository.findDuplicateCandidates(
                caseId, date.minusDays(window), date.plusDays(window))) {
            if (matchRepository.existsByCaseIdAndMatchedCaseId(caseId, candidate.getId())) continue;
            Optional<DuplicateMatch> result = matcher.match(submitted.getFullName(), date,
                    candidate.getFullName(), candidate.getLastSeenDate());
            if (result.isEmpty()) continue;
            DuplicateMatch match = result.get();
            matchRepository.save(CaseDuplicateMatch.builder().caseId(caseId).matchedCaseId(candidate.getId())
                    .confidence(match.confidence()).nameSimilarity(match.nameSimilarity())
                    .dayDifference(match.dayDifference()).algorithmVersion(match.algorithmVersion())
                    .reasons(match.reasons().stream().map(Enum::name).collect(Collectors.joining(","))).build());
            found = true;
        }
        if (found) submitted.setDuplicateFlag(true);
    }
}
