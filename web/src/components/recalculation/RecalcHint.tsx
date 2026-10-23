import { Link } from 'react-router';
import { useT } from '../../i18n';
import { useCan } from '../../me';

/** A reminder on a screen whose change does not touch past charges; only for those who may recalculate. */
export function RecalcHint() {
  const t = useT();
  const canRecalculate = useCan('RECALCULATION_RUN');
  if (!canRecalculate) return null;
  const h = t.recalculation.hint;
  const [before, after] = h.text.split(h.link);
  return (
    <p className="text-sm text-ink-muted">
      {before}
      <Link to="/charges/recalculations/new" className="text-accent underline">
        {h.link}
      </Link>
      {after}
    </p>
  );
}
