import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { setFormDirty } from './dirtyForms';
import { applyUpdate, canApplyUpdate, hasOidcCallback, registerPwa, resetPwaForTests } from './registerPwa';
import { UpdateBar } from './UpdateBar';

const registered: { onNeedRefresh?: () => void; update: ReturnType<typeof vi.fn> } = { update: vi.fn() };

vi.mock('virtual:pwa-register', () => ({
  registerSW: (options: { onNeedRefresh?: () => void }) => {
    registered.onNeedRefresh = options.onNeedRefresh;
    return registered.update;
  },
}));

beforeEach(() => {
  resetPwaForTests();
  registered.update = vi.fn().mockResolvedValue(undefined);
  setFormDirty('form', false);
  Object.defineProperty(navigator, 'serviceWorker', { value: {}, configurable: true });
});

describe('canApplyUpdate', () => {
  it('is blocked by a dirty form and by an OIDC callback in the address', () => {
    expect(canApplyUpdate(false, '')).toBe(true);
    expect(canApplyUpdate(true, '')).toBe(false);
    expect(canApplyUpdate(false, '?code=abc&state=xyz')).toBe(false);
    expect(hasOidcCallback('?state=xyz')).toBe(true);
    expect(hasOidcCallback('?street=Main')).toBe(false);
  });
});

describe('registerPwa', () => {
  it('does nothing outside production builds', async () => {
    await registerPwa(false);
    registered.onNeedRefresh?.();
    expect(registered.onNeedRefresh).toBeUndefined();
  });

  it('applies the update only when pressed, once, and never while a sign-in callback is in the address', async () => {
    await registerPwa(true);
    expect(await applyUpdate(false, '')).toBe(false); // nothing waiting yet
    act(() => registered.onNeedRefresh?.());
    expect(await applyUpdate(false, '?code=1&state=2')).toBe(false);
    expect(registered.update).not.toHaveBeenCalled();
    expect(await applyUpdate(true, '')).toBe(false);
    expect(await applyUpdate(false, '')).toBe(true);
    expect(registered.update).toHaveBeenCalledWith(true);
    expect(await applyUpdate(false, '')).toBe(false); // one reload only
    expect(registered.update).toHaveBeenCalledTimes(1);
  });
});

describe('UpdateBar', () => {
  it('shows the bar, disables «Update» with a hint while a form is dirty, and applies on press', async () => {
    await registerPwa(true);
    const user = userEvent.setup();
    renderWithApp(<UpdateBar />);
    expect(screen.queryByRole('status')).toBeNull();
    act(() => registered.onNeedRefresh?.());
    expect(screen.getByText('A new version is available')).toBeInTheDocument();
    act(() => setFormDirty('form', true));
    const button = screen.getByRole('button', { name: 'Update' });
    expect(button).toBeDisabled();
    expect(screen.getByText(/Finish or cancel your changes/)).toBeInTheDocument();
    act(() => setFormDirty('form', false));
    expect(screen.getByRole('button', { name: 'Update' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Update' }));
    expect(registered.update).toHaveBeenCalledWith(true);
  });
});

describe('UpdateBar during sign-in', () => {
  it('keeps «Update» disabled while the address carries code and state', async () => {
    await registerPwa(true);
    renderWithApp(<UpdateBar />, { route: '/?code=abc&state=xyz' });
    act(() => registered.onNeedRefresh?.());
    expect(screen.getByRole('button', { name: 'Update' })).toBeDisabled();
    expect(screen.getByText('Finish signing in to update.')).toBeInTheDocument();
  });
});
