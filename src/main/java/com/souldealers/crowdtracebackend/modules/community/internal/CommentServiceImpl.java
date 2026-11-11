package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.community.CommentResponse;
import com.souldealers.crowdtracebackend.modules.community.CommentService;
import com.souldealers.crowdtracebackend.modules.community.CreateCommentRequest;
import com.souldealers.crowdtracebackend.modules.community.internal.model.Comment;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CommentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;
import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
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
class CommentServiceImpl implements CommentService {
    static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository comments;
    private final CaseQueryService caseQueryService;
    private final UserService userService;
    private final Clock clock;

    @Override
    @Transactional
    public CommentResponse create(Long caseId, Long authorId, CreateCommentRequest request) {
        requirePublicCase(caseId);
        // Microseconds match Postgres precision, so the cursor built from this row stays valid.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Comment saved = comments.save(Comment.post(caseId, authorId, request.body().strip(), now));
        return toResponse(saved, userService.getPublicProfile(authorId));
    }

    @Override
    @Transactional(readOnly = true)
    public CursorResponse<CommentResponse> listPublic(Long caseId, String token, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        CommentCursor cursor = token == null ? null : CommentCursor.decode(token);
        requirePublicCase(caseId);
        Limit limit = Limit.of(size + 1);
        List<Comment> fetched = cursor == null
                ? comments.findFirstVisible(caseId, limit)
                : comments.findVisibleAfter(caseId, cursor.createdAt(), cursor.id(), limit);
        boolean hasNext = fetched.size() > size;
        List<Comment> page = hasNext ? fetched.subList(0, size) : fetched;
        Map<Long, PublicUserResponse> authors = userService.getPublicProfiles(
                page.stream().map(Comment::getAuthorId).toList());
        List<CommentResponse> content = page.stream()
                .map(c -> toResponse(c, authors.get(c.getAuthorId()))).toList();
        String next = hasNext ? new CommentCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new CursorResponse<>(content, size, hasNext, next);
    }

    private void requirePublicCase(Long caseId) {
        if (!caseQueryService.isPublicCase(caseId)) {
            throw new NotFoundException(CASE_NOT_FOUND);
        }
    }

    private static CommentResponse toResponse(Comment comment, PublicUserResponse author) {
        return new CommentResponse(comment.getId(), author, comment.getBody(), comment.getCreatedAt());
    }
}
