'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AuthCard } from '@/components/app/auth-card';
import { Field, FormMessage, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, errorMessage } from '@/lib/api/client';

export default function SignupPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError(null);
    const { email, password, displayName } = formValues(e.currentTarget);
    try {
      const { error } = await api.POST('/api/v1/auth/signup', {
        body: { email, password, displayName },
      });
      if (error) return setError(errorMessage(error));
      router.push(`/verify-otp?email=${encodeURIComponent(email)}`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <AuthCard
      title="Create an account"
      description="We'll email you a code to verify your address."
    >
      <form onSubmit={onSubmit} className="grid gap-4">
        <FormMessage error={error} />
        <Field label="Display name" name="displayName" required />
        <Field label="Email" name="email" type="email" required autoComplete="email" />
        <Field
          label="Password"
          name="password"
          type="password"
          required
          autoComplete="new-password"
        />
        <Button disabled={pending}>{pending ? 'Creating…' : 'Sign up'}</Button>
      </form>
    </AuthCard>
  );
}
