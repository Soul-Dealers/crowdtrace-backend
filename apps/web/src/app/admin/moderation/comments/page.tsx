'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { FormMessage } from '@/components/app/form';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { PageHeader } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';
import { Pill } from '@/components/ct/pill';
import { api, ApiError, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useMyAvatarSeed } from '@/lib/case-comments';
import { REPORT_REASONS } from '@/lib/comment-reports';
import { formatDate } from '@/lib/enums';
import { clampPage } from '@/lib/paging';

type Item = Schemas['CommentModerationQueueItem'];
type Decision = 'remove' | 'keep';

const KEY = ['comment-moderation'];
const CONFLICT = 'Already handled by another moderator';
const reasonLabel = (value: string) =>
  REPORT_REASONS.find((r) => r.value === value)?.label ?? value;

function useDecision(commentId: number, onConflict: (message: string) => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ decision, notes }: { decision: Decision; notes: string }) => {
      const params = { path: { commentId } };
      const res =
        decision === 'remove'
          ? await api.POST('/api/v1/admin/moderation/comments/{commentId}/remove', {
              params,
              body: { notes },
            })
          : await api.POST('/api/v1/admin/moderation/comments/{commentId}/keep', {
              params,
              body: { notes: notes.trim() || undefined },
            });
      if (res.error !== undefined || !res.response.ok) {
        throw new ApiError(res.response.status === 409 ? CONFLICT : errorMessage(res.error));
      }
    },
    // The refetch drops a row another moderator already decided, so its message is shown by the queue.
    onError: (error) => error.message === CONFLICT && onConflict(CONFLICT),
    onSettled: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

