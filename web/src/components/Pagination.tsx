import { format } from '@nadel/shared';
import { useT } from '../i18n';
import { Button } from './ui';

/** `page` is 0-based; the label shows it 1-based. */
export function Pagination({
  page,
  totalPages,
  onPageChange,
}: {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  const t = useT();
  if (totalPages <= 1) return null;
  return (
    <nav aria-label={t.pagination.label} className="flex items-center gap-3">
      <Button variant="secondary" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        {t.pagination.previous}
      </Button>
      <span className="text-[15px] text-ink-muted">
        {format(t.pagination.pageOf, { page: page + 1, total: totalPages })}
      </span>
      <Button variant="secondary" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>
        {t.pagination.next}
      </Button>
    </nav>
  );
}
