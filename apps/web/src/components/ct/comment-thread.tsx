'use client';

import { BadgeCheck, House, Lightbulb, Pin, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { Pill } from './pill';
import { UserAvatar } from './user-avatar';

export type CaseComment = {
  id: string;
  author: string;
  /** The author's public avatar seed; it stays the same when they rename. */
  avatarSeed: string;
  role?: 'FAMILY' | 'EXPERT';
  /** The author's verification badge from the API: POLICE, NGO or SUBJECT_MATTER_EXPERT. */
  badge?: string;
  pinned?: boolean;
  ago: string;
  body: string;
  replies?: CaseComment[];
};

/** Reply and Report are shown only when a handler is passed. */
export type CommentActions = { onReply?: (comment: CaseComment) => void; onReport?: (comment: CaseComment) => void };

const BADGES: Record<string, { label: string; icon: React.ReactNode }> = {
  POLICE: { label: 'Police', icon: <ShieldCheck /> },
  NGO: { label: 'NGO', icon: <BadgeCheck /> },
  SUBJECT_MATTER_EXPERT: { label: 'Expert', icon: <Lightbulb /> },
};

function Comment({ comment, depth, actions }: { comment: CaseComment; depth: number; actions: CommentActions }) {
  const [repliesHidden, setRepliesHidden] = useState(false);
  const replies = comment.replies ?? [];
  const badge = comment.badge ? BADGES[comment.badge] : undefined;
  const action = 'text-sm text-muted-foreground hover:text-foreground';
  return (
    <div className="flex gap-3">
      <UserAvatar seed={comment.avatarSeed} size={depth === 0 ? 'lg' : depth === 1 ? 'md' : 'sm'}
        tone={comment.role === 'EXPERT' || badge ? 'expert' : 'brand'} />
      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-base font-medium">{comment.author}</span>
          {comment.role === 'FAMILY' && <Pill tone="brand" icon={<House />}>Family</Pill>}
          {comment.role === 'EXPERT' && <Pill tone="expert" icon={<Lightbulb />}>Expert</Pill>}
          {badge && <Pill tone="expert" icon={badge.icon}>{badge.label}</Pill>}
          {comment.pinned && <Pill tone="pinned" icon={<Pin />}>Pinned</Pill>}
          <span className="text-muted-foreground">· {comment.ago}</span>
        </div>
        <p className="whitespace-pre-line break-words text-[15px] leading-relaxed text-foreground/90">{comment.body}</p>
        <div className="flex gap-4 empty:hidden">
          {actions.onReply && <button type="button" className={action} onClick={() => actions.onReply?.(comment)}>Reply</button>}
          {replies.length > 0 && (
            <button type="button" className={action} onClick={() => setRepliesHidden(!repliesHidden)}>
              {repliesHidden ? `Show Replies (${replies.length})` : 'Hide Replies'}
            </button>
          )}
          {actions.onReport && <button type="button" className={action} onClick={() => actions.onReport?.(comment)}>Report</button>}
        </div>
        {!repliesHidden && replies.length > 0 && (
          <div className="mt-3 space-y-5 border-l border-border pl-4">
            {replies.map((reply) => <Comment key={reply.id} comment={reply} depth={depth + 1} actions={actions} />)}
          </div>
        )}
      </div>
    </div>
  );
}

export function CommentThread({ comments, actions = {} }: { comments: CaseComment[]; actions?: CommentActions }) {
  return (
    <ol className="divide-y divide-border">
      {comments.map((comment) => (
        <li key={comment.id} className="p-4"><Comment comment={comment} depth={0} actions={actions} /></li>
      ))}
    </ol>
  );
}
