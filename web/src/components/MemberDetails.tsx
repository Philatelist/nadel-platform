import type { Member } from '@nadel/api-client';
import { useT } from '../i18n';
import type { ReactNode } from 'react';
import { RoleNameList } from './RoleName';
import { UserContent } from './UserContent';
import { Panel } from './ui';

/** Read-only member card. Optional fields (notes, messengers) appear only when the response has them. */
export function MemberDetails({ member, title, large }: { member: Member; title?: string; large?: boolean }) {
  const t = useT();
  const f = t.member.fields;
  const fullName = [member.lastName, member.firstName, member.middleName].filter(Boolean).join(' ');
  const userText = (value: string | undefined): ReactNode =>
    value ? <UserContent>{value}</UserContent> : f.none;
  const rows: [string, ReactNode][] = [
    [f.name, <UserContent>{fullName}</UserContent>],
    [f.phones, userText(member.phones.join(', '))],
    [f.email, userText(member.email)],
    [f.postalAddress, userText(member.postalAddress)],
    [f.deliveryChannel, t.channel[member.deliveryChannel]],
  ];
  if (member.telegramUsername) rows.push([f.telegramUsername, userText(member.telegramUsername)]);
  if (member.viberPhone) rows.push([f.viberPhone, userText(member.viberPhone)]);
  if (member.notes) rows.push([f.notes, userText(member.notes)]);
  rows.push([f.roles, member.roles.length > 0 ? <RoleNameList roles={member.roles} /> : f.none]);

  return (
    <Panel {...(title ? { title } : {})}>
      <dl
        className={`grid grid-cols-1 gap-x-6 gap-y-3 ${large ? 'text-base' : 'text-[15px]'} sm:grid-cols-[minmax(10rem,max-content)_1fr]`}
      >
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt
              className={`font-medium text-ink-muted sm:font-normal ${large ? 'text-base' : 'text-sm sm:text-[15px]'}`}
            >
              {label}
            </dt>
            <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{value}</dd>
          </div>
        ))}
      </dl>
    </Panel>
  );
}
