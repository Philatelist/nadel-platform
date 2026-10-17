import { ApiError, useGetMe, type Me, type Permission } from '@nadel/api-client';
import { createContext, useContext, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { FullPageMessage } from './components/FullPageMessage';
import { QueryBoundary } from './components/QueryBoundary';
import { useT } from './i18n';
import { problemCode } from './problem';

const MeContext = createContext<Me | null>(null);

export function MeProvider({ children }: { children: ReactNode }) {
  const t = useT();
  const auth = useAuth();
  const query = useGetMe();
  const signOut = (
    <button
      type="button"
      onClick={() => void auth.signoutRedirect()}
      className="rounded-md border border-line px-3 py-1.5 text-sm"
    >
      {t.auth.signOut}
    </button>
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

export function useCan(permission: Permission): boolean {
  return useMe().permissions.includes(permission);
}
