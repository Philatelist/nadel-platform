import type { RoleDetails, RoleRef } from '@nadel/api-client';
import { useT } from '../i18n';
import { UserContent } from './UserContent';

/** A base role that was not renamed shows the catalog name; a renamed or custom role shows its name as user content. */
export function RoleName({ role }: { role: Pick<RoleRef, 'name' | 'baseCode' | 'nameCustomized'> }) {
  const t = useT();
  if (role.baseCode && !role.nameCustomized) return <>{t.roles[role.baseCode]}</>;
  return <UserContent>{role.name}</UserContent>;
}

/** Role names joined by ", ". */
export function RoleNameList({
  roles,
}: {
  roles: readonly Pick<RoleRef, 'name' | 'baseCode' | 'nameCustomized'>[];
}) {
  return (
    <>
      {roles.map((role, index) => (
        <span key={`${role.name}-${index}`}>
          {index > 0 && ', '}
          <RoleName role={role} />
        </span>
      ))}
    </>
  );
}

/** The standard description of an untouched base role comes from the catalog; otherwise it is user content. */
export function RoleDescription({
  role,
}: {
  role: Pick<RoleDetails, 'description' | 'baseCode' | 'descriptionCustomized'>;
}) {
  const t = useT();
  if (role.baseCode && !role.descriptionCustomized) return <>{t.roleDescriptions[role.baseCode]}</>;
  return role.description ? <UserContent>{role.description}</UserContent> : null;
}
