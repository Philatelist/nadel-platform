import { useAcceptInvitation, useInspectInvitation } from '@nadel/api-client';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useAuth } from 'react-oidc-context';
import { Button, Notice, Panel, TextField } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { problemCode, problemFieldErrors, problemMessage } from '../problem';

const MIN_LENGTH = 8;

type Phase = 'loading' | 'invalid' | 'form' | 'done';

/** Keeps the page's link token out of the Referer of anything it loads, while the page is shown. */
function useNoReferrer(): void {
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'referrer';
    meta.content = 'no-referrer';
    document.head.appendChild(meta);
    return () => meta.remove();
  }, []);
}

/** The public page of an invitation link: inspect the token, set the first password, go to sign-in. */
export function SetPasswordPage() {
  const t = useT();
  const { locale } = useLocale();
  const auth = useAuth();
  const c = t.setPassword;
  useNoReferrer();

  // the token comes from the address once; it is removed from the address bar and kept in memory
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token') ?? '');
  const [phase, setPhase] = useState<Phase>(token === '' ? 'invalid' : 'loading');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  const [formError, setFormError] = useState<string>();
  const inspected = useRef(false);

  const inspect = useInspectInvitation();
  const accept = useAcceptInvitation();

  useEffect(() => {
    if (window.location.search !== '') {
      window.history.replaceState(null, '', window.location.pathname);
    }
    if (token === '' || inspected.current) return;
    inspected.current = true;
    inspect.mutate(
      { data: { token } },
      {
        onSuccess: (info) => {
          setEmail(info.email);
          setPhase('form');
        },
        onError: () => setPhase('invalid'),
      },
    );
    // inspect once on mount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const longEnough = password.length >= MIN_LENGTH;
  const matches = password !== '' && password === confirm;

  const submit = (event: FormEvent): void => {
    event.preventDefault();
    setFieldError(undefined);
    setFormError(undefined);
    accept.mutate(
      { data: { token, password } },
      {
        onSuccess: () => setPhase('done'),
        onError: (error) => {
          if (problemCode(error) === 'INVITATION_INVALID') {
            setPhase('invalid');
            return;
          }
          const fields = problemFieldErrors(t, error);
          if (fields.password !== undefined) setFieldError(fields.password);
          else setFormError(problemMessage(t, error));
        },
      },
    );
  };

  return (
    <div className="mx-auto flex max-w-md flex-col gap-4">
      <h1 className="font-serif text-2xl font-semibold text-ink">{c.title}</h1>
      {phase === 'loading' && <p className="text-ink-muted">{c.loading}</p>}
      {phase === 'invalid' && <Notice tone="danger">{c.invalid}</Notice>}
      {phase === 'form' && (
        <Panel>
          <form className="flex flex-col gap-4" onSubmit={submit} noValidate>
            <TextField label={c.email} type="email" value={email} readOnly autoComplete="username" />
            <TextField
              label={c.password}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              {...(fieldError !== undefined ? { error: fieldError } : {})}
            />
            <TextField
              label={c.confirm}
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
            />
            <ul className="text-[13px]">
              <li className={longEnough ? 'text-success-ink' : 'text-ink-subtle'}>{c.hintLength}</li>
              {confirm !== '' && (
                <li className={matches ? 'text-success-ink' : 'text-danger'}>
                  {matches ? c.hintMatch : c.hintMismatch}
                </li>
              )}
            </ul>
            {formError && <Notice tone="danger">{formError}</Notice>}
            <div>
              <Button type="submit" disabled={accept.isPending || !longEnough || !matches}>
                {c.submit}
              </Button>
            </div>
          </form>
        </Panel>
      )}
      {phase === 'done' && (
        <Panel>
          <div className="flex flex-col gap-3">
            <p className="font-medium text-ink">{c.done}</p>
            <p className="text-ink-muted">{c.doneText}</p>
            <div>
              <Button onClick={() => void auth.signinRedirect({ extraQueryParams: { ui_locales: locale } })}>
                {c.signIn}
              </Button>
            </div>
          </div>
        </Panel>
      )}
    </div>
  );
}
