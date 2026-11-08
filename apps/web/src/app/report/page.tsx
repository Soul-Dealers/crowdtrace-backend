'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Field, FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { RequireAuth } from '@/components/app/require-auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { api, errorMessage, uploadCaseFile, unwrap, type Schemas } from '@/lib/api/client';
import { GENDERS, REGIONS, humanize } from '@/lib/enums';

const CONSENT_VERSION = process.env.NEXT_PUBLIC_CONSENT_VERSION ?? 'v1';

function Area({ label, name, required }: { label: string; name: string; required?: boolean }) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={name}>{label}</Label>
      <Textarea id={name} name={name} required={required} />
    </div>
  );
}

function ReportForm() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [step, setStep] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const form = e.currentTarget;
    const v = formValues(form);
    const reports = (form.elements.namedItem('reports') as HTMLInputElement).files!;
    const photos = (form.elements.namedItem('photos') as HTMLInputElement).files!;
    if (photos.length > 5) return setError('You can attach at most 5 photos.');

    try {
      setStep('Uploading files…');
      const reportFileIds = await Promise.all(
        [...reports].map((f) => uploadCaseFile(f, 'REPORT').then((r) => r.id!)),
      );
      const photoFileIds = await Promise.all(
        [...photos].map((f) => uploadCaseFile(f, 'PHOTO').then((r) => r.id!)),
      );

      setStep('Submitting case…');
      const res = await api.POST('/api/user/cases', {
        body: {
          fullName: v.fullName,
          age: Number(v.age),
          gender: v.gender as Schemas['CaseSubmissionRequest']['gender'],
          lastSeenDate: v.lastSeenDate,
          region: v.region as Schemas['CaseSubmissionRequest']['region'],
          lastSeenLocation: v.lastSeenLocation,
          physicalDescription: v.physicalDescription,
          clothing: v.clothing,
          circumstances: v.circumstances,
          publicContactNumber: v.publicContactNumber,
          sensitiveDetails: {
            reporterRelationship: v.reporterRelationship,
            medicalConditions: v.medicalConditions || undefined,
            knownAssociates: v.knownAssociates || undefined,
            vehicleInfo: v.vehicleInfo || undefined,
            socialMediaHandles: v.socialMediaHandles || undefined,
          },
          consent: { accepted: true, version: CONSENT_VERSION, source: 'WEB' },
          reportFileIds,
          photoFileIds,
        },
      });
      const created = unwrap<Schemas['CaseSubmissionResponse']>(res);
      router.push(`/my/cases/${created.caseId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : errorMessage(err));
    } finally {
      setStep(null);
    }
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-6">
      <FormMessage error={error} />
      <Card>
        <CardHeader>
          <CardTitle>Missing person</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name" name="fullName" required />
          <Field label="Age" name="age" type="number" min={0} max={130} required />
          <NativeSelect label="Gender" name="gender" options={GENDERS} format={humanize} required />
          <NativeSelect label="Region" name="region" options={REGIONS} format={humanize} required />
          <Field label="Last seen date" name="lastSeenDate" type="date" required />
          <Field label="Last seen location" name="lastSeenLocation" required />
          <Field label="Public contact number" name="publicContactNumber" type="tel" required />
          <div className="sm:col-span-2 grid gap-4">
            <Area label="Physical description" name="physicalDescription" required />
            <Area label="Clothing" name="clothing" required />
            <Area label="Circumstances" name="circumstances" required />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Private details (reviewers only)</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Field label="Your relationship to them" name="reporterRelationship" required />
          <Field label="Medical conditions" name="medicalConditions" />
          <Field label="Known associates" name="knownAssociates" />
          <Field label="Vehicle info" name="vehicleInfo" />
          <Field label="Social media handles" name="socialMediaHandles" />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Documents</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4">
          <Field
            label="Police report (PDF or JPEG, required)"
            name="reports"
            type="file"
            accept="application/pdf,image/jpeg"
            multiple
            required
          />
          <Field label="Photos (up to 5)" name="photos" type="file" accept="image/*" multiple />
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" required className="mt-1" />I consent to CrowdTrace processing
            the sensitive details above for case review.
          </label>
        </CardContent>
      </Card>

      <Button disabled={!!step} className="justify-self-start">
        {step ?? 'Submit case'}
      </Button>
    </form>
  );
}

export default function ReportPage() {
  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold">Report a missing person</h1>
      <RequireAuth>
        <ReportForm />
      </RequireAuth>
    </main>
  );
}
