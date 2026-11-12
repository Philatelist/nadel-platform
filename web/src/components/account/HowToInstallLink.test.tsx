import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { HowToInstallLink } from './HowToInstallLink';

vi.mock('../../help/helpOrigin', () => ({ useHelpOrigin: () => undefined, originState: () => undefined }));

function mockDisplayMode(standalone: boolean): void {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: standalone && query === '(display-mode: standalone)',
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

describe('HowToInstallLink', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'standalone');
  });

  it('links to the install help in a browser tab', () => {
    mockDisplayMode(false);
    renderWithApp(<HowToInstallLink />);
    expect(screen.getByRole('link', { name: 'How to install on your phone' })).toHaveAttribute(
      'href',
      '/help/member-install',
    );
  });

  it('is hidden in the installed app (display-mode standalone)', () => {
    mockDisplayMode(true);
    renderWithApp(<HowToInstallLink />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('is hidden in the iOS home-screen app (navigator.standalone)', () => {
    mockDisplayMode(false);
    Object.defineProperty(navigator, 'standalone', { value: true, configurable: true });
    renderWithApp(<HowToInstallLink />);
    expect(screen.queryByRole('link')).toBeNull();
  });
});
