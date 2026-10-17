import { useT } from '../../i18n';
import { Button } from './Button';

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const t = useT();
  return (
    <div role="alert" className="flex flex-col items-start gap-3 p-6">
      <p className="text-ink-muted">{message}</p>
      <Button variant="secondary" onClick={onRetry}>
        {t.errors.retry}
      </Button>
    </div>
  );
}
