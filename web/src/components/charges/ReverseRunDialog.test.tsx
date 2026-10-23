import { ApiError, type Permission } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { bookedRun, reversedRun } from './lossesFixtures';
import { ReverseRunDialog, type RunReverser } from './ReverseRunDialog';
import { RunHistory, runRange, type RunSummaryRow } from './RunHistory';

type Callbacks = { onSuccess?: () => Promise<void> | void; onError?: (e: unknown) => void };

let permissions: Permission[];
const mutate = vi.fn();
const onDone = vi.fn();
const onClose = vi.fn();
const useReverse = (): RunReverser => ({ mutate, isPending: false });

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));

function renderDialog() {
  return renderWithApp(
    <ReverseRunDialog
      runId="r1"
      range="1 Oct 2026 – 15 Oct 2026"
      useReverse={useReverse}
      onDone={onDone}
      onClose={onClose}
    />,
  );
}

describe('ReverseRunDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
    onDone.mockReset();
    onClose.mockReset();
  });

  it('requires a reason before it reverses', async () => {
    renderDialog();
    expect(screen.getByRole('dialog', { name: /Reverse the run 1 Oct 2026/ })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Reason'), '   ');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse the run' }));
    expect(screen.getByText('Enter a reason')).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it('reverses with the trimmed reason, refreshes and closes', async () => {
    renderDialog();
    await userEvent.type(screen.getByLabelText('Reason'), '  Wrong meter  ');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse the run' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'r1', data: { reason: 'Wrong meter' } }, expect.any(Object));
    await act(async () => {
      await (mutate.mock.calls[0]?.[1] as Callbacks).onSuccess?.();
    });
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('shows the text of a refusal and stays open', async () => {
    renderDialog();
    await userEvent.type(screen.getByLabelText('Reason'), 'Wrong meter');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse the run' }));
    act(() =>
      (mutate.mock.calls[0]?.[1] as Callbacks).onError?.(new ApiError(409, { code: 'RUN_IN_CLOSED_MONTH' })),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('its billing month is closed');
    expect(onClose).not.toHaveBeenCalled();
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('shows a field error of the reason from the server', async () => {
    renderDialog();
    await userEvent.type(screen.getByLabelText('Reason'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse the run' }));
    act(() =>
      (mutate.mock.calls[0]?.[1] as Callbacks).onError?.(
        new ApiError(400, { code: 'VALIDATION_FAILED', errors: [{ field: 'reason', code: 'TOO_LONG' }] }),
      ),
    );
    expect(await screen.findByText('This value is too long.')).toBeInTheDocument();
  });
});

describe('RunHistory', () => {
  const rows: RunSummaryRow[] = [bookedRun, reversedRun];
  const query = { data: rows, error: null, isPending: false, isError: false, refetch: () => undefined };

  function renderHistory() {
    return renderWithApp(
      <RunHistory
        query={query}
        counts={() => 'counts line'}
        renderDetails={(run) => <p>details of {run.id}</p>}
        renderReverse={(run, close) => (
          <button type="button" onClick={close}>
            reverse dialog {run.id}
          </button>
        )}
      />,
    );
  }

  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'CHARGES_REVERSE'];
  });

  it('lists the runs with status, author, and the reversal with its reason', () => {
    renderHistory();
    expect(screen.getByText('Booked')).toBeInTheDocument();
    expect(screen.getByText('Reversed')).toBeInTheDocument();
    expect(screen.getByText('1 Oct 2026 – 15 Oct 2026')).toBeInTheDocument();
    expect(screen.getAllByText('Іваненко Петро')[0]).toHaveAttribute('data-user-content');
    expect(screen.getByText('Wrong meter')).toHaveAttribute('data-user-content');
    expect(screen.getByText('Шевченко Олена')).toHaveAttribute('data-user-content');
  });

  it('offers the reverse button only for a reversible run and opens the dialog', async () => {
    renderHistory();
    const buttons = screen.getAllByRole('button', { name: 'Reverse the run' });
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0] as HTMLElement);
    expect(screen.getByRole('button', { name: 'reverse dialog r1' })).toBeInTheDocument();
  });

  it('hides the reverse button without CHARGES_REVERSE', () => {
    permissions = ['CHARGES_VIEW'];
    renderHistory();
    expect(screen.queryByRole('button', { name: 'Reverse the run' })).toBeNull();
  });

  it('shows the details of a run on demand', async () => {
    renderHistory();
    expect(screen.queryByText('details of r1')).toBeNull();
    await userEvent.click(screen.getAllByRole('button', { name: 'Details' })[0] as HTMLElement);
    expect(screen.getByText('details of r1')).toBeInTheDocument();
  });

  it('formats the range of a run for titles', () => {
    expect(runRange(bookedRun, '{from} – {to}', 'uk')).toBe('01.10.2026 – 15.10.2026');
  });
});
