import { useNavigate } from 'react-router';
import { Link } from 'react-router';
import { MemberForm } from '../components/MemberForm';
import { PageHeader } from '../components/ui';
import { useT } from '../i18n';

/** `/members/new`: create a member, then open the new card with a "Saved" notice. */
export function MemberFormPage() {
  const t = useT();
  const navigate = useNavigate();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/members" className="text-ink-muted hover:text-ink">
        {t.members.back}
      </Link>
      <PageHeader title={t.members.addTitle} />
      <MemberForm
        onSaved={(saved) => void navigate(`/members/${saved.id}`, { state: { saved: true } })}
        onCancel={() => void navigate('/members')}
      />
    </div>
  );
}
