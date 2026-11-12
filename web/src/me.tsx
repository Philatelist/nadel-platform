import { ApiError, useGetMe, type Me, type Permission } from '@nadel/api-client';
import { createContext, useContext, type ReactNode } from 'react';
import { FullPageMessage } from './components/FullPageMessage';
import { QueryBoundary } from './components/QueryBoundary';
import { Button } from './components/ui';
import { useT } from './i18n';
import { problemCode } from './problem';
import { useSignOut } from './pwa/useSignOut';

const MeContext = createContext<Me | null>(null);

export function MeProvider({ children }: { children: ReactNode }) {
  const t = useT();
  const signOutAndLeave = useSignOut();
  const query = useGetMe();
  const signOut = (
    <Button variant="secondary" onClick={() => void signOutAndLeave()}>
      {t.auth.signOut}
    </Button>
  );

  if (
    query.error instanceof ApiError &&
    query.error.status === 403 &&
    problemCode(query.error) === 'MEMBER_NOT_LINKED'
  ) {
    return (
      <FullPageMessage title={t.access.notLinkedTitle} message={t.problem.MEMBER_NOT_LINKED}>
        {signOut}
      </FullPageMessage>
    );
  }

  return (
    <QueryBoundary query={query} isEmpty={() => false}>
      {(me) =>
        me.platformOperator ? (
          <FullPageMessage title={t.access.noCooperativeTitle} message={t.access.noCooperative}>
            {signOut}
          </FullPageMessage>
        ) : (
          <MeContext value={me}>{children}</MeContext>
        )
      }
    </QueryBoundary>
  );
}

export function useMe(): Me {
  const me = useContext(MeContext);
  if (!me) throw new Error('useMe must be used inside MeProvider');
  return me;
}

/** Like `useMe`, but returns null outside `MeProvider` (sign-in and full-page messages). */
export function useOptionalMe(): Me | null {
  return useContext(MeContext);
}

export function useCan(permission: Permission): boolean {
  return useMe().permissions.includes(permission);
}
