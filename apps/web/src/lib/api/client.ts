import createClient, { type Middleware } from 'openapi-fetch';
import { clearSession, getToken, type Role } from '@/lib/auth';
import type { components, paths } from './schema';

export type Schemas = components['schemas'];

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

export const api = createClient<paths>({ baseUrl: API_URL });

const bearer: Middleware = {
  onRequest({ request }) {
    const token = getToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  onResponse({ response }) {
    // Expired or revoked token: drop it and send the user to log in again.
    if (response.status === 401 && getToken()) {
      clearSession();
      window.location.assign('/login?expired=1');
    }
    return response;
  },
};
api.use(bearer);

export class ApiError extends Error {}

/** Spring returns either ProblemDetail ({detail}) or ApiResponse ({message, errors}). */
export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const e = error as { detail?: string; message?: string; title?: string; errors?: unknown };
    if (e.errors && typeof e.errors === 'object') {
      const first = Object.values(e.errors as Record<string, string>)[0];
      if (first) return String(first);
    }
    return e.detail ?? e.message ?? e.title ?? 'Something went wrong';
  }
  return 'Something went wrong';
}

/**
 * Throws on error, returns the body's `data` envelope field. `T` is explicit because several
 * endpoints declare a generic ApiResponse in OpenAPI, so the inferred payload type is wrong.
 */
export function unwrap<T>(res: { data?: unknown; error?: unknown }): T {
  if (res.error !== undefined || !res.data) throw new ApiError(errorMessage(res.error));
  return (res.data as { data: T }).data;
}

/** Auth endpoints declare a generic envelope in OpenAPI, so their payloads are typed here. */
export interface LoginResponse {
  displayName: string;
  email: string;
  role: Role;
  token: string;
}

export async function uploadCaseFile(file: File, purpose: 'REPORT' | 'PHOTO') {
  const body = new FormData();
  body.append('file', file);
  body.append('purpose', purpose);
  const token = getToken();
  const res = await fetch(`${API_URL}/api/user/case-files`, {
    method: 'POST',
    body,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(errorMessage(json));
  return (json as Schemas['CaseFileUploadResponse']).data!;
}
