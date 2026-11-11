import { useEffect, useRef, useState } from 'react';
import { useT } from '../../i18n';
import { Button } from '../ui';

const COPIED_MS = 2000;

/** Copies through a hidden textarea and `execCommand('copy')` for browsers without the async clipboard API. */
function legacyCopy(value: string): boolean {
  const area = document.createElement('textarea');
  area.value = value;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try {
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    document.body.removeChild(area);
  }
}

async function copyText(value: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(value);
    return true;
  } catch {
    return legacyCopy(value);
  }
}

/**
 * A button that copies `value` as given. On success «Copied» is announced in a live region for about two seconds;
 * when both copy methods fail the value is shown in a selectable read-only field. The value is never logged.
 */
export function CopyButton({ value, label }: { value: string; label: string }) {
  const t = useT();
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const copy = async () => {
    clearTimeout(timer.current);
    const ok = await copyText(value);
    setState(ok ? 'copied' : 'failed');
    if (ok) timer.current = setTimeout(() => setState('idle'), COPIED_MS);
  };
  return (
    <div className="flex flex-col gap-1">
      <Button variant="secondary" className="w-full sm:w-auto" onClick={() => void copy()}>
        {label}
      </Button>
      <div role="status" className="text-sm text-ink-muted">
        {state === 'copied' ? t.invoices.pay.copied : ''}
      </div>
      {state === 'failed' && (
        <div className="flex flex-col gap-1">
          <span className="text-sm text-danger">{t.invoices.pay.copyFailed}</span>
          <input
            readOnly
            aria-label={label}
            value={value}
            onFocus={(event) => event.currentTarget.select()}
            className="min-h-11 rounded-control border border-line-strong bg-surface px-3 font-mono text-sm"
          />
        </div>
      )}
    </div>
  );
}
