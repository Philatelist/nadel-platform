import { useListMembers } from '@nadel/api-client';
import { Link } from 'react-router';
import { useState } from 'react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { UserContent } from '../UserContent';
import { Button, SearchField } from '../ui';

export interface PickedMember {
  id: string;
  name: string;
}

const DEBOUNCE_MS = 300;

/** Picks one active member by a debounced name search; members chosen in other rows are left out. */
export function MemberPicker({
  label,
  value,
  excludeIds,
  error,
  onChange,
}: {
  label: string;
  value: PickedMember | null;
  excludeIds: readonly string[];
  error?: string;
  onChange: (member: PickedMember | null) => void;
}) {
  const t = useT();
  const canAdd = useCan('MEMBERS_EDIT');
  const [text, setText] = useState('');
  const q = useDebouncedValue(text.trim(), DEBOUNCE_MS);
  const search = useListMembers({ q: q || undefined, size: 10 }, { query: { enabled: value === null } });
  const results = (search.data?.items ?? []).filter((m) => !m.archived && !excludeIds.includes(m.id));

  if (value) {
    return (
      <div className="flex flex-col gap-1">
        <span className="text-sm font-medium text-ink-muted">{label}</span>
        <div className="flex items-center gap-3">
          <span className="font-medium">
            <UserContent>{value.name}</UserContent>
          </span>
          <Button variant="secondary" onClick={() => onChange(null)}>
            {t.ownership.changeMember}
          </Button>
        </div>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      <SearchField
        label={label}
        placeholder={t.ownership.searchMember}
        value={text}
        error={error}
        onChange={(e) => setText(e.target.value)}
      />
      {search.data && results.length === 0 && (
        <p className="text-sm text-ink-muted">{t.ownership.noMembersFound}</p>
      )}
      {results.length > 0 && (
        <ul className="flex flex-col rounded-control border border-line">
          {results.map((m) => {
            const name = [m.lastName, m.firstName, m.middleName].filter(Boolean).join(' ');
            return (
              <li key={m.id}>
                <button
                  type="button"
                  onClick={() => onChange({ id: m.id, name })}
                  className="min-h-11 w-full px-3 text-left hover:bg-accent-soft"
                >
                  <UserContent>{name}</UserContent>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {canAdd && (
        <Link to="/members/new" target="_blank" rel="noopener" className="text-sm text-accent underline">
          {t.ownership.addMember}
        </Link>
      )}
    </div>
  );
}
