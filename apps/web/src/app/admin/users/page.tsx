'use client';

import { useEffect, useState } from 'react';
import { FormMessage, NativeSelect } from '@/components/app/form';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { PageHeader, TABLE } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { errorMessage } from '@/lib/api/client';
import {
  ACTION_LABEL,
  availableActions,
  confirmText,
  useAdminUsers,
  useUserAction,
  type AdminUser,
  type UserAction,
  type UserFilters,
} from '@/lib/admin-users';
import { useSession } from '@/lib/auth';
import { formatDate, humanize } from '@/lib/enums';

const ROLES = ['REGISTERED_USER', 'MODERATOR', 'SUPER_ADMIN'] as const;
const STATUSES = ['ACTIVE', 'PENDING_VERIFICATION', 'SUSPENDED', 'INACTIVE', 'DEACTIVATED'] as const;

/** Waits for typing to pause so each keystroke doesn't fetch. */
function useDebounced(value: string, ms = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

function Actions({ user, myEmail }: { user: AdminUser; myEmail?: string }) {
  const [pending, setPending] = useState<UserAction>();
  const run = useUserAction();
  const actions = availableActions(user, myEmail);
  // The refetch after a 409 may leave no actions on this row; the message still explains why.
  const error = run.isError && <FormMessage error={run.error.message} />;
  if (actions.length === 0) return error || null;

  if (pending) {
    return (
      <span className="flex flex-wrap items-center justify-end gap-2">
        <span className="text-text-secondary">{confirmText(pending, user.displayName)}</span>
        <Button
          size="sm"
          variant={pending === 'promote' ? 'default' : 'destructive'}
          disabled={run.isPending}
          onClick={() => run.mutate({ action: pending, id: user.id }, { onSettled: () => setPending(undefined) })}
        >
          Confirm
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setPending(undefined)}>
          Cancel
        </Button>
      </span>
    );
  }
  return (
    <span className="flex flex-col items-end gap-1">
      <span className="flex justify-end gap-1.5">
        {actions.map((action) => (
          <Button
            key={action}
            size="sm"
            variant="outline"
            className={action === 'deactivate' ? 'text-destructive' : undefined}
            onClick={() => {
              run.reset();
              setPending(action);
            }}
          >
            {ACTION_LABEL[action]}
          </Button>
        ))}
      </span>
      {error}
    </span>
  );
}

function Users() {
  const myEmail = useSession()?.email;
  const [role, setRole] = useState<UserFilters['role']>();
  const [status, setStatus] = useState<UserFilters['status']>();
  const [search, setSearch] = useState('');
  const q = useDebounced(search.trim());
  const query = useAdminUsers({ role, status, q });
  const rows = query.data?.pages.flatMap((p) => p.content ?? []) ?? [];

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="grid gap-1.5">
          <Label htmlFor="user-search">Search users</Label>
          <Input
            id="user-search"
            type="search"
            placeholder="Email or name starts with…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <NativeSelect
          label="Role"
          name="role"
          placeholder="All roles"
          options={ROLES}
          format={humanize}
          value={role ?? ''}
          onChange={(e) => setRole((e.target.value || undefined) as UserFilters['role'])}
        />
        <NativeSelect
          label="Status"
          name="status"
          placeholder="All statuses"
          options={STATUSES}
          format={humanize}
          value={status ?? ''}
          onChange={(e) => setStatus((e.target.value || undefined) as UserFilters['status'])}
        />
      </div>
      {query.isError && <FormMessage error={errorMessage(query.error)} />}
      {query.isSuccess && rows.length === 0 && (
        <p className="text-sm text-muted-foreground">No users match.</p>
      )}
      {rows.length > 0 && (
        <Panel bodyClassName="overflow-x-auto">
          <table className={TABLE.table}>
            <thead className={TABLE.head}>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th>Joined</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <tr key={u.id} className={TABLE.row}>
                  <td className="font-medium">{u.displayName}</td>
                  <td className="text-text-secondary">{u.email}</td>
                  <td className="text-text-secondary">{humanize(u.role)}</td>
                  <td>
                    <StatusBadge status={u.status} />
                  </td>
                  <td className="text-text-secondary">{formatDate(u.createdAt)}</td>
                  <td>
                    <Actions user={u} myEmail={myEmail} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
      {query.hasNextPage && (
        <Button variant="outline" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          Load more
        </Button>
      )}
    </div>
  );
}

export default function AdminUsersPage() {
  return (
    <div className="space-y-5">
      <PageHeader title="Users" description="Promote moderators and deactivate accounts." />
      <RequireAuth superAdmin>
        <Users />
      </RequireAuth>
    </div>
  );
}
