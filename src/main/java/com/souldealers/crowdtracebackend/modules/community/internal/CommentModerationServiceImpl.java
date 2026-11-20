package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.community.CommentModerationService;
import com.souldealers.crowdtracebackend.modules.community.CommentModerationView;
import com.souldealers.crowdtracebackend.modules.community.ModeratedComment;
import com.souldealers.crowdtracebackend.modules.community.internal.model.Comment;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CommentRepository;
import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.COMMENT_NOT_FOUND;

@Service
@RequiredArgsConstructor
class CommentModerationServiceImpl implements CommentModerationService {
    private final CommentRepository comments;
    private final CaseQueryService caseQueryService;
    private final UserService userService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ModeratedComment lockReportable(long commentId) {
        // Shared case lock first, then the comment, the same order as posting a reply.
        Long caseId = comments.findCaseIdById(commentId).orElseThrow(CommentModerationServiceImpl::notFound);
        if (!caseQueryService.lockPublicCase(caseId)) {
            throw notFound();
        }
        return toModerated(comments.findReportableForUpdate(commentId).orElseThrow(CommentModerationServiceImpl::notFound));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ModeratedComment lockForDecision(long commentId) {
        return toModerated(comments.findForUpdate(commentId).orElseThrow(CommentModerationServiceImpl::notFound));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addOpenReport(long commentId) {
        comments.incrementReportCount(commentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(long commentId, LocalDateTime at) {
        comments.markRemoved(commentId, at);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearOpenReports(long commentId) {
        comments.zeroReportCount(commentId);
    }

    @Override
    @RequiresModerator
    @Transactional(readOnly = true)
    public Map<Long, CommentModerationView> getModerationViews(Collection<Long> commentIds) {
        List<Comment> found = comments.findAllById(commentIds);
        Map<Long, PublicUserResponse> authors = userService.getPublicProfiles(
                found.stream().filter(c -> !c.isDeleted()).map(Comment::getAuthorId).distinct().toList());
        return found.stream().map(c -> new CommentModerationView(c.getId(), c.getCaseId(),
                        c.isDeleted() ? null : authors.get(c.getAuthorId()), c.getAuthorId(), c.getBody(),
                        c.isDeleted(), c.isRemoved(), c.getCreatedAt()))
                .collect(Collectors.toMap(CommentModerationView::id, Function.identity()));
    }

    private static ModeratedComment toModerated(Comment c) {
        return new ModeratedComment(c.getId(), c.getCaseId(), c.getAuthorId(), c.isRemoved(), c.isDeleted());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(COMMENT_NOT_FOUND);
    }
}
