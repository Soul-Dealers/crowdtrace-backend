import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useSession } from '@/lib/auth';

export type Notification = Schemas['InAppNotificationResponse'];

const KEY = ['notifications'];

/** Unread count for the bell; polled so new notifications appear without a reload. */
export function useUnreadCount() {
  const session = useSession();
  const query = useQuery({
    queryKey: [...KEY, 'unread'],
    enabled: !!session,
    refetchInterval: 30_000,
    queryFn: async () =>
      unwrap<Schemas['UnreadCountResponse']>(await api.GET('/api/user/notifications/unread-count')).unreadCount,
  });
  return session ? (query.data ?? 0) : 0;
}

/** The signed-in user's notifications, newest first, a page at a time. */
export function useNotifications(size = 20) {
  return useInfiniteQuery({
    queryKey: [...KEY, 'list', size],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['InAppNotificationCursorResponse']>(
        await api.GET('/api/user/notifications', { params: { query: { cursor: pageParam, size } } }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });
}

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    // 204 has no envelope to unwrap.
    mutationFn: async (notificationId: number) => {
      const res = await api.POST('/api/user/notifications/{notificationId}/read', {
        params: { path: { notificationId } },
      });
      if (res.error !== undefined || !res.response.ok) throw new ApiError(errorMessage(res.error));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}
