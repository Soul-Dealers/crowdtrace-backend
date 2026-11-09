'use client';

import { useState } from 'react';
import { cn } from '@/lib/utils';
import { CasePhoto, publicPhotoUrl } from './case-photo';

/** Main case photo with a thumbnail strip when there is more than one. Falls back to the silhouette. */
export function CaseGallery({ caseId, photoIds, alt, className }: {
  caseId: number;
  photoIds: number[];
  alt: string;
  className?: string;
}) {
  const [picked, setPicked] = useState<number>();
  // Derived so a refetch that adds or removes photos never leaves a stale or empty selection.
  const selected = picked !== undefined && photoIds.includes(picked) ? picked : photoIds[0];
  return (
    <div className={cn('space-y-2', className)}>
      {/* keyed so a failed image doesn't hide the next one the user picks */}
      <CasePhoto key={selected} caseId={caseId} photoId={selected} alt={alt} className="aspect-[4/3]" />
      {photoIds.length > 1 && (
        <ul className="flex gap-2 overflow-x-auto">
          {photoIds.map((id, i) => (
            <li key={id} className="shrink-0">
              <button type="button" onClick={() => setPicked(id)} aria-label={`Photo ${i + 1} of ${photoIds.length}`}
                aria-pressed={id === selected}
                className={cn('block size-14 overflow-hidden rounded-lg ring-1 ring-white/10',
                  id === selected && 'ring-2 ring-brand')}>
                <img src={publicPhotoUrl(caseId, id)} alt="" className="size-full object-cover" loading="lazy"
                  onError={(e) => { e.currentTarget.style.visibility = 'hidden'; }} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