function Detail({
  item,
  onConflict,
}: {
  item: Item;
  onConflict: (message: string) => void;
}) {
  const commentId = item.commentId!;
  const mySeed = useMyAvatarSeed();
  const decide = useDecision(commentId, onConflict);
  const [notes, setNotes] = useState('');
  const detail = useQuery({
    queryKey: [...KEY, 'detail', commentId],
    queryFn: async () =>
      unwrap<Schemas['CommentModerationDetail']>(
        await api.GET('/api/v1/admin/moderation/comments/{commentId}', {
          params: { path: { commentId } },
        }),
      ),
  });
  if (detail.isError) return <FormMessage error={errorMessage(detail.error)} />;
  if (!detail.data) return <div className="h-24 animate-pulse rounded-xl bg-muted" aria-busy />;
  const { comment, reports = [] } = detail.data;
  const own = !!mySeed && comment?.author?.avatarSeed === mySeed;
  const notesId = `notes-${commentId}`;

  return (
    <div className="space-y-4 border-t border-border pt-4">
      {/* A new tab keeps the moderator's place in the queue; a case that isn't public has no thread to show. */}
      {item.casePublic && (
        <a
          href={`/cases/${item.caseId}?tab=discussion`}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-sm text-brand hover:underline"
        >
          See it in the discussion <ExternalLink className="size-3.5" aria-hidden />
        </a>
      )}
      <p className="whitespace-pre-line break-words text-sm">
        {comment?.deleted ? (
          <i className="text-muted-foreground">Deleted by author</i>
        ) : (
          comment?.body
        )}
      </p>
      <ol className="space-y-2 text-sm">
        {reports.map((r) => (
          <li key={r.id} className="rounded-lg bg-secondary/40 px-3 py-2">
            <span className="font-medium">{reasonLabel(r.reason!)}</span>
            <span className="text-text-subtle"> · {formatDate(r.createdAt)}</span>
            {r.status !== 'OPEN' && (
              <span className="text-text-subtle">
                {' '}
                · {r.resolution === 'REMOVED' ? 'Removed' : 'Kept'}
              </span>
            )}
            {r.details && <p className="text-text-secondary">{r.details}</p>}
            {r.resolutionNotes && (
              <p className="text-text-subtle">Moderator: {r.resolutionNotes}</p>
            )}
          </li>
        ))}
      </ol>
      {own ? (
        <p className="text-sm text-muted-foreground">
          You wrote this comment, so another moderator must decide it.
        </p>
      ) : (
        <form className="space-y-2" onSubmit={(e) => e.preventDefault()}>
          <label htmlFor={notesId} className="text-sm text-muted-foreground">
            Notes
          </label>
          <textarea
            id={notesId}
            rows={2}
            maxLength={2000}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Required to remove. Optional to keep."
            className="w-full resize-none rounded-xl border border-border bg-black p-2 text-sm"
          />
          {decide.isError && <FormMessage error={errorMessage(decide.error)} />}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              disabled={decide.isPending}
              onClick={() => decide.mutate({ decision: 'keep', notes })}
              className="rounded-lg border border-border px-3 py-1.5 text-sm disabled:opacity-50"
            >
              Keep comment
            </button>
            <button
              type="button"
              disabled={!notes.trim() || decide.isPending}
              onClick={() => decide.mutate({ decision: 'remove', notes: notes.trim() })}
              className="rounded-lg bg-destructive px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
            >
              Remove comment
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function Row({ item, onConflict }: { item: Item; onConflict: (message: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="space-y-3 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <p className="break-words text-sm">
            {item.authorDeleted ? (
              <i className="text-muted-foreground">Deleted by author</i>
            ) : (
              item.bodyExcerpt
            )}
          </p>
          <p className="text-xs text-text-subtle">
            {item.author?.displayName ?? 'Unknown author'} on{' '}
            <Link className="hover:text-brand" href={`/admin/cases/${item.caseId}`}>
              {item.caseName}
            </Link>
            {!item.casePublic && ' (not public)'} · first reported {formatDate(item.oldestOpenAt)}
          </p>
          <span className="flex flex-wrap gap-1">
            <Pill tone="danger">{item.openReports} open</Pill>
            {Object.entries(item.reasons ?? {}).map(([reason, count]) => (
              <Pill key={reason}>
                {reasonLabel(reason)} × {count}
              </Pill>
            ))}
          </span>
        </div>
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          {open ? 'Hide' : 'Review'}
        </button>
      </div>
      {open && <Detail item={item} onConflict={onConflict} />}
    </li>
  );
}

function Queue() {
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: [...KEY, 'queue', page],
    queryFn: async () =>
      unwrap<Schemas['CommentModerationQueuePageResponse']>(
        await api.GET('/api/v1/admin/moderation/comments', {
          params: { query: { page, size: 20 } },
        }),
      ),
  });
  const rows = query.data?.content ?? [];
  const [notice, setNotice] = useState<string>();
  const totalPages = query.data?.totalPages;
  // Deciding the last row on the last page would otherwise strand the moderator on an empty page.
  useEffect(() => {
    if (clampPage(page, totalPages) !== page) setPage(clampPage(page, totalPages));
  }, [page, totalPages]);
  return (
    <div className="space-y-4">
      {notice && <FormMessage error={notice} />}
      {query.isError && <FormMessage error={(query.error as Error).message} />}
      {query.isPending && <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy />}
      {query.isSuccess && rows.length === 0 && (
        <p className="py-10 text-center text-muted-foreground">No reported comments.</p>
      )}
      {rows.length > 0 && (
        <Panel>
          <ol className="divide-y divide-border">
            {rows.map((item) => (
              <Row key={item.commentId} item={item} onConflict={setNotice} />
            ))}
          </ol>
        </Panel>
      )}
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />
    </div>
  );
}

export default function CommentModerationPage() {
  return (
    <div className="space-y-5">
      <PageHeader
        title="Reported comments"
        description="Comments with open reports, oldest report first. Removing one tells its reporters."
      />
      <RequireAuth admin>
        <Queue />
      </RequireAuth>
    </div>
  );
}
