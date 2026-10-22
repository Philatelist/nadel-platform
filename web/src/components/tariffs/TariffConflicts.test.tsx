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

  it('names the other tariff, the plot count and the plot numbers', () => {
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
    expect(screen.getByText(/12 plots are covered by both tariffs/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '2' })).toHaveAttribute('href', '/plots/p2');
    expect(screen.getByText(/and 10 more/)).toBeInTheDocument();
  });
});
