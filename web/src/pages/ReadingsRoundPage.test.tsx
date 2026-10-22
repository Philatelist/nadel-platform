import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Meter, ReadingBatch, ReadingBatchResult, ReadingRowResult } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ReadingsRoundPage } from './ReadingsRoundPage';

const second: Meter = {
  ...plotMeter,
  id: 'm3',
  serial: 'EM-2',
  plot: { id: 'p8', number: '8', street: 'Lipova' },
};

let meters: Meter[];
let sent: ReadingBatch[];
let respond: (batch: ReadingBatch) => ReadingRowResult;
let roundParams: unknown[];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetReadingRound: (params: unknown) => {
    roundParams.push(params);
    return queryResult(meters);
  },
  useEnterReadings: (options: {
    mutation: { onSuccess: (result: ReadingBatchResult) => Promise<void> | void };
  }) => ({
    isPending: false,
    mutate: ({ data }: { data: ReadingBatch }) => {
      sent.push(data);
      void options.mutation.onSuccess({ rows: [respond(data)] });
    },
  }),
}));

function saved(extra: Partial<ReadingRowResult> = {}): ReadingRowResult {
  return {
    index: 0,
    meterId: 'm1',
    outcome: 'SAVED',
    supersededReadingIds: [],
    warnings: [],
    reading: {
      id: 'r1',
      meterId: 'm1',
      takenOn: '2026-10-21',
      value: '8250.000',
      status: 'APPROVED',
      source: 'ENTERED',
      changeable: true,
      interval: { from: '2026-09-03', to: '2026-10-21', days: 48, consumption: '110.000' },
    },
    ...extra,
  };
}

function renderRound(route = '/readings') {
  return renderWithApp(
    withQueries(
      <Routes>
        <Route path="/readings" element={<ReadingsRoundPage />} />
      </Routes>,
    ),
    { route },
  );
}

