import { useListPlotOwnerships, type Ownership } from '@nadel/api-client';
import { useState } from 'react';
import { useCanEditOwnership } from '../../hooks/useCanEditOwnership';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Panel } from '../ui';
import { ChangeOwnerDialog } from './ChangeOwnerDialog';
import { CorrectOwnershipDialog } from './CorrectOwnershipDialog';
import { OwnershipLists } from './OwnershipLists';

/** Owners of a plot, with "Change owner" for people who may edit ownership. and "Correct" on each record. */
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
  const [correcting, setCorrecting] = useState<Ownership | null>(null);
  const [saved, setSaved] = useState<'change' | 'correct' | null>(null);
  const canChange = canEdit && canViewMembers && kind !== 'COMMON' && !archived;
  return (
    <Panel title={t.plot.ownersTitle}>
      {saved && (
        <Notice tone="success">
          {saved === 'change' ? t.ownership.changeSaved : t.ownership.correctSaved}
        </Notice>
      )}
      {canChange && (
        <div>
          <Button
            onClick={() => {
              setSaved(null);
              setChanging(true);
            }}
          >
            {t.ownership.changeOwner}
          </Button>
        </div>
      )}
      <QueryBoundary query={ownerships}>
        {(data) => (
          <>
            <OwnershipLists
              ownerships={data}
              onCorrect={
                canChange
                  ? (o) => {
                      setSaved(null);
                      setCorrecting(o);
                    }
                  : undefined
              }
            />
            {correcting && (
              <CorrectOwnershipDialog
                plotId={plotId}
                ownership={correcting}
                ownerships={data}
                onClose={() => setCorrecting(null)}
                onSaved={() => {
                  setCorrecting(null);
                  setSaved('correct');
                }}
              />
            )}
          </>
        )}
      </QueryBoundary>
      {changing && (
        <ChangeOwnerDialog
          plotId={plotId}
          onClose={() => setChanging(false)}
          onSaved={() => {
            setChanging(false);
            setSaved('change');
          }}
        />
      )}
    </Panel>
  );
}
