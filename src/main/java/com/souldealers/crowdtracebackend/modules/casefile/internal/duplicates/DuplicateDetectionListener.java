package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetectionListener {
    private final DuplicateDetectionService detector;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void submitted(CaseSubmittedEvent event) {
        try {
            detector.detect(event.caseId());
        } catch (RuntimeException exception) {
            log.warn("Duplicate detection failed for case {} ({})", event.caseId(),
                    exception.getClass().getSimpleName());
        }
    }
}
