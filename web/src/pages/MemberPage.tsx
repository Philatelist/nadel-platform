import { ApiError, useGetMember } from '@nadel/api-client';
import { Link, useParams } from 'react-router';
import { MemberDetails } from '../components/MemberDetails';
import { QueryBoundary } from '../components/QueryBoundary';
import { useT } from '../i18n';
import { NotFoundPage } from './NotFoundPage';

export function MemberPage() {
  const t = useT();
  const { id = '' } = useParams();
  const member = useGetMember(id);

  if (member.error instanceof ApiError && member.error.status === 404) return <NotFoundPage />;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-8">
      <Link to="/members" className="text-sm text-muted hover:text-ink">
        {t.members.back}
      </Link>
      <QueryBoundary query={member}>
        {(data) => (
          <>
            <h1 className="font-display text-2xl font-bold">
              {[data.lastName, data.firstName, data.middleName].filter(Boolean).join(' ')}
            </h1>
            {data.archived && <p className="text-sm text-muted">{t.members.archived}</p>}
            <MemberDetails member={data} />
          </>
        )}
      </QueryBoundary>
    </div>
  );
}
