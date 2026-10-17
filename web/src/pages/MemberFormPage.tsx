import { useNavigate } from 'react-router';
import { Link } from 'react-router';
import { MemberForm } from '../components/MemberForm';
import { useT } from '../i18n';

/** `/members/new`: create a member, then open the new card with a "Saved" notice. */
export function MemberFormPage() {
  const t = useT();
  const navigate = useNavigate();
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-8">
      <Link to="/members" className="text-sm text-muted hover:text-ink">
        {t.members.back}
      </Link>
      <h1 className="font-display text-2xl font-bold">{t.members.addTitle}</h1>
      <MemberForm
        onSaved={(saved) => void navigate(`/members/${saved.id}`, { state: { saved: true } })}
        onCancel={() => void navigate('/members')}
      />
    </div>
  );
}
