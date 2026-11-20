'use client';

import { useQuery } from '@tanstack/react-query';
import { Download, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { FormMessage } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { humanize } from '@/lib/enums';
import { previewKind } from '@/lib/evidence';

type File = Schemas['CaseFileMetadataResponse'];

/**
 * Shows one evidence file on the page. The inline link is fetched when the viewer opens (each fetch is audited)
 * and lives one minute, so it is never cached or kept after closing.
 */
export function EvidencePreview({
  caseId,
  file,
  onDownload,
  onClose,
}: {
  caseId: number;
  file: File;
  onDownload: () => Promise<string | null>;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const kind = previewKind(file.contentType);
  const title = humanize(file.purpose);
  const link = useQuery({
    queryKey: ['evidence-preview', caseId, file.id],
    enabled: !!kind,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
    queryFn: async () =>
      unwrap<Schemas['SignedFileUrl']>(
        await api.GET('/api/v1/admin/review/cases/{id}/files/{fileId}/url', {
          params: { path: { id: caseId, fileId: file.id! }, query: { inline: true } },
        }),
      ).url!,
  });

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  return (
    <dialog
      ref={dialog}
      aria-label={`Preview: ${title}`}
      onClose={onClose}
      className="m-auto h-[85vh] w-[min(64rem,calc(100vw-2rem))] flex-col rounded-2xl border border-border bg-card p-0 text-foreground backdrop:bg-black/70 open:flex"
    >
      <header className="flex items-center gap-3 border-b border-border px-4 py-3">
        <h2 className="flex-1 truncate font-medium">{title}</h2>
        <Button
          size="sm"
          variant="outline"
          onClick={async () => setDownloadError(await onDownload())}
        >
          <Download aria-hidden /> Download
        </Button>
        <Button
          size="sm"
          variant="ghost"
          aria-label="Close"
          onClick={() => dialog.current?.close()}
        >
          <X aria-hidden />
        </Button>
      </header>
      {downloadError && (
        <div className="px-4 pt-3">
          <FormMessage error={downloadError} />
        </div>
      )}
      <div className="grid min-h-0 flex-1 place-items-center overflow-auto bg-black/40 p-2">
        {!kind && (
          <p className="text-sm text-muted-foreground">
            This file type can&apos;t be previewed. Download it instead.
          </p>
        )}
        {link.isError && <FormMessage error={(link.error as Error).message} />}
        {kind && link.isPending && (
          <div className="size-full animate-pulse rounded-xl bg-muted" aria-busy />
        )}
        {kind === 'image' && link.data && (
          <img
            src={link.data}
            alt={title}
            referrerPolicy="no-referrer"
            className="max-h-full max-w-full object-contain"
          />
        )}
        {kind === 'pdf' && link.data && (
          <iframe
            src={link.data}
            title={title}
            referrerPolicy="no-referrer"
            className="size-full rounded-lg bg-white"
          />
        )}
      </div>
    </dialog>
  );
}
