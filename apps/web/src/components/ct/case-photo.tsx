'use client';

import { UserRound } from 'lucide-react';
import { useState } from 'react';
import { API_URL } from '@/lib/api/client';
import { cn } from '@/lib/utils';

export function publicPhotoUrl(caseId: number, photoId: number): string {
  return `${API_URL}/api/public/cases/${caseId}/photos/${photoId}`;
}

/** Case photo, or the silhouette placeholder on the design's dark-to-brown gradient when there is none. */
export function CasePhoto({ caseId, photoId, alt, className, iconClassName }: {
  caseId: number;
  photoId?: number | null;
  alt: string;
  className?: string;
  iconClassName?: string;
}) {
  // Storage may be unable to sign (503) or the object may be gone; show the placeholder rather than a broken image.
  const [failed, setFailed] = useState(false);
  return (
    <div className={cn('relative overflow-hidden rounded-2xl bg-gradient-to-b from-[#0b0b0b] to-[#2b2117] ring-1 ring-white/5', className)}>
      {photoId && !failed ? (
        // A plain img: the photo URL redirects to a short-lived signed URL, which next/image would cache.
        <img src={publicPhotoUrl(caseId, photoId)} alt={alt} className="size-full object-cover" loading="lazy"
          onError={() => setFailed(true)} />
      ) : (
        <div className="flex size-full items-center justify-center" aria-label={`${alt}: no photo`} role="img">
          <UserRound className={cn('size-2/5 text-[#d9d9d9]', iconClassName)} strokeWidth={1.5} />
        </div>
      )}
    </div>
  );
}
