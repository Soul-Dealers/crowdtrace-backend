import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import type { CaseComment } from '@/components/ct/comment-thread';
import { timeAgo } from '@/lib/case-display';

type Comment = Schemas['CommentResponse'];

const key = (caseId: number) => ['case-comments', caseId];

/** A case's visible comments, newest first, one cursor page at a time. */
export function useCaseComments(caseId: number, size = 20) {
  return useInfiniteQuery({
    queryKey: key(caseId),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['CommentCursorResponse']>(
        await api.GET('/api/public/cases/{caseId}/comments', {
          params: { path: { caseId }, query: { cursor: pageParam, size } },
        }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });
}

export function usePostComment(caseId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: string) =>
      unwrap<Comment>(
        await api.POST('/api/user/cases/{caseId}/comments', { params: { path: { caseId } }, body: { body } }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: key(caseId) }),
  });
}

export function toThreadComment(c: Comment): CaseComment {
  return {
    id: String(c.id),
    author: c.author.displayName,
    avatarSeed: c.author.avatarSeed,
    badge: c.author.badgeType ?? undefined,
    ago: timeAgo(c.createdAt),
    body: c.body,
  };
}
