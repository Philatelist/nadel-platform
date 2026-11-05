import {
  ApiError,
  getListTariffsQueryKey,
  useDeleteTariff,
  useGetTariff,
  type Tariff,
} from '@nadel/api-client';
import { formatDay, format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { UserContent, formatUser } from '../components/UserContent';
import { QueryBoundary } from '../components/QueryBoundary';
import { ArchiveTariffDialog } from '../components/tariffs/ArchiveTariffDialog';
import { TariffConflicts } from '../components/tariffs/TariffConflicts';
import { ScopeEditor } from '../components/tariffs/ScopeEditor';
import { NewVersionForm } from '../components/tariffs/NewVersionForm';
import { VersionHistory } from '../components/tariffs/VersionHistory';
import { Badge, Button, Notice, PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';
import { NotFoundPage } from './NotFoundPage';

/** «Delete tariff» with a confirmation; the server refuses (409 TARIFF_IN_USE) once the tariff has charges. */
function DeleteTariff({ tariff }: { tariff: Tariff }) {
  const t = useT();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string>();
  const remove = useDeleteTariff({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() });
        await queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).includes('/tariffs') });
        void navigate('/tariffs');
      },
      onError: (e: unknown) => {
        setConfirming(false);
        setError(problemMessage(t, e));
      },
    },
  });
  const d = t.tariffs.delete;
  return (
    <>
      {error && <Notice tone="danger">{error}</Notice>}
      <div>
        <Button variant="secondary" onClick={() => setConfirming(true)}>
          {d.action}
        </Button>
      </div>
      {confirming && (
        <ConfirmDialog
          title={d.title}
          message={formatUser(d.text, { name: tariff.name })}
          confirmLabel={d.confirm}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: tariff.id })}
          onCancel={() => setConfirming(false)}
        />
      )}
    </>
  );
}

/** «Archive» for a used tariff (it cannot be deleted): opens the dialog asking for the day it stops applying. */
function ArchiveTariff({ tariff }: { tariff: Tariff }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <div>
        <Button variant="secondary" onClick={() => setOpen(true)}>
          {t.tariffs.archive.action}
        </Button>
      </div>
      {open && <ArchiveTariffDialog tariff={tariff} onClose={() => setOpen(false)} />}
    </>
  );
}

/** `/tariffs/:id`: the tariff, its versions (newest first) and, with TARIFFS_MANAGE, a form for a new version. */
export function TariffPage() {
  const { id = '' } = useParams();
  const t = useT();
  const { locale } = useLocale();
  const canManage = useCan('TARIFFS_MANAGE');
  const query = useGetTariff(id);
  useDocumentTitle(query.data?.name ?? t.tariffs.title);
  if (query.error instanceof ApiError && query.error.status === 404) return <NotFoundPage />;
  const c = t.tariffs.card;
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/tariffs" className="text-ink-muted hover:text-ink">
        {t.tariffs.back}
      </Link>
      <QueryBoundary query={query}>
        {(tariff) => {
          const archived = tariff.archivedAt !== undefined;
          return (
            <>
              <PageHeader
                title={<UserContent>{tariff.name}</UserContent>}
                description={
                  <>
                    {c.chargeType}: <ChargeTypeName type={tariff.chargeType} />
                  </>
                }
                badge={
                  archived ? (
                    <Badge tone="neutral">{t.tariffs.archivedBadge}</Badge>
                  ) : (
                    !tariff.chargeType.active && <Badge tone="neutral">{t.tariffs.inactive}</Badge>
                  )
                }
              />
              {archived && tariff.validUntil && (
                <Notice tone="warning">
                  {format(t.tariffs.validUntil, { date: formatDay(tariff.validUntil, locale) })}
                </Notice>
              )}
              <TariffConflicts conflicts={tariff.conflicts} />
              <ScopeEditor tariff={tariff} readOnly={archived} />
              <Panel title={c.history}>
                <VersionHistory tariff={tariff} />
              </Panel>
              {canManage && !archived && (
                <Panel title={c.newVersion}>
                  <NewVersionForm tariff={tariff} />
                </Panel>
              )}
              {canManage && !archived && tariff.deletable && <DeleteTariff tariff={tariff} />}
              {canManage && !archived && !tariff.deletable && <ArchiveTariff tariff={tariff} />}
            </>
          );
        }}
      </QueryBoundary>
    </div>
  );
}
