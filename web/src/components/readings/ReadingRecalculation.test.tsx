import {
  ApiError,
  type Meter,
  type MeterReading,
  type Permission,
  type PendingReading,
} from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { preview } from '../recalculation/recalcFixtures';
import { PendingReadingCard } from './PendingReadingCard';
import { ReadingHistory } from './ReadingHistory';
import { RoundRow } from './RoundRow';

type Options = {
  mutation: { onSuccess: (r: unknown) => unknown; onError: (e: unknown, v: unknown) => unknown };
};

const runAsync = vi.fn();
const invalidateAfterRecalculation = vi.fn();
let permissions: Permission[];
const inUse = new ApiError(409, { code: 'READING_IN_USE' });

const reading: MeterReading = {
  id: 'r1',
  meterId: 'm1',
  takenOn: '2026-10-05',
  value: '8205.000',
  status: 'APPROVED',
  source: 'ENTERED',
  changeable: true,
};

vi.mock('../charges/chargeInvalidation', () => ({
  invalidateAfterReading: vi.fn(),
  invalidateAfterRecalculation: (...args: unknown[]) => invalidateAfterRecalculation(...args),
}));
vi.mock('../../me', () => ({
  useOptionalMe: () => null,
  useCan: (permission: Permission) => permissions.includes(permission),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMeterReadings: () => queryResult({ meterId: 'm1', intervals: [], readings: [reading] }),
  useCorrectReading: (o: Options) => {
    return { mutate: (v: unknown) => o.mutation.onError(inUse, v), isPending: false };
  },
  useDeleteReading: (o: Options) => {
    return { mutate: (v: unknown) => o.mutation.onError(inUse, v), isPending: false };
  },
  useEnterReadings: (o: Options) => {
    return {
      mutate: () =>
        o.mutation.onSuccess({
          rows: [
            {
              outcome: 'REJECTED',
              error: { code: 'READING_IN_USE' },
              supersededReadingIds: [],
              warnings: [],
            },
          ],
        }),
      isPending: false,
    };
  },
  useApproveReading: (o: Options) => {
    return { mutate: (v: unknown) => o.mutation.onError(inUse, v), isPending: false };
  },
  useRunRecalculation: () => ({ mutateAsync: runAsync, isPending: false }),
}));

const booked = preview({ id: 'rc1', preview: false, booked: true, changeApplied: true });
const empty = preview({ lines: [], totals: [], total: '0.00', plotCount: 0 });

function answer(plan = preview(), result = booked) {
  runAsync.mockImplementation(({ params }: { params: { preview: boolean } }) =>
    Promise.resolve(params.preview ? plan : result),
  );
}

