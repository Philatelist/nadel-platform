import type { ReactNode } from 'react';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { EmptyState, ErrorState, LoadingState } from './ui';

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
  if (query.isPending) return <LoadingState message={t.state.loading} />;
  if (query.isError || query.data === undefined) {
    return <ErrorState message={problemMessage(t, query.error)} onRetry={() => void query.refetch()} />;
  }
  if (isEmpty?.(query.data) ?? false) {
    return <EmptyState message={emptyMessage ?? t.state.empty} />;
  }
  return <>{children(query.data)}</>;
}
