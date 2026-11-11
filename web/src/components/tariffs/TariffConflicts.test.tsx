import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderWithApp } from '../../test/render';
import { TariffConflicts } from './TariffConflicts';

describe('TariffConflicts', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('renders nothing without conflicts', () => {
    const { container } = renderWithApp(<TariffConflicts conflicts={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('names the other tariff, the warning text and the plot numbers', () => {
    renderWithApp(
      <TariffConflicts
        conflicts={[
          {
            otherTariffId: 'tf2',
            otherTariffName: 'Old fee',
            plotCount: 12,
            plots: [
              { plotId: 'p1', number: '1' },
              { plotId: 'p2', number: '2' },
            ],
          },
        ]}
      />,
    );
    expect(screen.getByRole('link', { name: 'Old fee' })).toHaveAttribute('href', '/tariffs/tf2');
    expect(
      screen.getByText(
        /Tariffs of the same charge type cannot apply to the same plots\. These plots are not being charged now\. Change the plots of one of the tariffs or choose a different charge type\./,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '2' })).toHaveAttribute('href', '/plots/p2');
    expect(screen.getByText(/and 10 more/)).toBeInTheDocument();
  });
});
