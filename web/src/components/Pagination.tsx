import { format } from '@nadel/shared';
import { useT } from '../i18n';

const button = 'rounded-md border border-line px-3 py-1.5 text-sm disabled:opacity-50';

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
      <button type="button" className={button} disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        {t.pagination.previous}
      </button>
      <span className="text-sm text-muted">
        {format(t.pagination.pageOf, { page: page + 1, total: totalPages })}
      </span>
      <button
        type="button"
        className={button}
        disabled={page >= totalPages - 1}
        onClick={() => onPageChange(page + 1)}
      >
        {t.pagination.next}
      </button>
    </nav>
  );
}
