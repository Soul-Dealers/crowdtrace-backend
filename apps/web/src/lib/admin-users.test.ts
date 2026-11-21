import { describe, expect, it } from 'vitest';
import { availableActions, type AdminUser } from './admin-users';

const user = (over: Partial<AdminUser>): AdminUser => ({
  id: 7,
  displayName: 'Hettie Fritsch',
  email: 'hettie@example.com',
  role: 'REGISTERED_USER',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00',
  ...over,
});

describe('availableActions', () => {
  it('offers a role change and deactivation on an active account', () => {
    expect(availableActions(user({}), 'me@example.com')).toEqual(['promote', 'deactivate']);
    expect(availableActions(user({ role: 'MODERATOR' }), 'me@example.com')).toEqual(['demote', 'deactivate']);
  });

  it.each(['PENDING_VERIFICATION', 'SUSPENDED', 'INACTIVE'] as const)(
    'still lets a Super Admin deactivate a %s account, without a role change',
    (status) => {
      expect(availableActions(user({ status }), 'me@example.com')).toEqual(['deactivate']);
    },
  );

  it('offers nothing on a deactivated account, a Super Admin, or your own row', () => {
    expect(availableActions(user({ status: 'DEACTIVATED' }), 'me@example.com')).toEqual([]);
    expect(availableActions(user({ role: 'SUPER_ADMIN' }), 'me@example.com')).toEqual([]);
    expect(availableActions(user({}), 'hettie@example.com')).toEqual([]);
  });
});
