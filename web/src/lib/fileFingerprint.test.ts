import { afterEach, describe, expect, it, vi } from 'vitest';
import { fileFingerprint } from './fileFingerprint';

describe('fileFingerprint', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('gives the same hash for the same name, size and content', async () => {
    const a = await fileFingerprint(new File(['abc'], 'a.png', { type: 'image/png' }));
    const b = await fileFingerprint(new File(['abc'], 'a.png', { type: 'image/png' }));
    expect(a).toMatch(/^[0-9a-f]{64}$/);
    expect(a).toBe(b);
  });

  it('gives a different hash for different content or name', async () => {
    const base = await fileFingerprint(new File(['abc'], 'a.png'));
    expect(await fileFingerprint(new File(['abd'], 'a.png'))).not.toBe(base);
    expect(await fileFingerprint(new File(['abc'], 'b.png'))).not.toBe(base);
  });

  it('returns null without crypto.subtle', async () => {
    vi.stubGlobal('crypto', {});
    expect(await fileFingerprint(new File(['abc'], 'a.png'))).toBeNull();
  });
});
