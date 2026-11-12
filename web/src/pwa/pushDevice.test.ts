import type { PushDevice } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEVICE_KEY,
  blockingState,
  decodeKey,
  deviceLabel,
  reconcile,
  signOutCleanup,
  turnOff,
  turnOn,
} from './pushDevice';

const KEY = 'AQIDBA'; // bytes 1,2,3,4
const device = (id: string): PushDevice => ({ id, createdAt: '2026-01-01T00:00:00Z' });

let subscription: {
  unsubscribe: ReturnType<typeof vi.fn>;
  options: { applicationServerKey: ArrayBuffer | null };
  endpoint: string;
  toJSON: () => unknown;
} | null;
let permission: NotificationPermission;
const subscribe = vi.fn();

function install(opts: { registration?: boolean; ua?: string } = {}) {
  vi.stubGlobal('Notification', {
    get permission() {
      return permission;
    },
    requestPermission: vi.fn(async () => permission),
  });
  vi.stubGlobal('PushManager', class {});
  const registration = {
    pushManager: { getSubscription: async () => subscription, subscribe },
  };
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      getRegistration: async () => (opts.registration === false ? undefined : registration),
      ready: Promise.resolve(registration),
    },
  });
  Object.defineProperty(navigator, 'userAgent', {
    configurable: true,
    value: opts.ua ?? 'Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36',
  });
}

function makeSub(key: Uint8Array | null) {
  return {
    unsubscribe: vi.fn(async () => true),
    options: { applicationServerKey: key ? (key.buffer as ArrayBuffer) : null },
    endpoint: 'https://push.example/abc',
    toJSON: () => ({ keys: { p256dh: 'pk', auth: 'ak' } }),
  };
}

beforeEach(() => {
  localStorage.clear();
  subscription = null;
  permission = 'default';
  subscribe.mockReset();
  install();
});

describe('states', () => {
  it('is unsupported without a worker registration (dev server)', async () => {
    install({ registration: false });
    expect(await reconcile(KEY, [])).toBe('unsupported');
  });

  it('is unsupported without the Push API', () => {
    vi.stubGlobal('PushManager', undefined);
    expect(blockingState()).toBe('unsupported');
  });

  it('asks an iPhone outside the installed app to install first', () => {
    install({ ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1' });
    expect(blockingState()).toBe('ios-needs-install');
  });

  it('is denied when the permission is blocked and there is no subscription', async () => {
    permission = 'denied';
    expect(await reconcile(KEY, [])).toBe('denied');
  });

  it('is off without a subscription and on with a matching one the server knows', async () => {
    expect(await reconcile(KEY, [])).toBe('off');
    subscription = makeSub(decodeKey(KEY));
    localStorage.setItem(DEVICE_KEY, 'd1');
    expect(await reconcile(KEY, [device('d1')])).toBe('on');
  });
});

describe('reconcile', () => {
  it('drops a subscription made with another server key', async () => {
    subscription = makeSub(new Uint8Array([9, 9, 9, 9]));
    localStorage.setItem(DEVICE_KEY, 'd1');
    expect(await reconcile(KEY, [device('d1')])).toBe('off');
    expect(subscription.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(DEVICE_KEY)).toBeNull();
  });

  it('drops the local subscription when the server no longer lists the device', async () => {
    subscription = makeSub(decodeKey(KEY));
    localStorage.setItem(DEVICE_KEY, 'gone');
    expect(await reconcile(KEY, [device('other')])).toBe('off');
    expect(subscription.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(DEVICE_KEY)).toBeNull();
  });

  it('keeps the state when the device list could not be read', async () => {
    subscription = makeSub(decodeKey(KEY));
    localStorage.setItem(DEVICE_KEY, 'd1');
    expect(await reconcile(KEY, null)).toBe('on');
  });
});

describe('turn on and off', () => {
  it('asks the permission, subscribes with the server key, registers and stores the id', async () => {
    permission = 'granted';
    const sub = makeSub(null);
    subscribe.mockResolvedValue(sub);
    const register = vi.fn().mockResolvedValue(device('d7'));
    expect(await turnOn({ register, remove: vi.fn() }, KEY, 'en')).toBe('on');
    expect(subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: decodeKey(KEY) });
    expect(register).toHaveBeenCalledWith({
      endpoint: 'https://push.example/abc',
      keys: { p256dh: 'pk', auth: 'ak' },
      label: 'Android · Chrome',
      language: 'en',
    });
    expect(localStorage.getItem(DEVICE_KEY)).toBe('d7');
  });

  it('does not subscribe when the permission is denied', async () => {
    permission = 'denied';
    expect(await turnOn({ register: vi.fn(), remove: vi.fn() }, KEY, 'uk')).toBe('denied');
    expect(subscribe).not.toHaveBeenCalled();
  });

  it('unsubscribes again when the server refuses', async () => {
    permission = 'granted';
    const sub = makeSub(null);
    subscribe.mockResolvedValue(sub);
    await expect(
      turnOn({ register: vi.fn().mockRejectedValue(new Error('409')), remove: vi.fn() }, KEY, 'uk'),
    ).rejects.toThrow();
    expect(sub.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(DEVICE_KEY)).toBeNull();
  });

  it('turns off: server first, then the browser, then clears the id', async () => {
    const order: string[] = [];
    subscription = makeSub(decodeKey(KEY));
    subscription.unsubscribe = vi.fn(async () => {
      order.push('unsubscribe');
      return true;
    });
    localStorage.setItem(DEVICE_KEY, 'd1');
    const remove = vi.fn(async () => {
      order.push('delete');
    });
    expect(await turnOff({ register: vi.fn(), remove })).toBe('off');
    expect(order).toEqual(['delete', 'unsubscribe']);
    expect(localStorage.getItem(DEVICE_KEY)).toBeNull();
  });
});

describe('signOutCleanup', () => {
  it('deletes the device before unsubscribing and never throws', async () => {
    const order: string[] = [];
    subscription = makeSub(decodeKey(KEY));
    subscription.unsubscribe = vi.fn(async () => {
      order.push('unsubscribe');
      return true;
    });
    localStorage.setItem(DEVICE_KEY, 'd1');
    await signOutCleanup(async () => {
      order.push('delete');
      throw new Error('offline');
    });
    expect(order).toEqual(['delete', 'unsubscribe']);
    expect(localStorage.getItem(DEVICE_KEY)).toBeNull();
  });

  it('does nothing without a stored device', async () => {
    const remove = vi.fn();
    await signOutCleanup(remove);
    expect(remove).not.toHaveBeenCalled();
  });
});

describe('deviceLabel', () => {
  it('is short and readable', () => {
    expect(deviceLabel('Mozilla/5.0 (Windows NT 10.0) Chrome/120 Edg/120')).toBe('Windows · Edge');
  });
});
