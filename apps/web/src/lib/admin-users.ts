import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, errorMessage, unwrap, type Schemas } from '@/lib/api/client';

export type AdminUser = Schemas['AdminUserResponse'];
export type UserFilters = { role?: AdminUser['role']; status?: AdminUser['status']; q?: string };
export type UserAction = 'promote' | 'demote' | 'deactivate';

const KEY = ['admin-users'];
export const CONFLICT = 'Already changed by another admin';

/** Super Admin user list, newest first, one cursor page at a time. */
export function useAdminUsers(filters: UserFilters, size = 20) {
  return useInfiniteQuery({
    queryKey: [...KEY, filters, size],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['AdminUserCursorResponse']>(
        await api.GET('/api/v1/admin/users', {
          params: { query: { ...filters, q: filters.q || undefined, cursor: pageParam, size } },
        }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });
}

/** The actions a Super Admin may take on a row; none on their own row, a Super Admin, or an inactive account. */
export function availableActions(user: AdminUser, myEmail: string | undefined): UserAction[] {
  if (user.email === myEmail || user.role === 'SUPER_ADMIN' || user.status !== 'ACTIVE') return [];
  return [user.role === 'MODERATOR' ? 'demote' : 'promote', 'deactivate'];
}

export const ACTION_LABEL: Record<UserAction, string> = {
  promote: 'Make moderator',
  demote: 'Remove moderator',
  deactivate: 'Deactivate',
};

export function confirmText(action: UserAction, name: string): string {
  switch (action) {
    case 'promote':
      return `Make ${name} a moderator? They can review cases and reports.`;
    case 'demote':
      return `Remove ${name} as a moderator? They lose moderation access immediately.`;
    case 'deactivate':
      return `Deactivate ${name}? They lose access immediately.`;
  }
}

/** Rejects with a message ready to show; a 409 means another admin got there first. */
export function useUserAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ action, id }: { action: UserAction; id: number }) => {
      const params = { path: { id } };
      const res =
        action === 'deactivate'
          ? await api.POST('/api/v1/admin/users/{id}/deactivate', { params })
          : await api.POST('/api/v1/admin/users/{id}/role', {
              params,
              body: { role: action === 'promote' ? 'MODERATOR' : 'REGISTERED_USER' },
            });
      if (res.error !== undefined || !res.response.ok) {
        throw new ApiError(res.response.status === 409 ? CONFLICT : errorMessage(res.error));
      }
    },
    onSettled: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}
