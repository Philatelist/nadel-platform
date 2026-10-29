import {
  useListPlotStreets,
  useSendAnnouncement,
  type AnnouncementChannel,
  type AnnouncementRequest,
  type AnnouncementSendResult,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { UserContent } from '../components/UserContent';
import { Button, Notice, PageHeader, Panel, RadioGroup, TextArea } from '../components/ui';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const MAX_CHARACTERS = 2000;

type Audience = 'ALL' | 'STREETS';

/** Characters as the server counts them: Unicode code points of the trimmed text. */
export function countCharacters(text: string): number {
  return [...text.trim()].length;
}

/** Compose an announcement; it can be sent only after a preview of exactly what is on the form. */
export function AnnouncementFormPage() {
  const t = useT();
  const a = t.announcements;
  const f = a.form;
  useDocumentTitle(f.title);
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const [audience, setAudience] = useState<Audience>('ALL');
  const [streets, setStreets] = useState<readonly string[]>([]);
  const [telegram, setTelegram] = useState(true);
  const [email, setEmail] = useState(true);
  const [previewed, setPreviewed] = useState<{ key: string; result: AnnouncementSendResult }>();
  const [error, setError] = useState<unknown>();
  const streetList = useListPlotStreets();
  const send = useSendAnnouncement();

  const channels: AnnouncementChannel[] = [
    ...(telegram ? (['TELEGRAM'] as const) : []),
    ...(email ? (['EMAIL'] as const) : []),
  ];
  const chosenStreets = audience === 'STREETS' ? streets : [];
  const body: AnnouncementRequest = { text, streets: [...chosenStreets], channels };
  const key = JSON.stringify(body);
  const length = countCharacters(text);
  const tooLong = length > MAX_CHARACTERS;
  const blank = length === 0;
  const noStreets = audience === 'STREETS' && streets.length === 0;
  const valid = !tooLong && !blank && channels.length > 0 && !noStreets;
  const preview = previewed?.key === key ? previewed.result : undefined;

  const edit = () => setError(undefined);
  const busy = send.isPending;

  function runPreview() {
    setError(undefined);
    send.mutate(
      { data: body, params: { preview: true } },
      {
        onSuccess: (result) => setPreviewed({ key, result }),
        onError: (e: unknown) => setError(e),
      },
    );
  }

  function runSend() {
    setError(undefined);
    send.mutate(
      { data: body, params: { preview: false } },
      {
        onSuccess: (result) => {
          void navigate(result.announcementId ? `/announcements/${result.announcementId}` : '/announcements');
        },
        onError: (e: unknown) => setError(e),
      },
    );
  }

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={f.title}
        actions={
          <Link to="/announcements" className="text-accent underline">
            {a.detail.back}
          </Link>
        }
      />
      <Panel>
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-1">
            <TextArea
              label={f.text}
              rows={8}
              value={text}
              error={tooLong ? format(f.tooLong, { max: MAX_CHARACTERS }) : undefined}
              onChange={(e) => {
                setText(e.target.value);
                edit();
              }}
            />
            <p className={`text-sm ${tooLong ? 'text-danger' : 'text-ink-muted'}`} aria-live="polite">
              {format(f.counter, { count: length, max: MAX_CHARACTERS })}
            </p>
          </div>
          <RadioGroup<Audience>
            legend={f.audience}
            value={audience}
            onChange={(v) => {
              setAudience(v);
              edit();
            }}
            options={[
              { value: 'ALL', label: f.audienceAll },
              { value: 'STREETS', label: f.audienceStreets },
            ]}
          />
          {audience === 'STREETS' && (
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 text-sm font-medium text-ink-muted">{f.streets}</legend>
              {streetList.data?.length === 0 && <p className="text-ink-muted">{f.noStreets}</p>}
              {streetList.data?.map((street) => (
                <label key={street} className="flex min-h-11 items-center gap-3">
                  <input
                    type="checkbox"
                    className="size-5"
                    checked={streets.includes(street)}
                    onChange={(e) => {
                      setStreets(
                        e.target.checked ? [...streets, street] : streets.filter((s) => s !== street),
                      );
                      edit();
                    }}
                  />
                  <UserContent>{street}</UserContent>
                </label>
              ))}
              {noStreets && <p className="text-sm text-danger">{f.streetsRequired}</p>}
            </fieldset>
          )}
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium text-ink-muted">{f.channels}</legend>
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                className="size-5"
                checked={telegram}
                onChange={(e) => {
                  setTelegram(e.target.checked);
                  edit();
                }}
              />
              <span>{f.channelTelegram}</span>
            </label>
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                className="size-5"
                checked={email}
                onChange={(e) => {
                  setEmail(e.target.checked);
                  edit();
                }}
              />
              <span>{f.channelEmail}</span>
            </label>
            {channels.length === 0 && <p className="text-sm text-danger">{f.channelRequired}</p>}
          </fieldset>
          {error !== undefined && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
          {preview !== undefined && (
            <section className="flex flex-col gap-2 rounded-control border border-line p-3">
              <h2 className="font-semibold">{f.previewTitle}</h2>
              <p>{format(f.previewCounts, { ...preview.counts })}</p>
              {preview.paperMembers.length === 0 ? (
                <p className="text-ink-muted">{f.paperNone}</p>
              ) : (
                <>
                  <h3 className="text-sm font-medium text-ink-muted">{f.paperTitle}</h3>
                  <ul className="list-disc pl-5">
                    {preview.paperMembers.map((m) => (
                      <li key={m.memberId}>
                        <UserContent>{m.name}</UserContent>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="secondary" disabled={!valid || busy} onClick={runPreview}>
              {f.preview}
            </Button>
            <Button disabled={!valid || preview === undefined || busy} onClick={runSend}>
              {f.send}
            </Button>
            {preview === undefined && <span className="text-sm text-ink-muted">{f.sendHint}</span>}
          </div>
        </div>
      </Panel>
    </div>
  );
}
