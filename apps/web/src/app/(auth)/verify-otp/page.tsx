'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { AuthCard } from '@/components/app/auth-card';
import { Field, FormMessage, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, errorMessage } from '@/lib/api/client';

function VerifyForm() {
  const router = useRouter();
  const initialEmail = useSearchParams().get('email') ?? '';
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const { email, code } = formValues(e.currentTarget);
    const { error } = await api.POST('/api/v1/auth/verify-otp', { body: { email, code } });
    if (error) return setError(errorMessage(error));
    router.push('/login');
  }

  async function resend(email: string) {
    setError(null);
    const { error } = await api.POST('/api/v1/auth/resend-otp', { body: { email } });
    if (error) setError(errorMessage(error));
    else setNotice('A new code is on its way.');
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-4">
      <FormMessage error={error} success={notice} />
      <Field label="Email" name="email" type="email" required defaultValue={initialEmail} />
      <Field label="Code" name="code" required inputMode="numeric" autoComplete="one-time-code" />
      <Button>Verify</Button>
      <Button
        type="button"
        variant="ghost"
        onClick={(e) => resend(String(new FormData(e.currentTarget.form!).get('email')))}
      >
        Resend code
      </Button>
    </form>
  );
}

export default function VerifyOtpPage() {
  return (
    <AuthCard title="Verify your email" description="Enter the code we emailed you.">
      <Suspense>
        <VerifyForm />
      </Suspense>
    </AuthCard>
  );
}
