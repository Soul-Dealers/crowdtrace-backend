import { describe, expect, it } from 'vitest';
import type { Session } from './auth';
import { refreshedSession } from './session-refresh';

const started: Session = { token: 't1', displayName: 'Hettie', email: 'h@example.com', role: 'REGISTERED_USER' };

describe('refreshedSession', () => {
  it('saves a role changed on the server', () => {
    expect(refreshedSession(started, started, { role: 'MODERATOR', displayName: 'Hettie' })).toEqual({
      ...started,
      role: 'MODERATOR',
    });
  });

  it('saves nothing when nothing changed', () => {
    expect(refreshedSession(started, started, { role: 'REGISTERED_USER', displayName: 'Hettie' })).toBeNull();
  });

  it('drops a late answer when another tab saved a newer session while it was in flight', () => {
    const newer: Session = { ...started, role: 'MODERATOR' };
    expect(refreshedSession(started, newer, { role: 'REGISTERED_USER', displayName: 'Hettie' })).toBeNull();
  });

  it('drops the answer after a logout', () => {
    expect(refreshedSession(started, null, { role: 'MODERATOR', displayName: 'Hettie' })).toBeNull();
  });
});
