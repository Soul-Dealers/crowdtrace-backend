'use client';

import { House, Lightbulb, Pin } from 'lucide-react';
import { useState } from 'react';
import { AvatarInitials } from './avatar-initials';
import { Pill } from './pill';

export type CaseComment = {
  id: string;
  author: string;
  role?: 'FAMILY' | 'EXPERT';
  pinned?: boolean;
  ago: string;
  body: string;
  replies?: CaseComment[];
};

/** Reply and Report stay disabled until the comments API exists; pass handlers to enable them. */
export type CommentActions = { onReply?: (comment: CaseComment) => void; onReport?: (comment: CaseComment) => void };

function Comment({ comment, depth, actions }: { comment: CaseComment; depth: number; actions: CommentActions }) {
  const [repliesHidden, setRepliesHidden] = useState(false);
  const replies = comment.replies ?? [];
  const action = 'text-sm text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:hover:text-muted-foreground';
  return (
    <div className="flex gap-3">
      <AvatarInitials name={comment.author} size={depth === 0 ? 'lg' : depth === 1 ? 'md' : 'sm'}
        tone={comment.role === 'EXPERT' ? 'expert' : 'brand'} />
      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-base font-medium">{comment.author}</span>
          {comment.role === 'FAMILY' && <Pill tone="brand" icon={<House />}>Family</Pill>}
          {comment.role === 'EXPERT' && <Pill tone="expert" icon={<Lightbulb />}>Expert</Pill>}
          {comment.pinned && <Pill tone="pinned" icon={<Pin />}>Pinned</Pill>}
          <span className="text-muted-foreground">· {comment.ago}</span>
        </div>
        <p className="text-[15px] leading-relaxed text-foreground/90">{comment.body}</p>
        <div className="flex gap-4">
          <button type="button" className={action} disabled={!actions.onReply} onClick={() => actions.onReply?.(comment)}>Reply</button>
          {replies.length > 0 && (
            <button type="button" className={action} onClick={() => setRepliesHidden(!repliesHidden)}>
              {repliesHidden ? `Show Replies (${replies.length})` : 'Hide Replies'}
            </button>
          )}
          <button type="button" className={action} disabled={!actions.onReport} onClick={() => actions.onReport?.(comment)}>Report</button>
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
