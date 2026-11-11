'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { AuthCard } from '@/components/app/auth-card';
import { Field, FormMessage, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, errorMessage } from '@/lib/api/client';

function ResetForm() {
  const router = useRouter();
  const email = useSearchParams().get('email') ?? '';
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = formValues(e.currentTarget);
    if (v.password !== v.confirmPassword) return setError('Passwords do not match');
    const { error } = await api.POST('/api/v1/auth/reset-password', {
      body: {
        email: v.email,
        code: v.code,
        password: v.password,
        confirmPassword: v.confirmPassword,
      },
    });
    if (error) return setError(errorMessage(error));
    router.push('/login');
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-4">
      <FormMessage error={error} />
      <Field label="Email" name="email" type="email" required defaultValue={email} />
      <Field label="Reset code" name="code" required />
      <Field
        label="New password"
        name="password"
        type="password"
        required
        autoComplete="new-password"
      />
      <Field label="Confirm password" name="confirmPassword" type="password" required />
      <Button>Reset password</Button>
    </form>
  );
}

export default function ResetPasswordPage() {
  return (
    <AuthCard title="Choose a new password">
      <Suspense>
        <ResetForm />
      </Suspense>
    </AuthCard>
  );
}
