import { describe, expect, it } from 'vitest';
import { installOffer, isIosSafari, type InstallContext } from './installPrompt';

const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1';
const CHROME_IOS =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/124.0 Mobile/15E148 Safari/604.1';
const CHROME_ANDROID =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';

const base: InstallContext = {
  standalone: false,
  dismissed: false,
  hasPrompt: false,
  userAgent: CHROME_ANDROID,
};

describe('installOffer', () => {
  it('offers the browser prompt once the browser allowed installing', () => {
    expect(installOffer({ ...base, hasPrompt: true })).toBe('prompt');
    expect(installOffer(base)).toBe('hidden');
  });

  it('shows the steps only in Safari on iPhone and iPad', () => {
    expect(installOffer({ ...base, userAgent: SAFARI_IPHONE })).toBe('ios');
    expect(installOffer({ ...base, userAgent: CHROME_IOS })).toBe('hidden');
    expect(
      isIosSafari('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit Version/17 Safari/605', 5),
    ).toBe(true);
    expect(
      isIosSafari('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit Version/17 Safari/605', 0),
    ).toBe(false);
  });

  it('shows nothing when installed or dismissed', () => {
    expect(installOffer({ ...base, hasPrompt: true, standalone: true })).toBe('hidden');
    expect(installOffer({ ...base, userAgent: SAFARI_IPHONE, standalone: true })).toBe('hidden');
    expect(installOffer({ ...base, hasPrompt: true, dismissed: true })).toBe('hidden');
    expect(installOffer({ ...base, userAgent: SAFARI_IPHONE, dismissed: true })).toBe('hidden');
  });
});
