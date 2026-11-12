import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RESUME_THROTTLE_MS, refreshOnResume } from './refreshOnResume';

describe('refreshOnResume', () => {
  let client: QueryClient;
  let invalidate: ReturnType<typeof vi.spyOn>;
  let time: number;
  let stop: () => void;
  const sw = new EventTarget();

  beforeEach(() => {
    client = new QueryClient();
    invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
    time = 10_000;
    Object.defineProperty(navigator, 'serviceWorker', { value: sw, configurable: true });
    stop = refreshOnResume(client, () => time);
  });
  afterEach(() => stop());

  function visibility(state: 'visible' | 'hidden'): void {
    Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  }

  it('refetches active queries when the page becomes visible, not when it is hidden', () => {
    visibility('hidden');
    expect(invalidate).not.toHaveBeenCalled();
    visibility('visible');
    expect(invalidate).toHaveBeenCalledWith({ refetchType: 'active' });
  });

  it('refetches on focus and on pageshow from the back/forward cache only', () => {
    window.dispatchEvent(new Event('focus'));
    expect(invalidate).toHaveBeenCalledTimes(1);
    time += RESUME_THROTTLE_MS;
    window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted: false }));
    expect(invalidate).toHaveBeenCalledTimes(1);
    window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted: true }));
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it('counts visibility and focus fired together as one', () => {
    visibility('visible');
    window.dispatchEvent(new Event('focus'));
    expect(invalidate).toHaveBeenCalledTimes(1);
    time += RESUME_THROTTLE_MS;
    window.dispatchEvent(new Event('focus'));
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it('refetches on the worker message from a push click, even inside the throttle window', () => {
    window.dispatchEvent(new Event('focus'));
    sw.dispatchEvent(new MessageEvent('message', { data: { type: 'NADEL_REFRESH' } }));
    expect(invalidate).toHaveBeenCalledTimes(2);
    sw.dispatchEvent(new MessageEvent('message', { data: { type: 'OTHER' } }));
    expect(invalidate).toHaveBeenCalledTimes(2);
  });
});