describe('reading changes through a recalculation', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    runAsync.mockReset();
    invalidateAfterRecalculation.mockReset();
    permissions = ['READINGS_ENTER', 'RECALCULATION_RUN'];
  });

  async function correctOnCard(user: ReturnType<typeof userEvent.setup>) {
    renderWithApp(withQueries(<ReadingHistory meter={plotMeter as Meter} />));
    await user.click(screen.getByRole('button', { name: 'Correct' }));
    const dialog = screen.getByRole('dialog');
    const value = within(dialog).getByLabelText(/^Reading/, { selector: 'input[inputmode="decimal"]' });
    await user.clear(value);
    await user.type(value, '8215');
    await user.click(within(dialog).getByRole('button', { name: 'Save the correction' }));
  }

  it('opens step 2 for a used reading and books the correction with the digest and the reason', async () => {
    answer();
    const user = userEvent.setup();
    await correctOnCard(user);
    const cause = {
      type: 'READING_CORRECT',
      reading: { readingId: 'r1', reading: { takenOn: '2026-10-05', value: '8215' } },
    };
    expect(runAsync).toHaveBeenCalledWith({ data: { cause }, params: { preview: true } });
    await user.type(await screen.findByLabelText(/^Reason/), 'Wrong digit');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Reading corrected, recalculation booked')).toBeInTheDocument();
    expect(runAsync).toHaveBeenLastCalledWith({
      data: { cause, digest: 'digest-1', reason: 'Wrong digit' },
      params: { preview: false },
    });
    expect(invalidateAfterRecalculation).toHaveBeenCalledTimes(1);
  });

  it('deletes a used reading through step 2', async () => {
    answer();
    const user = userEvent.setup();
    renderWithApp(withQueries(<ReadingHistory meter={plotMeter as Meter} />));
    await user.click(screen.getByRole('button', { name: 'Delete reading' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete reading' }));
    const cause = { type: 'READING_DELETE', reading: { readingId: 'r1' } };
    expect(runAsync).toHaveBeenCalledWith({ data: { cause }, params: { preview: true } });
    await user.type(await screen.findByLabelText(/^Reason/), 'Entered twice');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Reading deleted, recalculation booked')).toBeInTheDocument();
  });

  it('saves a correction whose plan is empty without a reason', async () => {
    answer(empty, preview({ preview: false, booked: false, changeApplied: true }));
    const user = userEvent.setup();
    await correctOnCard(user);
    await vi.waitFor(() => expect(runAsync).toHaveBeenCalledTimes(2));
    expect(runAsync.mock.calls[1]?.[0]).toMatchObject({
      data: { digest: 'digest-1' },
      params: { preview: false },
    });
    expect(screen.queryByLabelText(/^Reason/)).not.toBeInTheDocument();
    expect(screen.queryByText('Reading corrected, recalculation booked')).not.toBeInTheDocument();
  });

  it('shows the hint and no step 2 to a user without RECALCULATION_RUN', async () => {
    permissions = ['READINGS_ENTER'];
    const user = userEvent.setup();
    await correctOnCard(user);
    expect(
      await screen.findByText('The reading has already been charged — ask the chairman'),
    ).toBeInTheDocument();
    expect(runAsync).not.toHaveBeenCalled();
    expect(screen.queryByLabelText(/^Reason/)).not.toBeInTheDocument();
  });

  it('opens step 2 with READING_ENTER for a back-dated reading of the round', async () => {
    answer();
    const user = userEvent.setup();
    renderWithApp(
      withQueries(<RoundRow meter={plotMeter as Meter} roundDate="2026-09-20" onSaved={vi.fn()} />),
    );
    await user.type(screen.getByLabelText(/^Reading, /, { selector: 'input[inputmode="decimal"]' }), '8210');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const cause = {
      type: 'READING_ENTER',
      reading: { meterId: 'm1', reading: { takenOn: '2026-09-20', value: '8210' } },
    };
    expect(runAsync).toHaveBeenCalledWith({ data: { cause }, params: { preview: true } });
    await user.type(await screen.findByLabelText(/^Reason/), 'Late reading');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Reading saved, recalculation booked')).toBeInTheDocument();
  });

  it('shows the electrician the hint in the round', async () => {
    permissions = ['READINGS_ENTER'];
    const user = userEvent.setup();
    renderWithApp(
      withQueries(<RoundRow meter={plotMeter as Meter} roundDate="2026-09-20" onSaved={vi.fn()} />),
    );
    await user.type(screen.getByLabelText(/^Reading, /, { selector: 'input[inputmode="decimal"]' }), '8210');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText('The reading has already been charged — ask the chairman')).toBeInTheDocument();
    expect(runAsync).not.toHaveBeenCalled();
  });

  it('shows the hint when approving a used pending reading without RECALCULATION_RUN', async () => {
    permissions = ['READINGS_APPROVE'];
    const user = userEvent.setup();
    const item = {
      reading: { ...reading, status: 'PENDING' },
      meter: plotMeter,
      warnings: [],
    } as unknown as PendingReading;
    renderWithApp(
      withQueries(
        <ul>
          <PendingReadingCard item={item} />
        </ul>,
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    expect(screen.getByText('The reading has already been charged — ask the chairman')).toBeInTheDocument();
  });
});
