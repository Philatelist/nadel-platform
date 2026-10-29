import {
  DeliveryChannel,
  getGetMyNotificationsQueryKey,
  getGetMemberQueryKey,
  useCreateMyTelegramLink,
  useDeleteMyTelegramLink,
  useGetMyNotifications,
  useUpdateMyNotifications,
  type MyNotifications,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useT } from '../../i18n';
import { useMe } from '../../me';
import { problemMessage } from '../../problem';
import { ConfirmDialog } from '../ConfirmDialog';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, RadioGroup } from '../ui';

/** "Notifications" on the account page: the member's invoice channel and the Telegram link (feature 017 §2.2, §2.3). */
export function NotificationsPanel() {
  const t = useT();
  const query = useGetMyNotifications();
  return (
    <section aria-labelledby="my-notifications" className="flex flex-col gap-3">
      <h2 id="my-notifications" className="font-serif text-lg font-semibold text-ink">
        {t.notifications.title}
      </h2>
      <QueryBoundary query={query}>
        {(data) => <NotificationsBody key={data.channel} data={data} />}
      </QueryBoundary>
    </section>
  );
}

function NotificationsBody({ data }: { data: MyNotifications }) {
  const t = useT();
  const queryClient = useQueryClient();
  const me = useMe();
  const [selected, setSelected] = useState(data.channel);
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string }>();
  const [confirming, setConfirming] = useState(false);

  // After the member opens the bot, the link appears on the server; pick it up when the tab is focused again.
  useEffect(() => {
    if (!data.botAvailable || data.telegramLinked) return;
    const refresh = () => void queryClient.invalidateQueries({ queryKey: getGetMyNotificationsQueryKey() });
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [data.botAvailable, data.telegramLinked, queryClient]);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: getGetMyNotificationsQueryKey() });
    if (me.memberId) void queryClient.invalidateQueries({ queryKey: getGetMemberQueryKey(me.memberId) });
  }
  const fail = (error: unknown) => setMessage({ tone: 'danger', text: problemMessage(t, error) });

  const save = useUpdateMyNotifications({
    mutation: {
      onSuccess: () => {
        setMessage({ tone: 'success', text: t.notifications.saved });
        refresh();
      },
      onError: fail,
    },
  });
  const connect = useCreateMyTelegramLink({
    mutation: {
      onSuccess: (offer) => {
        setMessage(undefined);
        window.open(offer.deepLink, '_blank', 'noopener');
      },
      onError: fail,
    },
  });
  const disconnect = useDeleteMyTelegramLink({
    mutation: {
      onSuccess: () => {
        setConfirming(false);
        setMessage(undefined);
        refresh();
      },
      onError: (error) => {
        setConfirming(false);
        fail(error);
      },
    },
  });

  const options = data.available.map((value) => ({ value, label: t.channel[value] }));
  const viberOnly = data.channel === DeliveryChannel.VIBER && !data.available.includes(DeliveryChannel.VIBER);

  return (
    <div className="flex max-w-xl flex-col gap-4 rounded-control border border-line bg-surface p-4">
      {message && <Notice tone={message.tone}>{message.text}</Notice>}
      {viberOnly && <Notice tone="warning">{t.notifications.viberUnsupported}</Notice>}
      <RadioGroup
        legend={t.notifications.channelLegend}
        options={options}
        value={selected}
        onChange={setSelected}
      />
      <div>
        <Button
          disabled={save.isPending || selected === data.channel || !data.available.includes(selected)}
          onClick={() => {
            setMessage(undefined);
            save.mutate({ data: { channel: selected } });
          }}
        >
          {t.notifications.save}
        </Button>
      </div>

      {data.botAvailable && (
        <div className="flex flex-col gap-2 border-t border-line-subtle pt-4">
          <h3 className="font-medium text-ink">{t.telegramLink.title}</h3>
          {data.telegramLinked ? (
            <>
              <p>{t.telegramLink.connected}</p>
              <div>
                <Button variant="secondary" onClick={() => setConfirming(true)}>
                  {t.telegramLink.disconnect}
                </Button>
              </div>
            </>
          ) : (
            <>
              <p className="text-sm text-ink-muted">{t.telegramLink.hint}</p>
              <div>
                <Button disabled={connect.isPending} onClick={() => connect.mutate()}>
                  {t.telegramLink.connect}
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      {confirming && (
        <ConfirmDialog
          title={t.telegramLink.disconnectTitle}
          message={t.telegramLink.disconnectMessage}
          confirmLabel={t.telegramLink.disconnect}
          busy={disconnect.isPending}
          onConfirm={() => disconnect.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
    </div>
  );
}
