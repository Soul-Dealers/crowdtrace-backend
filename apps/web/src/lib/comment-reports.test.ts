import { describe, expect, it } from 'vitest';
import {
  canReport,
  canSubmitReport,
  commentState,
  notificationHref,
  notificationText,
  placeholderText,
  reportErrorText,
  reportOutcomeText,
} from './comment-reports';

const author = {
  displayName: 'Kwame_Tips',
  avatarSeed: 'seed-author',
  verified: false,
  badgeType: null,
};
const live = {
  id: 1,
  parentCommentId: null,
  author,
  body: 'hi',
  createdAt: '2026-11-18T10:00:00',
  replyCount: 0,
  deleted: false,
  removed: false,
};

describe('comment state', () => {
  it('shows a removed comment as removed by a moderator, with no author', () => {
    const removed = { ...live, author: null, body: null, removed: true };
    expect(commentState(removed)).toBe('removed');
    expect(placeholderText('removed')).toBe('Removed by a moderator');
  });

  it('lets removed win over deleted', () => {
    expect(commentState({ ...live, author: null, body: null, removed: true, deleted: true })).toBe(
      'removed',
    );
    expect(commentState({ ...live, author: null, body: null, deleted: true })).toBe('deleted');
    expect(commentState(live)).toBe('live');
  });
});

describe('report action', () => {
  it('shows only on visible comments written by someone else', () => {
    expect(canReport(live, 'seed-me')).toBe(true);
    expect(canReport(live, 'seed-author')).toBe(false);
    expect(canReport({ ...live, author: null, body: null, deleted: true }, 'seed-me')).toBe(false);
    expect(canReport({ ...live, author: null, body: null, removed: true }, 'seed-me')).toBe(false);
    expect(canReport(live, undefined)).toBe(false);
  });

  it('keeps Submit disabled for Other without details', () => {
    expect(canSubmitReport(undefined, '')).toBe(false);
    expect(canSubmitReport('OTHER', '   ')).toBe(false);
    expect(canSubmitReport('OTHER', 'Shares an address')).toBe(true);
    expect(canSubmitReport('SPAM', '')).toBe(true);
    expect(canSubmitReport('SPAM', 'x'.repeat(1001))).toBe(false);
  });

  it('tells the reporter what happened', () => {
    expect(reportOutcomeText(201)).toBe('Thanks. A moderator will review this comment.');
    expect(reportOutcomeText(200)).toBe("You've already reported this comment");
    expect(reportErrorText(429, 'Too Many Requests')).toBe('Too many reports, try again later');
    expect(reportErrorText(404, 'Comment not found')).toBe('Comment not found');
  });
});

describe('notifications', () => {
  it('describes a removal and links to the case', () => {
    const n = {
      id: 9,
      type: 'COMMENT_REMOVED' as const,
      caseId: 42,
      commentId: null,
      caseStatus: null,
      read: false,
      createdAt: '2026-11-18T10:00:00',
    };
    expect(notificationText(n, 'Kofi Mensah')).toBe('A comment you reported was removed');
    expect(notificationHref(n)).toBe('/cases/42');
  });

  it('keeps the existing texts', () => {
    const base = {
      id: 9,
      caseId: 42,
      commentId: 3,
      caseStatus: null,
      read: false,
      createdAt: '2026-11-18T10:00:00',
    };
    expect(notificationText({ ...base, type: 'COMMENT_REPLY' }, 'Kofi')).toBe(
      'New reply in your conversation on Kofi',
    );
    expect(notificationText({ ...base, type: 'NEW_COMMENT' }, 'Kofi')).toBe(
      'New comment on your case: Kofi',
    );
    expect(
      notificationText(
        { ...base, commentId: null, type: 'CASE_STATUS_CHANGED', caseStatus: 'FOUND_SAFE' },
        'Kofi',
      ),
    ).toBe('Kofi was marked found safe');
  });
});
