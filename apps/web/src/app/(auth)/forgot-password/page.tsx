'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AuthCard } from '@/components/app/auth-card';
import { Field, FormMessage, formValues } from '@/components/app/form';
import { Button } from '@/components/ui/button';
import { api, errorMessage } from '@/lib/api/client';

export default function ForgotPasswordPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const { email } = formValues(e.currentTarget);
    const { error } = await api.POST('/api/v1/auth/request-password-reset', { body: { email } });
    if (error) return setError(errorMessage(error));
    router.push(`/reset-password?email=${encodeURIComponent(email)}`);
  }

  return (
    <AuthCard title="Reset your password" description="We'll email you a reset code.">
      <form onSubmit={onSubmit} className="grid gap-4">
        <FormMessage error={error} />
        <Field label="Email" name="email" type="email" required />
        <Button>Send code</Button>
      </form>
    </AuthCard>
  );
}
