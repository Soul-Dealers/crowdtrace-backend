'use client';

import { useState } from 'react';
import { errorMessage } from '@/lib/api/client';
import { useSession } from '@/lib/auth';
import {
  useCaseComments,
  useDeleteComment,
  usePostComment,
  type Comment,
} from '@/lib/case-comments';
import { timeAgo } from '@/lib/case-display';
import { cn } from '@/lib/utils';
import { CommentHeader } from './comment-thread';
import { UserAvatar } from './user-avatar';

const action = 'text-sm text-muted-foreground hover:text-foreground disabled:opacity-50';
/** Past this depth replies stop indenting, so deep threads stay readable on a phone. */
const MAX_INDENT = 2;

/** Posts a top-level comment, or a reply when parentCommentId is set. */
export function CommentComposer({
  caseId,
  parentCommentId,
  label,
  placeholder,
  autoFocus,
  onPosted,
  onCancel,
}: {
  caseId: number;
  parentCommentId?: number;
  label: string;
  placeholder: string;
  autoFocus?: boolean;
  onPosted?: () => void;
  onCancel?: () => void;
}) {
  const post = usePostComment(caseId);
  const [draft, setDraft] = useState('');
  const body = draft.trim();
  const id = `composer-${parentCommentId ?? 'root'}`;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!body || post.isPending) return;
    // The textarea is read-only while posting, so nothing typed meanwhile can be lost.
    post.mutate(
      { body, parentCommentId },
      {
        onSuccess: () => {
          setDraft('');
          onPosted?.();
        },
      },
    );
  }

  return (
    <form onSubmit={submit} className="space-y-2">
      <label htmlFor={id} className="text-sm text-muted-foreground">
        {label}
      </label>
      <textarea
        id={id}
        rows={parentCommentId ? 2 : 3}
        maxLength={2000}
        value={draft}
        autoFocus={autoFocus}
        readOnly={post.isPending}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={placeholder}
        className="w-full resize-none rounded-xl border border-border bg-black p-3 text-sm placeholder:text-text-subtle read-only:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/25"
      />
      {post.isError && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(post.error)}
        </p>
      )}
      <div className="flex items-center justify-end gap-3">
        {onCancel && (
          <button type="button" onClick={onCancel} className={action}>
            Cancel
          </button>
        )}
        <button
          type="submit"
          disabled={!body || post.isPending}
          className="rounded-lg bg-white px-3 py-1.5 text-sm font-semibold text-black disabled:opacity-50"
        >
          {post.isPending ? 'Posting…' : parentCommentId ? 'Reply' : 'Post'}
        </button>
      </div>
    </form>
  );
}

function DeleteControl({ caseId, commentId }: { caseId: number; commentId: number }) {
  const remove = useDeleteComment(caseId);
  const [confirming, setConfirming] = useState(false);
  if (!confirming)
    return (
      <button type="button" className={action} onClick={() => setConfirming(true)}>
        Delete
      </button>
    );
  return (
    <span
      role="group"
      aria-label="Confirm delete"
      className="flex flex-wrap items-center gap-3 text-sm"
    >
      <span>Delete this comment? Replies will stay visible.</span>
      <button
        type="button"
        disabled={remove.isPending}
        onClick={() => remove.mutate(commentId)}
        className="font-medium text-destructive hover:underline disabled:opacity-50"
      >
        {remove.isPending ? 'Deleting…' : 'Delete'}
      </button>
      <button type="button" className={action} onClick={() => setConfirming(false)}>
        Keep
      </button>
      {remove.isError && (
        <span role="alert" className="text-destructive">
          {errorMessage(remove.error)}
        </span>
      )}
    </span>
  );
}

