'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Field, FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { RequireAuth } from '@/components/app/require-auth';
import { Button } from '@/components/ui/button';
import { WithRail } from '@/components/ct/app-shell';
import { EmergencyCard } from '@/components/ct/emergency-card';
import { Panel } from '@/components/ct/panel';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { api, errorMessage, uploadCaseFile, unwrap, type Schemas } from '@/lib/api/client';
import { GENDERS, REGIONS, humanize } from '@/lib/enums';

const CONSENT_VERSION = process.env.NEXT_PUBLIC_CONSENT_VERSION ?? 'v1';

function Area({
  label,
  name,
  ...props
}: React.ComponentProps<typeof Textarea> & { label: string; name: string }) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={name}>{label}</Label>
      <Textarea id={name} name={name} {...props} />
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
    <form onSubmit={onSubmit} className="grid gap-5">
      <FormMessage error={error} />
      <Section
        n={1}
        title="Who is missing"
        note="Shown publicly once a moderator approves the case."
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name" name="fullName" required />
          <Field label="Age" name="age" type="number" min={0} max={130} required />
          <NativeSelect
            label="Gender"
            name="gender"
            options={GENDERS}
            format={humanize}
            placeholder="Select"
            required
          />
          <NativeSelect
            label="Region"
            name="region"
            options={REGIONS}
            format={humanize}
            placeholder="Select"
            required
          />
          <Field
            label="Last seen date"
            name="lastSeenDate"
            type="date"
            required
            max={new Date().toISOString().slice(0, 10)}
          />
          <Field
            label="Last seen location"
            name="lastSeenLocation"
            placeholder="e.g. Kaneshie Market, Accra"
            required
          />
          <Field
            label="Public contact number"
            name="publicContactNumber"
            type="tel"
            placeholder="024 000 0000"
            hint="People with information will call this number."
            required
          />
        </div>
        <div className="mt-4 grid gap-4">
          <Area
            label="Physical description"
            name="physicalDescription"
            placeholder="Height, build, hair, distinguishing marks"
            required
          />
          <Area label="Clothing when last seen" name="clothing" required />
          <Area
            label="What happened"
            name="circumstances"
            placeholder="Where they were going, who they were with"
            required
          />
        </div>
      </Section>

      <Section n={2} title="Private details" note="Only moderators reviewing the case see these.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Your relationship to them"
            name="reporterRelationship"
            placeholder="e.g. Mother"
            required
          />
          <Field label="Medical conditions" name="medicalConditions" />
          <Field label="Known associates" name="knownAssociates" />
          <Field label="Vehicle info" name="vehicleInfo" />
          <Field label="Social media handles" name="socialMediaHandles" />
        </div>
      </Section>

      <Section n={3} title="Documents and photos">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Police report"
            name="reports"
            type="file"
            accept="application/pdf,image/jpeg"
            multiple
            required
            className="h-auto py-2"
            hint="PDF or JPEG. Required; reviewers only."
          />
          <Field
            label="Photos"
            name="photos"
            type="file"
            accept="image/*"
            multiple
            className="h-auto py-2"
            hint="Up to 5. Location and device data are removed."
          />
        </div>
        <label className="mt-5 flex items-start gap-3 rounded-xl border border-border bg-muted p-3.5 text-sm text-text-secondary">
          <input type="checkbox" required className="mt-0.5 size-4 accent-brand" />I consent to
          Over Here processing the sensitive details above for case review.
        </label>
      </Section>

      <div className="flex flex-wrap items-center gap-3">
        <Button size="lg" disabled={!!step}>
          {step ?? 'Submit for review'}
        </Button>
        <span className="text-sm text-text-subtle">
          Nothing is public until a moderator approves it.
        </span>
      </div>
    </form>
  );
}

function Section({
  n,
  title,
  note,
  children,
}: {
  n: number;
  title: string;
  note?: string;
  children: React.ReactNode;
}) {
  return (
    <Panel
      title={
        <span className="flex items-center gap-2.5">
          <span className="flex size-6 items-center justify-center rounded-full bg-chip font-mono text-xs">
            {n}
          </span>
          {title}
        </span>
      }
      meta={note && <span className="hidden sm:inline">{note}</span>}
      bodyClassName="p-4 sm:p-5"
    >
      {note && <p className="mb-4 text-sm text-muted-foreground sm:hidden">{note}</p>}
      {children}
    </Panel>
  );
}

const NEXT_STEPS = [
  ['Submitted', 'Your report goes to a moderator, not the public.'],
  ['Reviewed', 'They check the police report and may contact you.'],
  ['Published', 'Approved cases appear on Browse with your contact number.'],
] as const;

export default function ReportPage() {
  return (
    <div className="space-y-5">
      <header>
        <h1 className="text-2xl font-semibold sm:text-3xl">Report a missing person</h1>
        <p className="text-muted-foreground">
          Takes about 5 minutes. Have the police report ready.
        </p>
      </header>
      <WithRail
        rail={<>
          <EmergencyCard />
          <Panel title="What happens next" mono bodyClassName="p-4">
            <ol className="space-y-4">
              {NEXT_STEPS.map(([label, text], i) => (
                <li key={label} className="flex gap-3">
                  <span className="flex size-6 shrink-0 items-center justify-center rounded-full border border-border font-mono text-xs">
                    {i + 1}
                  </span>
                  <div>
                    <div className="text-sm font-medium">{label}</div>
                    <p className="text-sm text-muted-foreground">{text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </Panel>
        </>}
      >
        <RequireAuth>
          <ReportForm />
        </RequireAuth>
      </WithRail>
    </div>
  );
}
