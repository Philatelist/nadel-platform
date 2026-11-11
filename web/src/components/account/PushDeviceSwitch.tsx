import {
  deleteMyPushDevice,
  getGetMyPushConfigQueryKey,
  getListMyPushDevicesQueryKey,
  listMyPushDevices,
  registerMyPushDevice,
  useGetMyPushConfig,
  type PushDevice,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { reconcile, turnOff, turnOn, type PushApi, type PushState } from '../../pwa/pushDevice';
import { Notice } from '../ui';

const api: PushApi = {
  register: (input) => registerMyPushDevice(input),
  remove: (id) => deleteMyPushDevice(id),
};

async function loadDevices(): Promise<PushDevice[] | null> {
  try {
    return await listMyPushDevices();
  } catch {
    return null;
  }
}

/** «Notifications on this device»: shown only when the server has push configured. */
export function PushDeviceSwitch() {
  const config = useGetMyPushConfig();
  if (!config.data?.enabled || !config.data.publicKey) return null;
  return <PushSwitch publicKey={config.data.publicKey} />;
}

function PushSwitch({ publicKey }: { publicKey: string }) {
  const t = useT();
  const { locale } = useLocale();
  const queryClient = useQueryClient();
  const [state, setState] = useState<PushState>('busy');
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    void (async () => {
      const devices = await loadDevices();
      const next = await reconcile(publicKey, devices).catch((): PushState => 'unsupported');
      if (active) setState(next);
    })();
    return () => {
      active = false;
    };
  }, [publicKey]);

  const toggle = useCallback(async () => {
    const wasOn = state === 'on';
    setError(undefined);
    setState('busy');
    try {
      setState(wasOn ? await turnOff(api) : await turnOn(api, publicKey, locale));
    } catch (e: unknown) {
      setError(
        problemMessage(t, e) === t.problem.UNKNOWN ? t.notifications.push.error : problemMessage(t, e),
      );
      setState(wasOn ? 'on' : 'off');
    }
    void queryClient.invalidateQueries({ queryKey: getListMyPushDevicesQueryKey() });
    void queryClient.invalidateQueries({ queryKey: getGetMyPushConfigQueryKey() });
  }, [state, publicKey, locale, t, queryClient]);

  return (
    <div className="flex flex-col gap-2 border-t border-line-subtle pt-4">
      <h3 className="font-medium text-ink">{t.notifications.push.title}</h3>
      {error && <Notice tone="danger">{error}</Notice>}
      <PushBody state={state} onToggle={() => void toggle()} />
    </div>
  );
}

function PushBody({ state, onToggle }: { state: PushState; onToggle: () => void }) {
  const t = useT();
  const text = t.notifications.push;
  switch (state) {
    case 'unsupported':
      return <p className="text-sm text-ink-muted">{text.unsupported}</p>;
    case 'ios-needs-install':
      return <p className="text-sm text-ink-muted">{text.iosNeedsInstall}</p>;
    case 'denied':
      return (
        <>
          <Switch label={text.title} checked={false} disabled onToggle={onToggle} />
          <p className="text-sm text-ink-muted">{text.blocked}</p>
        </>
      );
    case 'off':
    case 'on':
    case 'busy':
      return (
        <>
          <Switch
            label={text.title}
            checked={state === 'on'}
            disabled={state === 'busy'}
            onToggle={onToggle}
          />
          <p className="text-sm text-ink-muted">{text.hint}</p>
        </>
      );
    default: {
      const unreachable: never = state;
      return unreachable;
    }
  }
}

function Switch(props: { label: string; checked: boolean; disabled: boolean; onToggle: () => void }) {
  return (
    <label className="flex items-center gap-2">
      <input
        type="checkbox"
        role="switch"
        className="size-5"
        checked={props.checked}
        disabled={props.disabled}
        onChange={props.onToggle}
      />
      <span>{props.label}</span>
    </label>
  );
}
