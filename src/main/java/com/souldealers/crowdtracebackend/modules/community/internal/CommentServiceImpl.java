package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.community.CommentResponse;
import com.souldealers.crowdtracebackend.modules.community.CommentService;
import com.souldealers.crowdtracebackend.modules.community.CreateCommentRequest;
import com.souldealers.crowdtracebackend.modules.community.internal.model.Comment;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CommentRepository;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CommentRepository.ReplyCount;
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
import java.util.stream.Collectors;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;
import static com.souldealers.crowdtracebackend.shared.CustomMessages.COMMENT_NOT_FOUND;

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
        // Shared lock first, then the parent row: a takedown or the parent's deletion either waits for this post or
        // has already happened and rejects it.
        if (!caseQueryService.lockPublicCase(caseId)) {
            throw new NotFoundException(CASE_NOT_FOUND);
        }
        Long parentId = request.parentCommentId();
        if (parentId != null) {
            comments.findVisibleForUpdate(parentId, caseId).filter(parent -> !parent.isDeleted())
                    .orElseThrow(() -> new NotFoundException(COMMENT_NOT_FOUND));
        }
        Comment saved = comments.save(Comment.post(caseId, parentId, authorId, request.body().strip(), now()));
        return toResponse(saved, userService.getPublicProfile(authorId), 0);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorResponse<CommentResponse> listPublic(Long caseId, Long parentId, String token, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (parentId != null && parentId <= 0) {
            throw new ValidationException("parentCommentId must be positive");
        }
        CommentCursor cursor = token == null ? null : CommentCursor.decode(token);
        requirePublicCase(caseId);
        if (parentId != null && comments.findVisible(parentId, caseId).isEmpty()) {
            throw new NotFoundException(COMMENT_NOT_FOUND);
        }
        Limit limit = Limit.of(size + 1);
        List<Comment> fetched = parentId == null
                ? cursor == null ? comments.findFirstRoots(caseId, limit)
                        : comments.findRootsAfter(caseId, cursor.createdAt(), cursor.id(), limit)
                : cursor == null ? comments.findFirstReplies(parentId, limit)
                        : comments.findRepliesAfter(parentId, cursor.createdAt(), cursor.id(), limit);
        boolean hasNext = fetched.size() > size;
        List<Comment> page = hasNext ? fetched.subList(0, size) : fetched;
        if (page.isEmpty()) {
            return new CursorResponse<>(List.of(), size, false, null);
        }
        Map<Long, Long> replyCounts = comments.countVisibleReplies(page.stream().map(Comment::getId).toList())
                .stream().collect(Collectors.toMap(ReplyCount::getParentId, ReplyCount::getReplies));
        // A deleted comment's author is never looked up, let alone returned.
        Map<Long, PublicUserResponse> authors = userService.getPublicProfiles(
                page.stream().filter(c -> !c.isDeleted()).map(Comment::getAuthorId).distinct().toList());
        List<CommentResponse> content = page.stream()
                .map(c -> toResponse(c, authors.get(c.getAuthorId()), replyCounts.getOrDefault(c.getId(), 0L)))
                .toList();
        String next = hasNext ? new CommentCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new CursorResponse<>(content, size, hasNext, next);
    }

    @Override
    @Transactional
    public void deleteByAuthor(Long caseId, Long commentId, Long authorId) {
        requirePublicCase(caseId);
        comments.findVisibleForUpdate(commentId, caseId)
                .filter(c -> c.getAuthorId() == authorId)
                .orElseThrow(() -> new NotFoundException(COMMENT_NOT_FOUND));
        comments.scrubByAuthor(commentId, now());
    }

    private void requirePublicCase(Long caseId) {
        if (!caseQueryService.isPublicCase(caseId)) {
            throw new NotFoundException(CASE_NOT_FOUND);
        }
    }

    /** Microseconds match Postgres precision, so a cursor built from a saved row stays valid. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static CommentResponse toResponse(Comment comment, PublicUserResponse author, long replyCount) {
        if (comment.isDeleted()) {
            return new CommentResponse(comment.getId(), comment.getParentCommentId(), null, null,
                    comment.getCreatedAt(), replyCount, true);
        }
        return new CommentResponse(comment.getId(), comment.getParentCommentId(), author, comment.getBody(),
                comment.getCreatedAt(), replyCount, false);
    }
}
