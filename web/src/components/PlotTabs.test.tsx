import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderWithApp } from '../test/render';
import { PlotTabs } from './PlotTabs';

describe('PlotTabs', () => {
  it('shows the first tab and switches on click', async () => {
    renderWithApp(
      <PlotTabs
        tabs={[
          { id: 'a', label: 'Account', content: <p>account body</p> },
          { id: 'b', label: 'Invoices', content: <p>invoices body</p> },
        ]}
      />,
    );
    expect(screen.getByText('account body')).toBeTruthy();
    expect(screen.queryByText('invoices body')).toBeNull();
    await userEvent.click(screen.getByRole('tab', { name: 'Invoices' }));
    expect(screen.getByText('invoices body')).toBeTruthy();
    expect(screen.queryByText('account body')).toBeNull();
  });

  it('skips a falsy tab and hides the bar when one remains', () => {
    renderWithApp(<PlotTabs tabs={[{ id: 'a', label: 'Account', content: <p>only</p> }, false]} />);
    expect(screen.getByText('only')).toBeTruthy();
    expect(screen.queryByRole('tablist')).toBeNull();
  });
});
