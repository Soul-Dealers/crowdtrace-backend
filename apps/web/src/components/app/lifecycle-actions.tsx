'use client';

import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { api, errorMessage, uploadCaseFile } from '@/lib/api/client';
import { CASE_STATUSES, humanize } from '@/lib/enums';

type Status = (typeof CASE_STATUSES)[number];

/**
 * Post-approval case actions. Reporters and admins share status/closing-statement endpoints
 * under different prefixes; only admins can take down / restore, only reporters add photos.
 */
export function LifecycleActions({
  caseId,
  version,
  as,
  queryKey,
  takenDown,
}: {
  caseId: number;
  version: number;
  as: 'reporter' | 'admin';
  queryKey: unknown[];
  takenDown?: boolean;
}) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const path = { params: { path: { id: caseId } } };

  async function run(action: () => Promise<{ error?: unknown }>, done: string) {
    setError(null);
    setNotice(null);
    try {
      const { error } = await action();
      if (error) return setError(errorMessage(error));
      setNotice(done);
      await qc.invalidateQueries({ queryKey });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function updateStatus(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const body = {
      expectedCaseVersion: version,
      caseStatus: formValues(e.currentTarget).caseStatus as Status,
    };
    run(
      () =>
        as === 'admin'
          ? api.PATCH('/api/v1/admin/cases/{id}/status', { ...path, body })
          : api.PATCH('/api/user/cases/{id}/status', { ...path, body }),
      'Status updated.',
    );
  }

  function setClosing(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const body = {
      expectedCaseVersion: version,
      closingStatement: formValues(e.currentTarget).closingStatement,
    };
    run(
      () =>
        as === 'admin'
          ? api.PUT('/api/v1/admin/cases/{id}/closing-statement', { ...path, body })
          : api.PUT('/api/user/cases/{id}/closing-statement', { ...path, body }),
      'Closing statement saved.',
    );
  }

  function addPhotos(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const files = (e.currentTarget.elements.namedItem('photos') as HTMLInputElement).files;
    if (!files?.length) return;
    run(async () => {
      const ids = await Promise.all(
        [...files].map((f) => uploadCaseFile(f, 'PHOTO').then((r) => r.id!)),
      );
      return api.POST('/api/user/cases/{id}/photos', {
        ...path,
        body: { expectedCaseVersion: version, photoFileIds: ids },
      });
    }, 'Photos added.');
  }

  function takedown(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const { reason } = formValues(e.currentTarget);
    run(
      () =>
        api.POST('/api/v1/admin/cases/{id}/takedown', {
          ...path,
          body: { expectedCaseVersion: version, reason },
        }),
      'Case taken down.',
    );
  }

  function restore() {
    run(
      () =>
        api.POST('/api/v1/admin/cases/{id}/restore', {
          ...path,
          body: { expectedCaseVersion: version },
        }),
      'Case restored.',
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Case actions</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-6">
        <FormMessage error={error} success={notice} />
        <form onSubmit={updateStatus} className="flex items-end gap-2">
          <NativeSelect
            label="Case status"
            name="caseStatus"
            options={CASE_STATUSES}
            format={humanize}
          />
          <Button>Update</Button>
        </form>
        <form onSubmit={setClosing} className="grid gap-2">
          <Label htmlFor="closingStatement">Closing statement</Label>
          <Textarea id="closingStatement" name="closingStatement" required />
          <Button className="justify-self-start">Save statement</Button>
        </form>
        {as === 'reporter' && (
          <form onSubmit={addPhotos} className="grid gap-2">
            <Label htmlFor="photos">Add photos (JPEG/PNG, max 5)</Label>
            <Input id="photos" name="photos" type="file" accept="image/*" multiple />
            <Button className="justify-self-start">Upload photos</Button>
          </form>
        )}
        {as === 'admin' &&
          (takenDown ? (
            <Button variant="outline" className="justify-self-start" onClick={restore}>
              Restore case
            </Button>
          ) : (
            <form onSubmit={takedown} className="grid gap-2">
              <Label htmlFor="reason">Take down reason</Label>
              <Textarea id="reason" name="reason" required />
              <Button variant="destructive" className="justify-self-start">
                Take down
              </Button>
            </form>
          ))}
      </CardContent>
    </Card>
  );
}
