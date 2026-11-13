import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useSession } from '@/lib/auth';

const followKey = (caseId: number) => ['case-follow', caseId];

/** Whether the signed-in user follows the case; undefined for guests or while loading. */
export function useFollowState(caseId: number) {
  const session = useSession();
  const state = useQuery({
    queryKey: followKey(caseId),
    enabled: !!session,
    queryFn: async () =>
      unwrap<Schemas['FollowStateResponse']>(
        await api.GET('/api/user/cases/{caseId}/follow', { params: { path: { caseId } } }),
      ).following,
  });
  return session ? state.data : undefined;
}

/** Follows or unfollows; both are idempotent on the server, so a double click is harmless. */
export function useToggleFollow(caseId: number) {
  const qc = useQueryClient();
  return useMutation({
    // 204 has no envelope to unwrap.
    mutationFn: async (follow: boolean) => {
      const options = { params: { path: { caseId } } };
      const res = follow
        ? await api.POST('/api/user/cases/{caseId}/follow', options)
        : await api.DELETE('/api/user/cases/{caseId}/follow', options);
      if (res.error !== undefined || !res.response.ok) throw new ApiError(errorMessage(res.error));
      return follow;
    },
    onSuccess: (following) => {
      qc.setQueryData(followKey(caseId), following);
      return qc.invalidateQueries({ queryKey: ['follows'] });
    },
  });
}
