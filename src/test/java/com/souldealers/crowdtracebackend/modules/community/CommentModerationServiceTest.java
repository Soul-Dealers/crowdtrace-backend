package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentModerationServiceTest extends CasePostgresTestSupport {
    @Autowired private CommentModerationService moderation;
    @Autowired private CaseRecordRepository cases;
    @Autowired private EntityManager em;

    private long authorId;
    private long liveCase;

    @BeforeEach
    void setUp() {
        authorId = user("author-" + UUID.randomUUID() + "@example.com");
        liveCase = caseWith(ReviewStatus.APPROVED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mutationsRequireTransaction() {
        assertThatThrownBy(() -> moderation.lockReportable(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> moderation.lockForDecision(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> moderation.addOpenReport(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> moderation.remove(1L, at(12))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> moderation.clearOpenReports(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockReportableRejectsRemovedDeletedAndNonPublic() {
        long removed = comment(liveCase);
        jdbc.update("UPDATE comments SET moderation_status='REMOVED', removed_at=now() WHERE id=?", removed);
        long deleted = comment(liveCase);
        jdbc.update("UPDATE comments SET body='[deleted]', deleted_at=now() WHERE id=?", deleted);
        long onTakenDown = comment(caseWith(ReviewStatus.TAKEN_DOWN));
        for (long id : new long[]{removed, deleted, onTakenDown, 999_999_999L}) {
            assertThatThrownBy(() -> moderation.lockReportable(id)).isInstanceOf(NotFoundException.class);
        }
        long visible = comment(liveCase);
        assertThat(moderation.lockReportable(visible))
                .isEqualTo(new ModeratedComment(visible, liveCase, authorId, false, false));
    }

    @Test
    void lockForDecisionAcceptsRemovedComment() {
        long removed = comment(liveCase);
        jdbc.update("UPDATE comments SET moderation_status='REMOVED', removed_at=now() WHERE id=?", removed);
        assertThat(moderation.lockForDecision(removed).removed()).isTrue();
        assertThatThrownBy(() -> moderation.lockForDecision(999_999_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addOpenReportThenRemoveZeroesCount() {
        long id = comment(liveCase);
        moderation.addOpenReport(id);
        assertThat(jdbc.queryForObject("SELECT report_count FROM comments WHERE id=?", Integer.class, id)).isOne();
        moderation.remove(id, at(12));
        assertThat(jdbc.queryForMap("SELECT moderation_status, report_count, removed_at FROM comments WHERE id=?", id))
                .containsEntry("moderation_status", "REMOVED")
                .containsEntry("report_count", 0)
                .containsEntry("removed_at", java.sql.Timestamp.valueOf(at(12)));
        moderation.clearOpenReports(id);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void moderationViewsReturnBodyOfRemovedComment() {
        long id = comment(liveCase);
        jdbc.update("UPDATE comments SET moderation_status='REMOVED', removed_at=now() WHERE id=?", id);
        CommentModerationView view = moderation.getModerationViews(List.of(id)).get(id);
        assertThat(view.body()).isEqualTo("rude words");
        assertThat(view.removed()).isTrue();
        assertThat(view.authorId()).isEqualTo(authorId);
        assertThat(view.author()).isNotNull();
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void moderationViewsRefuseNonModerators() {
        assertThatThrownBy(() -> moderation.getModerationViews(List.of(1L)))
                .isInstanceOf(org.springframework.security.authorization.AuthorizationDeniedException.class);
    }

    private long comment(long caseId) {
        em.flush();
        return jdbc.queryForObject("INSERT INTO comments (case_id, author_id, body) VALUES (?, ?, 'rude words') "
                + "RETURNING id", Long.class, caseId, authorId);
    }

    private long caseWith(ReviewStatus reviewStatus) {
        boolean down = reviewStatus == ReviewStatus.TAKEN_DOWN;
        return cases.saveAndFlush(aCase(authorId).reviewStatus(reviewStatus).caseStatus(CaseStatus.MISSING)
                .approvedAt(at(9)).takedownReason(down ? "Requested" : null).takenDownBy(down ? authorId : null)
                .takenDownAt(down ? at(10) : null).submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build())
                .getId();
    }
}
