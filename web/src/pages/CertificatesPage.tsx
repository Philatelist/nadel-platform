import {
  CertificateStatus,
  getCertificatePdf,
  getListCertificatesQueryKey,
  useAnnulCertificate,
  useListCertificates,
  type CertificateRow,
} from '@nadel/api-client';
import { format, formatDateTime, formatDay } from '@nadel/shared';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { AnnulDialog } from '../components/certificates/AnnulDialog';
import { certificateFileName } from '../components/certificates/certificateFile';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import {
  Badge,
  Button,
  DataTable,
  Money,
  Notice,
  PageHeader,
  Panel,
  Select,
  type Column,
} from '../components/ui';
import { usePdfAction } from '../hooks/usePdfAction';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { problemFieldErrors, problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 20;
const STATUSES = Object.values(CertificateStatus);

/** The register of debt certificates, newest first; the status filter lives in the address. */
export function CertificatesPage() {
  const t = useT();
  const c = t.certificates;
  const { locale } = useLocale();
  useDocumentTitle(c.title);
  const canAnnul = useCan('CERTIFICATES_ISSUE');
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const status = STATUSES.find((value) => value === params.get('status'));
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const [annulling, setAnnulling] = useState<CertificateRow>();
  const [annulError, setAnnulError] = useState<unknown>();
  const pdf = usePdfAction();

  const query = useListCertificates(
    { status, page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  const annul = useAnnulCertificate({
    mutation: {
      onSuccess: async () => {
        setAnnulling(undefined);
        setAnnulError(undefined);
        await queryClient.invalidateQueries({ queryKey: getListCertificatesQueryKey() });
      },
      onError: (error: unknown) => setAnnulError(error),
    },
  });

  function setStatus(value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set('status', value);
    else next.delete('status');
    next.delete('page');
    setParams(next);
  }

  const col = c.columns;
  const columns: Column<CertificateRow>[] = [
    { key: 'number', header: col.number, cell: (r) => <span className="font-mono">{r.number}</span> },
    {
      key: 'plot',
      header: col.plot,
      cell: (r) => (
        <Link to={`/plots/${r.plotId}`} className="text-accent underline">
          <UserContent>{r.plotNumber}</UserContent>
        </Link>
      ),
    },
    { key: 'asOf', header: col.asOf, cell: (r) => formatDay(r.asOf, locale) },
    { key: 'debt', header: col.debt, numeric: true, cell: (r) => <Money amount={r.totalDebt} /> },
    {
      key: 'issuedBy',
      header: col.issuedBy,
      cell: (r) => (r.issuedByName ? <UserContent>{r.issuedByName}</UserContent> : null),
    },
    { key: 'issuedAt', header: col.issuedAt, cell: (r) => formatDateTime(r.issuedAt, locale) },
    {
      key: 'status',
      header: col.status,
      cell: (r) => (
        <div className="flex flex-col gap-1">
          <Badge tone={r.status === 'ANNULLED' ? 'neutral' : 'success'}>{c.status[r.status]}</Badge>
          {r.status === 'ANNULLED' && (
            <>
              {r.annulledAt !== undefined && (
                <span className="text-sm text-ink-muted">
                  {format(c.annulledBy, {
                    name: r.annulledByName ?? '—',
                    when: formatDateTime(r.annulledAt, locale),
                  })}
                </span>
              )}
              {r.annulReason !== undefined && (
                <span className="text-sm text-ink-muted">
                  {c.annulReason}: <UserContent>{r.annulReason}</UserContent>
                </span>
              )}
            </>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      header: col.actions,
      cell: (r) => (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            disabled={pdf.pending}
            onClick={() => void pdf.run(() => getCertificatePdf(r.id), certificateFileName(r.number))}
          >
            {pdf.pending ? c.downloading : c.download}
          </Button>
          {canAnnul && r.status === 'ISSUED' && (
            <Button
              variant="secondary"
              onClick={() => {
                setAnnulError(undefined);
                setAnnulling(r);
              }}
            >
              {c.annul}
            </Button>
          )}
        </div>
      ),
    },
  ];

  const fieldErrors = annulError === undefined ? {} : problemFieldErrors(t, annulError);
  const annulMessage =
    annulError === undefined ? undefined : (fieldErrors.reason ?? problemMessage(t, annulError));

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={c.title} />
      <Panel>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <Select label={c.filters.status} value={status ?? ''} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{c.filters.all}</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {c.filters[value]}
              </option>
            ))}
          </Select>
        </div>
        {pdf.error !== undefined && <Notice tone="danger">{problemMessage(t, pdf.error)}</Notice>}
        <QueryBoundary
          query={query}
          isEmpty={(data) => data.items.length === 0}
          emptyMessage={status !== undefined ? c.nothingFound : c.empty}
        >
          {(data) => (
            <>
              <DataTable
                columns={columns}
                rows={data.items}
                rowKey={(r) => r.id}
                caption={c.title}
                emptyMessage={c.empty}
              />
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                onPageChange={(p) => {
                  const next = new URLSearchParams(params);
                  next.set('page', String(p));
                  setParams(next);
                }}
              />
            </>
          )}
        </QueryBoundary>
      </Panel>
      {annulling !== undefined && (
        <AnnulDialog
          number={annulling.number}
          busy={annul.isPending}
          error={annulMessage}
          onConfirm={(reason) => annul.mutate({ id: annulling.id, data: { reason } })}
          onCancel={() => setAnnulling(undefined)}
        />
      )}
    </div>
  );
}
