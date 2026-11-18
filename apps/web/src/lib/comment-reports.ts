import { useMutation } from '@tanstack/react-query';
import { api, ApiError, errorMessage, type Schemas } from '@/lib/api/client';

type Comment = Schemas['CommentResponse'];
type Notification = Schemas['InAppNotificationResponse'];
export type ReportReason = Schemas['ReportCommentRequest']['reason'];

export const REPORT_REASONS: { value: ReportReason; label: string }[] = [
  { value: 'SPAM', label: 'Spam' },
  { value: 'HARASSMENT', label: 'Harassment' },
  { value: 'MISINFORMATION', label: 'False information' },
  { value: 'PRIVACY', label: 'Shares private details' },
  { value: 'OTHER', label: 'Other' },
];
export const MAX_REPORT_DETAILS = 1000;

/** Removed wins over deleted: a removed comment's author is never shown. */
export function commentState(c: Comment): 'live' | 'removed' | 'deleted' {
  if (c.removed) return 'removed';
  if (c.deleted || !c.author) return 'deleted';
  return 'live';
}

export function placeholderText(state: 'removed' | 'deleted'): string {
  return state === 'removed' ? 'Removed by a moderator' : 'Comment has been deleted by author';
}

/** Only on someone else's visible comment; hidden until we know who "me" is. The server checks again. */
export function canReport(c: Comment, mySeed: string | undefined): boolean {
  return !!mySeed && commentState(c) === 'live' && c.author!.avatarSeed !== mySeed;
}

export function canSubmitReport(reason: ReportReason | undefined, details: string): boolean {
  if (!reason || details.length > MAX_REPORT_DETAILS) return false;
  return reason !== 'OTHER' || details.trim().length > 0;
}

export function reportOutcomeText(status: number): string {
  return status === 200
    ? "You've already reported this comment"
    : 'Thanks. A moderator will review this comment.';
}

export function reportErrorText(status: number, fallback: string): string {
  return status === 429 ? 'Too many reports, try again later' : fallback;
}

const STATUS: Record<string, string> = {
  MISSING: 'missing again',
  FOUND_SAFE: 'found safe',
  FOUND_DECEASED: 'found deceased',
  CLOSED: 'closed',
};

export function notificationText(n: Notification, caseName: string): string {
  switch (n.type) {
    case 'COMMENT_REPLY':
      return `New reply in your conversation on ${caseName}`;
    case 'NEW_COMMENT':
      return `New comment on your case: ${caseName}`;
    case 'COMMENT_REMOVED':
      return 'A comment you reported was removed';
    default:
      return `${caseName} was marked ${STATUS[n.caseStatus ?? ''] ?? 'updated'}`;
  }
}

export function notificationHref(n: Notification): string {
  return `/cases/${n.caseId}`;
}

/** Resolves to the text to show the reporter; rejects with a message ready to display. */
export function useReportComment(commentId: number) {
  return useMutation({
    mutationFn: async (vars: { reason: ReportReason; details: string }) => {
      const details = vars.details.trim();
      const res = await api.POST('/api/user/comments/{commentId}/reports', {
        params: { path: { commentId } },
        body: { reason: vars.reason, details: details || undefined },
      });
      if (res.error !== undefined || !res.response.ok) {
        throw new ApiError(reportErrorText(res.response.status, errorMessage(res.error)));
      }
      return reportOutcomeText(res.response.status);
    },
  });
}
