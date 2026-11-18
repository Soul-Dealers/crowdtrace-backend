package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.ModerationCaseRef;
import com.souldealers.crowdtracebackend.modules.community.CommentModerationService;
import com.souldealers.crowdtracebackend.modules.community.CommentModerationView;
import com.souldealers.crowdtracebackend.modules.governance.CommentModerationDetail;
import com.souldealers.crowdtracebackend.modules.governance.CommentModerationQueueItem;
import com.souldealers.crowdtracebackend.modules.governance.ContentReportView;
import com.souldealers.crowdtracebackend.modules.governance.ModerationService;
import com.souldealers.crowdtracebackend.modules.governance.CommentRemovedEvent;
import com.souldealers.crowdtracebackend.modules.governance.KeepCommentRequest;
import com.souldealers.crowdtracebackend.modules.governance.ModerationDecisionResponse;
import com.souldealers.crowdtracebackend.modules.governance.RemoveCommentRequest;
import com.souldealers.crowdtracebackend.modules.governance.ReportReason;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportResolution;
import com.souldealers.crowdtracebackend.modules.community.ModeratedComment;
import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.ContentReport;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.ContentReportRepository;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.ContentReportRepository.OpenReportGroup;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.ContentReportRepository.ReasonCount;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.COMMENT_NOT_FOUND;

@Service
@RequiredArgsConstructor
class ModerationServiceImpl implements ModerationService {
    static final int MAX_PAGE_SIZE = 50;
    private static final int EXCERPT_LENGTH = 200;

