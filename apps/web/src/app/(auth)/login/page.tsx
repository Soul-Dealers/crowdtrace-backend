'use client';

import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { AuthCard } from '@/components/app/auth-card';
import { Field, FormMessage, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, unwrap, type LoginResponse } from '@/lib/api/client';
import { saveSession } from '@/lib/auth';

function LoginForm() {
  const router = useRouter();
  const qc = useQueryClient();
  const expired = useSearchParams().get('expired');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError(null);
    const { email, password } = formValues(e.currentTarget);
    try {
      const res = await api.POST('/api/v1/auth/login', { body: { email, password } });
      qc.clear();
      saveSession(unwrap<LoginResponse>(res));
      router.push('/');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-4">
      {expired && !error && <FormMessage error="Your session expired. Please log in again." />}
      <FormMessage error={error} />
      <Field label="Email" name="email" type="email" required autoComplete="email" />
      <Field
        label="Password"
        name="password"
        type="password"
        required
        autoComplete="current-password"
      />
      <Button disabled={pending}>{pending ? 'Logging in…' : 'Log in'}</Button>
      <div className="flex justify-between text-sm">
        <Link href="/forgot-password" className="text-text-secondary hover:text-foreground">
          Forgot password?
        </Link>
        <Link href="/signup" className="font-medium text-brand hover:underline">
          Create an account
        </Link>
      </div>
    </form>
  );
}

export default function LoginPage() {
  return (
    <AuthCard title="Log in">
      <Suspense>
        <LoginForm />
      </Suspense>
    </AuthCard>
  );
}
