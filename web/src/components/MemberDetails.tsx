import type { Member } from '@nadel/api-client';
import { useT } from '../i18n';
import { useRoleName } from '../roleName';

/** Read-only member card. Optional fields (notes, messengers) appear only when the response has them. */
export function MemberDetails({ member }: { member: Member }) {
  const t = useT();
  const f = t.member.fields;
  const roleName = useRoleName();
  const fullName = [member.lastName, member.firstName, member.middleName].filter(Boolean).join(' ');
  const rows: [string, string][] = [
    [f.name, fullName],
    [f.phones, member.phones.join(', ') || f.none],
    [f.email, member.email ?? f.none],
    [f.postalAddress, member.postalAddress ?? f.none],
    [f.deliveryChannel, t.channel[member.deliveryChannel]],
  ];
  if (member.telegramUsername) rows.push([f.telegramUsername, member.telegramUsername]);
  if (member.viberPhone) rows.push([f.viberPhone, member.viberPhone]);
  if (member.notes) rows.push([f.notes, member.notes]);
  rows.push([f.roles, member.roles.map(roleName).join(', ') || f.none]);

  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-3 rounded-xl border border-line bg-surface p-5">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted">{label}</dt>
          <dd className="break-words whitespace-pre-line">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