describe('ReadingsRoundPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-21T09:00:00Z') });
    window.localStorage.setItem('nadel.locale', 'uk');
    meters = [plotMeter, second];
    sent = [];
    roundParams = [];
    respond = () => saved();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('fills the round date with today, shows the quiet hint and saves a row without a date of its own', async () => {
    const user = userEvent.setup();
    renderRound();
    expect(screen.getByLabelText('Дата обходу')).toHaveValue('2026-10-21');
    expect(
      screen.getAllByText('Дата показу — сьогодні, 21.10.2026. Змініть, якщо знімали в інший день'),
    ).toHaveLength(1);

    const [first] = screen.getAllByLabelText('Показ, кВт·год');
    await user.type(first!, '8250{Enter}');

    // untouched date: the server dates the reading with today
    expect(sent).toEqual([{ rows: [{ meterId: 'm1', takenOn: undefined, value: '8250' }] }]);
    expect(screen.getByText('Збережено')).toBeInTheDocument();
    expect(screen.getByText(/110 кВт·год за 48 дн\./)).toBeInTheDocument();
    // Enter moves on to the next card
    expect(screen.getAllByLabelText('Показ, кВт·год')[1]).toHaveFocus();
  });

  it('sends the date of one row only', async () => {
    const user = userEvent.setup();
    renderRound();
    const cards = screen.getAllByRole('listitem');
    const card = cards[0]!;
    const date = within(card).getByLabelText('Дата показу цього лічильника');
    fireEvent.change(date, { target: { value: '2026-10-18' } });
    await user.type(within(card).getByLabelText('Показ, кВт·год'), '8,25');
    await user.click(within(card).getByRole('button', { name: 'Зберегти' }));
    expect(sent[0]?.rows[0]).toEqual({ meterId: 'm1', takenOn: '2026-10-18', value: '8.25' });

    await user.type(within(cards[1]!).getByLabelText('Показ, кВт·год'), '9000{Enter}');
    expect(sent[1]?.rows[0]?.takenOn).toBeUndefined();
  });

  it('applies a changed round date to rows that have no date of their own', async () => {
    const user = userEvent.setup();
    renderRound();
    const round = screen.getByLabelText('Дата обходу');
    fireEvent.change(round, { target: { value: '2026-10-20' } });
    expect(
      within(screen.getAllByRole('listitem')[0]!).getByLabelText('Дата показу цього лічильника'),
    ).toHaveValue('2026-10-20');
    await user.type(screen.getAllByLabelText('Показ, кВт·год')[0]!, '8250{Enter}');
    expect(sent[0]?.rows[0]?.takenOn).toBe('2026-10-20');
  });

  it('refuses an invalid value without a request', async () => {
    const user = userEvent.setup();
    renderRound();
    await user.type(screen.getAllByLabelText('Показ, кВт·год')[0]!, '12,3456{Enter}');
    expect(sent).toEqual([]);
    expect(
      screen.getByText('Показ — невід’ємне число з не більше ніж 3 знаками після коми'),
    ).toBeInTheDocument();
  });

  it('shows the refusal of a row with the facts the server sent', async () => {
    const user = userEvent.setup();
    respond = () => ({
      index: 0,
      meterId: 'm1',
      outcome: 'REJECTED',
      supersededReadingIds: [],
      warnings: [],
      error: {
        code: 'READING_OUT_OF_ORDER',
        field: 'value',
        params: { previousTakenOn: '2026-09-03', previousValue: '8140.000' },
      },
    });
    renderRound();
    await user.type(screen.getAllByLabelText('Показ, кВт·год')[0]!, '8100{Enter}');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Показ менший за попередній або більший за наступний. Попередній показ: 8 140 кВт·год, 03.09.2026.',
    );
    expect(screen.queryByText('Збережено')).not.toBeInTheDocument();
  });

  it('shows the future-date refusal with today', async () => {
    const user = userEvent.setup();
    respond = () => ({
      index: 0,
      meterId: 'm1',
      outcome: 'REJECTED',
      supersededReadingIds: [],
      warnings: [],
      error: { code: 'READING_DATE_IN_FUTURE', field: 'takenOn', params: { today: '2026-10-21' } },
    });
    renderRound();
    await user.type(screen.getAllByLabelText('Показ, кВт·год')[0]!, '8100{Enter}');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Дата показу не може бути в майбутньому. Сьогодні 21.10.2026.',
    );
  });

  it('shows the unusual-consumption warning and the superseded note after a save', async () => {
    const user = userEvent.setup();
    respond = () => saved({ warnings: ['UNUSUAL_CONSUMPTION'], supersededReadingIds: ['old'] });
    renderRound();
    await user.type(screen.getAllByLabelText('Показ, кВт·год')[0]!, '8250{Enter}');
    expect(screen.getByText('Перевірте показ: споживання значно більше звичайного')).toBeInTheDocument();
    expect(screen.getByText('Показ за цю дату вже був — його позначено як «замінено».')).toBeInTheDocument();
  });

  it('switches "без показу з" on with a 60-day default', async () => {
    const user = userEvent.setup();
    renderRound();
    expect(roundParams.at(-1)).toEqual({ resource: undefined, street: undefined, noReadingSince: undefined });
    await user.click(screen.getByRole('checkbox', { name: 'Без показу з' }));
    expect(screen.getByLabelText('Дата, з якої немає показу')).toHaveValue('2026-08-22');
    expect(roundParams.at(-1)).toMatchObject({ noReadingSince: '2026-08-22' });
  });

  it('shows a plot with two meters as one card with a row per meter and an icon each', () => {
    meters = [plotMeter, { ...plotMeter, id: 'm9', serial: 'WT-1', resource: 'WATER' }, second];
    renderRound();
    const cards = screen.getAllByRole('listitem');
    expect(cards).toHaveLength(2);
    expect(within(cards[0]!).getAllByRole('button', { name: 'Зберегти' })).toHaveLength(2);
    expect(within(cards[0]!).getByRole('img', { name: 'Електроенергія' })).toBeInTheDocument();
    expect(within(cards[0]!).getByRole('img', { name: 'Вода' })).toBeInTheDocument();
    expect(within(cards[1]!).getAllByRole('button', { name: 'Зберегти' })).toHaveLength(1);
  });

  it('uses the remembered resource when the URL has none, and the URL wins when it has one', () => {
    window.localStorage.setItem('nadel.readings.resource', 'WATER');
    renderRound();
    expect(roundParams.at(-1)).toMatchObject({ resource: 'WATER' });
  });

  it('remembers a changed filter and lets "all" clear it', async () => {
    const user = userEvent.setup();
    renderRound('/readings?resource=ELECTRICITY');
    expect(roundParams.at(-1)).toMatchObject({ resource: 'ELECTRICITY' });
    await user.selectOptions(screen.getByLabelText('Ресурс'), 'WATER');
    expect(window.localStorage.getItem('nadel.readings.resource')).toBe('WATER');
    await user.selectOptions(screen.getByLabelText('Ресурс'), '');
    expect(window.localStorage.getItem('nadel.readings.resource')).toBeNull();
    expect(roundParams.at(-1)).toMatchObject({ resource: undefined });
  });

  it('falls back to all when storage fails', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key) => {
      if (key === 'nadel.readings.resource') throw new Error('blocked');
      return null;
    });
    renderRound();
    expect(roundParams.at(-1)).toMatchObject({ resource: undefined });
    vi.restoreAllMocks();
  });

  it('lists the meters as cards with marked user content and no table', () => {
    renderRound();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getAllByText('Lipova', { selector: '[data-user-content]' })).toHaveLength(2);
  });
});
