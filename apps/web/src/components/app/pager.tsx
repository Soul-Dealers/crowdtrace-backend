import { Button } from '@/components/ui/button';

export function Pager({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages?: number;
  onChange: (page: number) => void;
}) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <div className="mt-4 flex items-center gap-3 text-sm">
      <Button size="sm" variant="outline" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </Button>
      Page {page + 1} of {totalPages}
      <Button
        size="sm"
        variant="outline"
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        Next
      </Button>
    </div>
  );
}