    private final ContentReportRepository reports;
    private final CommentModerationService comments;
    private final CaseQueryService cases;
    private final ActorLookup actorLookup;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @RequiresModerator
    @Transactional(readOnly = true)
    public PagedResponse<CommentModerationQueueItem> listQueue(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Order is fixed by the query; client sorting is ignored.
        Page<OpenReportGroup> groups = reports.findOpenGroups(
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<Long> ids = groups.stream().map(OpenReportGroup::getCommentId).toList();
        if (ids.isEmpty()) {
            return PagedResponse.from(new PageImpl<>(List.<CommentModerationQueueItem>of(), groups.getPageable(),
                    groups.getTotalElements()));
        }
        Map<Long, Map<ReportReason, Long>> reasons = new HashMap<>();
        for (ReasonCount row : reports.countOpenByReason(ids)) {
            reasons.computeIfAbsent(row.getCommentId(), id -> new EnumMap<>(ReportReason.class))
                    .put(row.getReason(), row.getReports());
        }
        Map<Long, CommentModerationView> views = comments.getModerationViews(ids);
        Map<Long, ModerationCaseRef> refs = cases.getModerationCaseRefs(
                views.values().stream().map(CommentModerationView::caseId).distinct().toList());
        List<CommentModerationQueueItem> items = groups.stream().filter(g -> views.containsKey(g.getCommentId()))
                .map(g -> toItem(g, views.get(g.getCommentId()), refs, reasons.getOrDefault(g.getCommentId(), Map.of())))
                .toList();
        return PagedResponse.from(new PageImpl<>(items, groups.getPageable(), groups.getTotalElements()));
    }

    @Override
    @RequiresModerator
    @Transactional(readOnly = true)
    public CommentModerationDetail getDetail(Long commentId) {
        CommentModerationView view = comments.getModerationViews(List.of(commentId)).get(commentId);
        if (view == null) {
            throw new NotFoundException(COMMENT_NOT_FOUND);
        }
        ModerationCaseRef ref = cases.getModerationCaseRefs(List.of(view.caseId())).get(view.caseId());
        List<ContentReportView> all = reports.findByCommentIdOrderByCreatedAtAscIdAsc(commentId).stream()
                .map(ModerationServiceImpl::toView).toList();
        return new CommentModerationDetail(view, ref == null ? null : ref.fullName(), ref != null && ref.publiclyVisible(),
                all);
    }

    @Override
    @RequiresModerator
    @Transactional
    @AuditedOperation(AuditAction.COMMENT_REMOVED)
    public ModerationDecisionResponse remove(String actorEmail, Long commentId, RemoveCommentRequest request) {
        ActorView actor = actorLookup.byEmail(actorEmail);
        ModeratedComment comment = lockForDecision(commentId, actor);
        if (comment.removed()) {
            throw new ConflictException("This comment was already removed");
        }
        List<ContentReport> open = openReports(commentId);
        LocalDateTime now = now();
        resolve(commentId, ReportResolution.REMOVED, actor, request.notes().strip(), now, open.size());
        comments.remove(commentId, now);
        auditRecorder.record(auditActor(actor), AuditAction.COMMENT_REMOVED, commentId,
                AuditMetadata.of(AuditAction.COMMENT_REMOVED).put("resolvedReports", open.size()).build());
        events.publishEvent(CommentRemovedEvent.of(comment.caseId(), commentId,
                open.stream().map(ContentReport::getReporterId).distinct().toList()));
        return new ModerationDecisionResponse(commentId, ReportResolution.REMOVED.name(), open.size());
    }

    @Override
    @RequiresModerator
    @Transactional
    @AuditedOperation(AuditAction.CONTENT_REPORT_DISMISSED)
    public ModerationDecisionResponse keep(String actorEmail, Long commentId, KeepCommentRequest request) {
        ActorView actor = actorLookup.byEmail(actorEmail);
        lockForDecision(commentId, actor);
        List<ContentReport> open = openReports(commentId);
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().strip();
        resolve(commentId, ReportResolution.DISMISSED, actor, notes, now(), open.size());
        comments.clearOpenReports(commentId);
        for (ContentReport report : open) {
            auditRecorder.record(auditActor(actor), AuditAction.CONTENT_REPORT_DISMISSED, report.getId(),
                    AuditMetadata.of(AuditAction.CONTENT_REPORT_DISMISSED).put("commentId", commentId).build());
        }
        return new ModerationDecisionResponse(commentId, ReportResolution.DISMISSED.name(), open.size());
    }

    /** Locks the comment, so a concurrent decision or report waits and then sees this one's outcome. */
    private ModeratedComment lockForDecision(long commentId, ActorView actor) {
        ModeratedComment comment = comments.lockForDecision(commentId);
        if (comment.authorId() == actor.userId()) {
            throw new AccessDeniedException("You cannot moderate your own comment");
        }
        return comment;
    }

    private List<ContentReport> openReports(long commentId) {
        List<ContentReport> open = reports.findOpenByCommentIdOrderById(commentId);
        if (open.isEmpty()) {
            throw new ConflictException("This comment has no open reports");
        }
        return open;
    }

    private void resolve(long commentId, ReportResolution resolution, ActorView actor, String notes,
                         LocalDateTime at, int expected) {
        int resolved = reports.resolveOpen(commentId, resolution, actor.userId(), notes, at);
        if (resolved != expected) {
            // The comment lock keeps reports from arriving mid-decision; a mismatch means that guarantee broke.
            throw new IllegalStateException("Resolved " + resolved + " reports but expected " + expected);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static AuditActor auditActor(ActorView actor) {
        return AuditActor.user(actor.userId(), auditRole(actor.role()));
    }

    /** Mirrors identity's AuditRoleMapper, which is internal to identity. */
    private static AuditActorRole auditRole(UserRoles role) {
        return switch (role) {
            case REGISTERED_USER -> AuditActorRole.REGISTERED_USER;
            case MODERATOR -> AuditActorRole.MODERATOR;
            case SUPER_ADMIN -> AuditActorRole.SUPER_ADMIN;
        };
    }

    private static CommentModerationQueueItem toItem(OpenReportGroup group, CommentModerationView view,
                                                     Map<Long, ModerationCaseRef> refs,
                                                     Map<ReportReason, Long> reasons) {
        ModerationCaseRef ref = refs.get(view.caseId());
        String excerpt = view.deleted() ? null
                : view.body().length() <= EXCERPT_LENGTH ? view.body() : view.body().substring(0, EXCERPT_LENGTH);
        return new CommentModerationQueueItem(view.id(), view.caseId(), ref == null ? null : ref.fullName(),
                ref != null && ref.publiclyVisible(), excerpt, view.author(), view.deleted(), group.getOpenReports(),
                reasons, group.getOldestOpenAt(), group.getNewestOpenAt());
    }

    private static ContentReportView toView(ContentReport r) {
        return new ContentReportView(r.getId(), r.getReporterId(), r.getReason(), r.getDetails(),
                r.getStatus().name(), r.getResolution() == null ? null : r.getResolution().name(),
                r.getModeratorId(), r.getResolutionNotes(), r.getCreatedAt(), r.getResolvedAt());
    }
}