function LiveComment({
  c,
  caseId,
  depth,
  mySeed,
}: {
  c: Comment;
  caseId: number;
  depth: number;
  mySeed?: string;
}) {
  const session = useSession();
  const [replying, setReplying] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const size = depth === 0 ? 'lg' : depth === 1 ? 'md' : 'sm';
  const live = !c.deleted && c.author;

  const replies = expanded && (
    <CommentList caseId={caseId} parentCommentId={c.id} depth={depth + 1} mySeed={mySeed} />
  );

  return (
    <div>
      <div className="flex gap-3">
        {live ? (
          <UserAvatar
            seed={c.author!.avatarSeed}
            size={size}
            tone={c.author!.badgeType ? 'expert' : 'brand'}
          />
        ) : (
          <div
            aria-hidden
            className={cn(
              'shrink-0 rounded-full bg-muted',
              size === 'lg' ? 'size-10' : size === 'md' ? 'size-9' : 'size-7',
            )}
          />
        )}
        <div className="min-w-0 flex-1 space-y-1.5">
          {live ? (
            <>
              <CommentHeader
                comment={{
                  author: c.author!.displayName,
                  badge: c.author!.badgeType ?? undefined,
                  ago: timeAgo(c.createdAt),
                }}
              />
              <p className="whitespace-pre-line break-words text-[15px] leading-relaxed text-foreground/90">
                {c.body}
              </p>
            </>
          ) : (
            <p className="pt-2 text-[15px] italic text-muted-foreground">
              Comment has been deleted by author
            </p>
          )}
          <div className="flex flex-wrap gap-4 empty:hidden">
            {live && session && !replying && (
              <button type="button" className={action} onClick={() => setReplying(true)}>
                Reply
              </button>
            )}
            {c.replyCount > 0 && (
              <button
                type="button"
                className={action}
                aria-expanded={expanded}
                onClick={() => setExpanded(!expanded)}
              >
                {expanded ? 'Hide replies' : `Show replies (${c.replyCount})`}
              </button>
            )}
            {live && mySeed && c.author!.avatarSeed === mySeed && (
              <DeleteControl caseId={caseId} commentId={c.id} />
            )}
          </div>
          {replying && live && (
            <div className="pt-2">
              <CommentComposer
                caseId={caseId}
                parentCommentId={c.id}
                autoFocus
                label={`Reply to ${c.author?.displayName}`}
                placeholder="Add what you know. Say what you saw yourself."
                onPosted={() => {
                  setReplying(false);
                  setExpanded(true);
                }}
                onCancel={() => setReplying(false)}
              />
            </div>
          )}
          {replies && depth < MAX_INDENT && (
            <div className="mt-3 border-l border-border pl-4">{replies}</div>
          )}
        </div>
      </div>
      {/* Deeper replies sit outside this comment's avatar column, so they stop narrowing. */}
      {replies && depth >= MAX_INDENT && <div className="mt-5">{replies}</div>}
    </div>
  );
}

/** One level of the thread: top-level comments, or one comment's replies, loaded a page at a time. */
export function CommentList({
  caseId,
  parentCommentId,
  depth = 0,
  mySeed,
  empty,
}: {
  caseId: number;
  parentCommentId?: number;
  depth?: number;
  mySeed?: string;
  empty?: React.ReactNode;
}) {
  const comments = useCaseComments(caseId, parentCommentId);
  const list = comments.data?.pages.flatMap((page) => page.content ?? []) ?? [];
  const root = depth === 0;

  // Data first: a failed "older" page must not replace what is already loaded.
  if (!comments.data) {
    if (comments.isError) {
      return (
        <p role="alert" className="p-4 text-sm text-muted-foreground">
          {errorMessage(comments.error)}{' '}
          <button type="button" className="underline" onClick={() => comments.refetch()}>
            Try again
          </button>
        </p>
      );
    }
    return (
      <div
        className={cn('animate-pulse rounded-xl bg-muted', root ? 'm-4 h-24' : 'h-10')}
        aria-busy
      />
    );
  }
  if (list.length === 0) return root ? <>{empty}</> : null;

  return (
    <>
      <ol className={root ? 'divide-y divide-border' : 'space-y-5'}>
        {list.map((c) => (
          <li key={c.id} className={root ? 'p-4' : undefined}>
            <LiveComment c={c} caseId={caseId} depth={depth} mySeed={mySeed} />
          </li>
        ))}
      </ol>
      {comments.hasNextPage && (
        <div className={root ? 'border-t border-border p-3 text-center' : 'pt-3'}>
          {comments.isFetchNextPageError && (
            <span role="alert" className="mr-2 text-sm text-destructive">
              Couldn’t load more.
            </span>
          )}
          <button
            type="button"
            onClick={() => comments.fetchNextPage()}
            disabled={comments.isFetchingNextPage}
            className={action}
          >
            {comments.isFetchingNextPage
              ? 'Loading…'
              : comments.isFetchNextPageError
                ? 'Try again'
                : root
                  ? 'Show older comments'
                  : 'Show older replies'}
          </button>
        </div>
      )}
    </>
  );
}
