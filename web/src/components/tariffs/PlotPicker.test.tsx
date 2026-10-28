import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { PlotPicker } from './PlotPicker';

const listPlots = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlots: (params: unknown) => listPlots(params),
}));

beforeEach(() => {
  listPlots.mockReset();
  listPlots.mockReturnValue({
    data: {
      items: [
        { id: 'p1', number: '1', kind: 'PLOT', archived: false },
        { id: 'p2', number: '2', kind: 'PLOT', archived: false },
        { id: 'c1', number: 'Road', kind: 'COMMON', archived: false },
        { id: 'p9', number: '9', kind: 'PLOT', archived: true },
      ],
    },
  });
});

describe('PlotPicker', () => {
  it('keeps the suggestions closed until the user types or presses ArrowDown, and closes them on Escape', async () => {
    const user = userEvent.setup();
    renderWithApp(<PlotPicker label="Plot" excludeIds={[]} onPick={vi.fn()} />);
    const box = screen.getByRole('combobox', { name: 'Plot' });
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();

    await user.type(box, '{ArrowDown}');
    expect(box).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('listbox')).toHaveClass('absolute');

    await user.keyboard('{Escape}');
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();

    await user.type(box, '2');
    expect(screen.getByRole('listbox')).toBeInTheDocument();
  });

  it('never offers common land, archived or already chosen plots, and asks the server for plots only', async () => {
    const user = userEvent.setup();
    renderWithApp(<PlotPicker label="Plot" excludeIds={['p1']} onPick={vi.fn()} />);
    await user.type(screen.getByRole('combobox'), '{ArrowDown}');
    expect(screen.getByRole('option', { name: '2' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '1' })).not.toBeInTheDocument();
    expect(screen.queryByText('Road')).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '9' })).not.toBeInTheDocument();
    expect(listPlots).toHaveBeenCalledWith(expect.objectContaining({ kind: 'PLOT' }));
  });

  it('debounces the search and reports the plot picked by click', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    renderWithApp(<PlotPicker label="Plot" excludeIds={[]} onPick={onPick} />);
    await user.type(screen.getByRole('combobox'), '2');
    await waitFor(() => expect(listPlots).toHaveBeenCalledWith(expect.objectContaining({ q: '2' })));
    await user.click(screen.getByRole('option', { name: '2' }));
    expect(onPick).toHaveBeenCalledWith({ id: 'p2', number: '2' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('picks with the arrow keys and Enter, and names an option by its number and street', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    listPlots.mockReturnValue({
      data: { items: [{ id: 'p1', number: '1', kind: 'PLOT', archived: false, street: 'Garden' }] },
    });
    renderWithApp(<PlotPicker label="Plot" excludeIds={[]} onPick={onPick} />);
    await user.type(screen.getByRole('combobox'), '{ArrowDown}');
    expect(screen.getByRole('option', { name: '1 · Garden' })).toBeInTheDocument();
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onPick).toHaveBeenCalledWith({ id: 'p1', number: '1' });
  });
});
