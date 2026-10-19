import { useListPlotOwnerships } from '@nadel/api-client';
import { useState } from 'react';
import { useCanEditOwnership } from '../../hooks/useCanEditOwnership';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Panel } from '../ui';
import { ChangeOwnerDialog } from './ChangeOwnerDialog';
import { OwnershipLists } from './OwnershipLists';

/** Owners of a plot, with "Change owner" for people who may edit ownership. Correct arrives in a later slice. */
export function OwnersPanel({
  plotId,
  kind = 'PLOT',
  archived = false,
}: {
  plotId: string;
  kind?: 'PLOT' | 'COMMON';
  archived?: boolean;
}) {
  const t = useT();
  const ownerships = useListPlotOwnerships(plotId);
  const canEdit = useCanEditOwnership();
  const canViewMembers = useCan('MEMBERS_VIEW');
  const [changing, setChanging] = useState(false);
  const [saved, setSaved] = useState(false);
  const canChange = canEdit && canViewMembers && kind !== 'COMMON' && !archived;
  return (
    <Panel title={t.plot.ownersTitle}>
      {saved && <Notice tone="success">{t.ownership.changeSaved}</Notice>}
      {canChange && (
        <div>
          <Button
            onClick={() => {
              setSaved(false);
              setChanging(true);
            }}
          >
            {t.ownership.changeOwner}
          </Button>
        </div>
      )}
      <QueryBoundary query={ownerships}>{(data) => <OwnershipLists ownerships={data} />}</QueryBoundary>
      {changing && (
        <ChangeOwnerDialog
          plotId={plotId}
          onClose={() => setChanging(false)}
          onSaved={() => {
            setChanging(false);
            setSaved(true);
          }}
        />
      )}
    </Panel>
  );
}
