package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.community.FollowService;
import com.souldealers.crowdtracebackend.modules.community.FollowedCaseResponse;
import com.souldealers.crowdtracebackend.modules.community.internal.model.CaseFollow;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CaseFollowRepository;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.SeekCursor;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
class FollowServiceImpl implements FollowService {
    static final int MAX_PAGE_SIZE = 50;

    private final CaseFollowRepository follows;
    private final CaseQueryService caseQueryService;
    private final Clock clock;

    @Override
    @Transactional
    public void follow(Long caseId, Long userId) {
        // The shared lock makes a concurrent takedown wait for this follow, or reject it if it came first.
        if (!caseQueryService.lockPublicCase(caseId)) {
            throw new NotFoundException(CASE_NOT_FOUND);
        }
        // Microseconds match Postgres precision, so a cursor built from this row stays valid.
        follows.insertIfAbsent(userId, caseId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(Long caseId, Long userId) {
        return follows.existsByUserIdAndCaseId(userId, caseId);
    }

    @Override
    @Transactional
    public void unfollow(Long caseId, Long userId) {
        follows.delete(userId, caseId);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorResponse<FollowedCaseResponse> listOwn(Long userId, String token, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        SeekCursor cursor = token == null ? null : SeekCursor.decode(token);
        Limit limit = Limit.of(size + 1);
        List<CaseFollow> fetched = cursor == null ? follows.findFirst(userId, limit)
                : follows.findAfter(userId, cursor.createdAt(), cursor.id(), limit);
        boolean hasNext = fetched.size() > size;
        List<CaseFollow> page = hasNext ? fetched.subList(0, size) : fetched;
        if (page.isEmpty()) {
            return new CursorResponse<>(List.of(), size, false, null);
        }
        Map<Long, PublicCaseSummaryResponse> visible = caseQueryService.getPublicCaseSummaries(
                page.stream().map(CaseFollow::getCaseId).toList());
        List<FollowedCaseResponse> content = page.stream().filter(f -> visible.containsKey(f.getCaseId()))
                .map(f -> new FollowedCaseResponse(visible.get(f.getCaseId()), f.getCreatedAt())).toList();
        // The cursor follows the last row read, not the last one shown, so hidden cases never end the list early.
        String next = hasNext ? new SeekCursor(page.getLast().getCreatedAt(), page.getLast().getCaseId()).encode() : null;
        return new CursorResponse<>(content, size, hasNext, next);
    }
}
