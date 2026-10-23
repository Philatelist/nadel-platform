import type { ImportResult } from '@nadel/api-client';
import { Link } from 'react-router';
import { buttonClass, Button, DataTable, Notice, Panel, type Column } from '../ui';
import { useT } from '../../i18n';
import { useCan } from '../../me';

const entities = ['plots', 'members', 'ownerships', 'meters', 'waterConnections', 'openingBalances'] as const;

export function ImportResultPanel({ result, onReset }: { result: ImportResult; onReset: () => void }) {
  const t = useT();
  const s = t.import;
  const canViewMembers = useCan('MEMBERS_VIEW');
  const rows = entities.map((key) => ({ key, label: s.entity[key], counts: result.counts[key] }));
  const columns: Column<(typeof rows)[number]>[] = [
    { key: 'entity', header: '', cell: (r) => r.label },
    ...(['created', 'updated', 'unchanged'] as const).map((k) => ({
      key: k,
      header: s[k],
      numeric: true,
      cell: (r: (typeof rows)[number]) => r.counts[k],
    })),
  ];
  return (
    <Panel title={s.resultTitle}>
      <div className="flex flex-col gap-4">
        <Notice tone="success">{s.resultTitle}</Notice>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.key}
          caption={s.resultCaption}
          emptyMessage=""
        />
        <div className="flex flex-wrap items-center gap-3">
          <Link to="/plots" className={buttonClass('primary')}>
            {s.viewPlots}
          </Link>
          {canViewMembers && (
            <Link to="/members" className={buttonClass('secondary')}>
              {s.viewMembers}
            </Link>
          )}
          <Button variant="secondary" onClick={onReset}>
            {s.chooseAnother}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
