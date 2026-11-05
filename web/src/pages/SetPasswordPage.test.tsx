import { ApiError } from '@nadel/api-client';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { SetPasswordPage } from './SetPasswordPage';

const signinRedirect = vi.fn();
const inspect = vi.fn();
const accept = vi.fn();

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ signinRedirect }) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useInspectInvitation: () => ({ mutate: inspect, isPending: false }),
  useAcceptInvitation: () => ({ mutate: accept, isPending: false }),
}));

function open(search: string): void {
  window.history.replaceState(null, '', `/set-password${search}`);
}
const password = () => screen.getByLabelText('Password');
const confirm = () => screen.getByLabelText('Repeat the password');

describe('SetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    inspect.mockImplementation((_v, o) => o.onSuccess({ email: 'new@e2e.local' }));
  });

  it('shows the invalid text without a token and does not call the API', () => {
    open('');
    renderWithApp(<SetPasswordPage />);
    expect(screen.getByText(/The link is invalid or has expired/)).toBeInTheDocument();
    expect(inspect).not.toHaveBeenCalled();
  });

  it('shows the invalid text for a token the server refuses', () => {
    open('?token=bad');
    inspect.mockImplementation((_v, o) => o.onError(new ApiError(410, { code: 'INVITATION_INVALID' })));
    renderWithApp(<SetPasswordPage />);
    expect(screen.getByText(/The link is invalid or has expired/)).toBeInTheDocument();
  });

  it('inspects the token, then removes it from the address', () => {
    open('?token=abc');
    renderWithApp(<SetPasswordPage />);
    expect(inspect.mock.calls[0]?.[0]).toEqual({ data: { token: 'abc' } });
    expect(window.location.search).toBe('');
    expect(screen.getByLabelText('Login (e-mail)')).toHaveValue('new@e2e.local');
    expect(screen.getByLabelText('Login (e-mail)')).toHaveAttribute('readonly');
  });

  it('keeps Save disabled until the password is long enough and repeated', () => {
    open('?token=abc');
    renderWithApp(<SetPasswordPage />);
    const save = screen.getByRole('button', { name: 'Save password' });
    expect(save).toBeDisabled();
    fireEvent.change(password(), { target: { value: 'short' } });
    fireEvent.change(confirm(), { target: { value: 'short' } });
    expect(save).toBeDisabled();
    fireEvent.change(password(), { target: { value: 'long-enough-1' } });
    expect(save).toBeDisabled();
    fireEvent.change(confirm(), { target: { value: 'long-enough-1' } });
    expect(save).toBeEnabled();
  });

  it('sets the password and offers sign-in', () => {
    open('?token=abc');
    accept.mockImplementation((_v, o) => o.onSuccess());
    renderWithApp(<SetPasswordPage />);
    fireEvent.change(password(), { target: { value: 'long-enough-1' } });
    fireEvent.change(confirm(), { target: { value: 'long-enough-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));
    expect(accept.mock.calls[0]?.[0]).toEqual({ data: { token: 'abc', password: 'long-enough-1' } });
    expect(screen.getByText('Password set')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(signinRedirect).toHaveBeenCalledWith({ extraQueryParams: { ui_locales: 'en' } });
  });

  it('maps the server field errors onto the password field', async () => {
    open('?token=abc');
    accept.mockImplementation((_v, o) =>
      o.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'password', code: 'PASSWORD_POLICY' }],
        }),
      ),
    );
    renderWithApp(<SetPasswordPage />);
    fireEvent.change(password(), { target: { value: 'password1' } });
    fireEvent.change(confirm(), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));
    await waitFor(() =>
      expect(
        screen.getByText('This password is not accepted. Choose a longer or less obvious one.'),
      ).toBeInTheDocument(),
    );
  });

  it('maps TOO_SHORT and switches to the invalid text when the link was used up meanwhile', async () => {
    open('?token=abc');
    accept.mockImplementationOnce((_v, o) =>
      o.onError(
        new ApiError(400, { code: 'VALIDATION_FAILED', errors: [{ field: 'password', code: 'TOO_SHORT' }] }),
      ),
    );
    renderWithApp(<SetPasswordPage />);
    fireEvent.change(password(), { target: { value: 'long-enough-1' } });
    fireEvent.change(confirm(), { target: { value: 'long-enough-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));
    await waitFor(() =>
      expect(screen.getByText('The password must be at least 8 characters.')).toBeInTheDocument(),
    );
    accept.mockImplementationOnce((_v, o) => o.onError(new ApiError(410, { code: 'INVITATION_INVALID' })));
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));
    await waitFor(() => expect(screen.getByText(/The link is invalid or has expired/)).toBeInTheDocument());
  });
});
