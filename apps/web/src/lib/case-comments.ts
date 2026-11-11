import { type InfiniteData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useSession } from '@/lib/auth';

export type Comment = Schemas['CommentResponse'];

/** Every level of a case's thread sits under this prefix, so one invalidation refreshes them all. */
const caseKey = (caseId: number) => ['case-comments', caseId];

/** One page at a time of a case's top-level comments, or of one comment's direct replies, newest first. */
export function useCaseComments(caseId: number, parentCommentId?: number, enabled = true, size = 20) {
  return useInfiniteQuery({
    queryKey: [...caseKey(caseId), parentCommentId ?? 'root', size],
    enabled,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['CommentCursorResponse']>(
        await api.GET('/api/public/cases/{caseId}/comments', {
          params: { path: { caseId }, query: { cursor: pageParam, size, parentCommentId } },
        }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });
}

export function usePostComment(caseId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { body: string; parentCommentId?: number }) =>
      unwrap<Comment>(
        await api.POST('/api/user/cases/{caseId}/comments', { params: { path: { caseId } }, body: vars }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: caseKey(caseId) }),
  });
}

export function useDeleteComment(caseId: number) {
  const qc = useQueryClient();
  return useMutation({
    // 204 has no envelope to unwrap.
    mutationFn: async (commentId: number) => {
      const res = await api.DELETE('/api/user/cases/{caseId}/comments/{commentId}', {
        params: { path: { caseId, commentId } },
      });
      if (res.error !== undefined || !res.response.ok) throw new ApiError(errorMessage(res.error));
    },
    onSuccess: (_, commentId) => {
      // Redact the cached copy at once, so the text is gone even if the refetch fails.
      qc.setQueriesData<InfiniteData<Schemas['CommentCursorResponse']>>({ queryKey: caseKey(caseId) }, (data) =>
        data && {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            content: page.content?.map((c) =>
              c.id === commentId ? { ...c, author: null, body: null, deleted: true } : c),
          })),
        });
      return qc.invalidateQueries({ queryKey: caseKey(caseId) });
    },
  });
}

/**
 * The signed-in user's avatar seed, used only to decide where Delete shows; the server checks ownership.
 * Shares the account page's ['me'] cache.
 */
export function useMyAvatarSeed(): string | undefined {
  const session = useSession();
  const me = useQuery({
    queryKey: ['me'],
    enabled: !!session,
    queryFn: async () => unwrap<Schemas['UserResponse']>(await api.GET('/api/v1/auth/me')),
  });
  return session ? me.data?.avatarSeed : undefined;
}
