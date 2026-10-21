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
  it('never offers common land, archived or already chosen plots, and asks the server for plots only', async () => {
    renderWithApp(<PlotPicker label="Plot" excludeIds={['p1']} onPick={vi.fn()} />);
    expect(screen.getByRole('button', { name: '2' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '1' })).not.toBeInTheDocument();
    expect(screen.queryByText('Road')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '9' })).not.toBeInTheDocument();
    expect(listPlots).toHaveBeenCalledWith(expect.objectContaining({ kind: 'PLOT' }));
  });

  it('debounces the search and reports the picked plot', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    renderWithApp(<PlotPicker label="Plot" excludeIds={[]} onPick={onPick} />);
    await user.type(screen.getByRole('searchbox'), '2');
    await waitFor(() => expect(listPlots).toHaveBeenCalledWith(expect.objectContaining({ q: '2' })));
    await user.click(screen.getByRole('button', { name: '2' }));
    expect(onPick).toHaveBeenCalledWith({ id: 'p2', number: '2' });
  });
});
