import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type ImportPreview, type ImportProblem } from '@nadel/api-client';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ImportPreviewPanel, maxShownProblems } from './ImportPreviewPanel';

function preview(over: Partial<ImportPreview> = {}): ImportPreview {
  return {
    id: 'i1',
    fileName: 'plots.xlsx',
    expiresAt: '2026-10-20T12:30:00Z',
    counts: {
      plots: { created: 5, updated: 3, unchanged: 7 },
      members: { created: 0, updated: 0, unchanged: 0 },
      ownerships: { created: 0, updated: 0, unchanged: 0 },
    },
    problems: [],
    problemCount: 0,
    committable: true,
    ...over,
  };
}

function problem(row: number, over: Partial<ImportProblem> = {}): ImportProblem {
  return { sheet: 'PLOTS', row, code: 'REQUIRED', ...over };
}

function renderPanel(p: ImportPreview, onReset = vi.fn()) {
  renderWithApp(<ImportPreviewPanel preview={p} onReset={onReset} />);
  return onReset;
}

describe('ImportPreviewPanel', () => {
  it('shows the file name and the counts', () => {
    renderPanel(preview());
    expect(screen.getByText('File: plots.xlsx')).toBeInTheDocument();
    expect(screen.getByText(/Can be imported until/)).toBeInTheDocument();
    const row = within(screen.getByRole('row', { name: /Plots/ })).getAllByRole('cell');
    expect(row.map((c) => c.textContent)).toEqual(['Plots', '5', '3', '7']);
    expect(screen.getByRole('status')).toHaveTextContent('no problems');
  });

  it('labels a member problem with the member column and message', () => {
    renderPanel(
      preview({
        committable: false,
        problemCount: 1,
        problems: [problem(3, { sheet: 'MEMBERS', column: 'email', code: 'EMAIL_TAKEN' })],
      }),
    );
    const row = screen.getAllByRole('row').slice(-1)[0];
    expect(row).toHaveTextContent('Members3Email');
    expect(row).toHaveTextContent('A member with this email already exists.');
  });

  it('labels an ownership problem with the ownership column label', () => {
    renderPanel(
      preview({
        committable: false,
        problemCount: 1,
        problems: [
          problem(2, { sheet: 'OWNERSHIP', column: 'member_external_id', code: 'UNKNOWN_MEMBER_KEY' }),
        ],
      }),
    );
    const row = screen.getAllByRole('row').slice(-1)[0];
    expect(row).toHaveTextContent('Ownership2Member key');
    expect(row).toHaveTextContent('no row with this member key');
  });

  it('interpolates the plot number into the ownership conflict message', () => {
    renderPanel(
      preview({
        committable: false,
        problemCount: 1,
        problems: [
          problem(4, {
            sheet: 'OWNERSHIP',
            column: 'plot_number',
            code: 'OWNERSHIP_CONFLICT',
            params: { plot: '17-A' },
          }),
        ],
      }),
    );
    expect(screen.getAllByRole('row').slice(-1)[0]).toHaveTextContent(
      'Plot 17-A already has different owners — use Change owner on the plot card.',
    );
  });

  it('shows problems with sheet, row, column label and message', () => {
    renderPanel(
      preview({
        committable: false,
        problemCount: 3,
        problems: [
          problem(0, { code: 'SHEET_MISSING', sheet: 'FILE' }),
          problem(4, { column: 'area_m2', code: 'AREA_BOTH_OR_NONE' }),
          problem(5, { code: 'SOMETHING_NEW', column: 'custom' }),
        ],
      }),
    );
    const rows = screen.getAllByRole('row').slice(-3);
    expect(rows[0]).toHaveTextContent('File——');
    expect(rows[1]).toHaveTextContent('Plots4Area, m²');
    expect(rows[1]).toHaveTextContent('either in m² or in sotky');
    expect(rows[2]).toHaveTextContent('custom');
    expect(rows[2]).toHaveTextContent('The value is not valid.');
  });

  it('shows the first 200 problems and "and N more"', () => {
    const problems = Array.from({ length: 250 }, (_, i) => problem(i + 2));
    renderPanel(preview({ committable: false, problems, problemCount: 1000 }));
    // header rows: counts table has 4 rows, problems table has header + 200
    const tables = screen.getAllByRole('table');
    expect(within(tables[1]!).getAllByRole('row')).toHaveLength(maxShownProblems + 1);
    expect(screen.getByText('and 800 more')).toBeInTheDocument();
  });

  it('keeps the import button disabled and explains why when not committable', () => {
    renderPanel(preview({ committable: false, problemCount: 1, problems: [problem(2)] }));
    const button = screen.getByRole('button', { name: 'Import' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/Fix the problems/);
  });

  it('enables the import button when committable and calls onCommit', async () => {
    const onCommit = vi.fn();
    renderWithApp(<ImportPreviewPanel preview={preview()} onReset={vi.fn()} onCommit={onCommit} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Import' }));
    expect(onCommit).toHaveBeenCalledOnce();
  });

  it('disables the button while committing', () => {
    renderWithApp(<ImportPreviewPanel preview={preview()} onReset={vi.fn()} committing />);
    expect(screen.getByRole('button', { name: 'Importing…' })).toBeDisabled();
  });

  it('tells that there is nothing to import', () => {
    renderPanel(preview({ committable: false }));
    expect(screen.getByRole('status')).toHaveTextContent('Nothing new or changed in the file');
  });

  it('resets on "Choose another file"', async () => {
    const onReset = renderPanel(preview());
    await userEvent.setup().click(screen.getByRole('button', { name: 'Choose another file' }));
    expect(onReset).toHaveBeenCalledOnce();
  });

  it.each([
    [409, 'IMPORT_STALE'],
    [410, 'IMPORT_EXPIRED'],
    [409, 'IMPORT_ALREADY_COMMITTED'],
    [409, 'IMPORT_NOTHING_TO_COMMIT'],
    [409, 'IMPORT_HAS_ERRORS'],
  ])('disables the import button after the final refusal %s %s', (status, code) => {
    renderWithApp(
      <ImportPreviewPanel
        preview={preview()}
        onReset={vi.fn()}
        onCommit={vi.fn()}
        commitError={new ApiError(status, { code })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();
  });

  it('keeps the import button enabled after a retryable commit error', () => {
    renderWithApp(
      <ImportPreviewPanel
        preview={preview()}
        onReset={vi.fn()}
        onCommit={vi.fn()}
        commitError={new ApiError(500, { code: 'INTERNAL' })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Import' })).toBeEnabled();
  });
});
