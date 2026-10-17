import type { ReactNode } from 'react';
import { useT } from '../i18n';
import { problemMessage } from '../problem';

interface QueryLike<TData> {
  data: TData | undefined;
  error: unknown;
  isPending: boolean;
  isError: boolean;
  refetch: () => unknown;
}

/** Renders loading, error (with retry) and empty states around a query; otherwise the children. */
export function QueryBoundary<TData>({
  query,
  isEmpty,
  emptyMessage,
  children,
}: {
  query: QueryLike<TData>;
  isEmpty?: (data: TData) => boolean;
  emptyMessage?: string;
  children: (data: TData) => ReactNode;
}) {
  const t = useT();
  if (query.isPending) return <div className="p-6 text-muted">{t.state.loading}</div>;
  if (query.isError || query.data === undefined) {
    return (
      <div role="alert" className="flex flex-col items-start gap-3 p-6">
        <p className="text-muted">{problemMessage(t, query.error)}</p>
        <button
          type="button"
          onClick={() => void query.refetch()}
          className="rounded-md border border-line px-3 py-1.5 text-sm"
        >
          {t.errors.retry}
        </button>
      </div>
    );
  }
  if (isEmpty?.(query.data) ?? false) {
    return <div className="p-6 text-muted">{emptyMessage ?? t.state.empty}</div>;
  }
  return <>{children(query.data)}</>;
}
