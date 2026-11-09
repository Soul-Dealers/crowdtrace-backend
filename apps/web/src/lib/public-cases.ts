import { useInfiniteQuery } from '@tanstack/react-query';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import type { CaseTileData } from '@/components/ct/case-tile';
import { CASE_STATUSES, REGIONS } from '@/lib/enums';

export type PublicCaseFilters = {
  name?: string;
  status?: (typeof CASE_STATUSES)[number];
  region?: (typeof REGIONS)[number];
  ageMin?: number;
  ageMax?: number;
};

/** Cursor-paged public search; pages are cached per filter set. */
export function usePublicCases(filters: PublicCaseFilters, size = 20) {
  return useInfiniteQuery({
    queryKey: ['public-cases', filters, size],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['PublicCaseCursorResponse']>(
        await api.GET('/api/public/cases', {
          params: {
            query: {
              name: filters.name || undefined,
              region: filters.region,
              status: filters.status ? [filters.status] : undefined,
              ageMin: filters.ageMin,
              ageMax: filters.ageMax,
              cursor: pageParam,
              size,
            },
          },
        }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });
}

export function toTile(summary: Schemas['PublicCaseSummaryResponse']): CaseTileData {
  return {
    id: summary.id!,
    fullName: summary.fullName!,
    age: summary.age!,
    region: summary.region!,
    lastSeenDate: summary.lastSeenDate!,
    caseStatus: summary.caseStatus!,
    thumbnailPhotoId: summary.thumbnailPhotoId,
  };
}
