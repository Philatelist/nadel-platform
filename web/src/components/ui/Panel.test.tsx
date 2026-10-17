import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Panel } from './Panel';

describe('Panel', () => {
  it('renders an optional h2 title and its children', () => {
    render(
      <Panel title="Details">
        <p>Body</p>
      </Panel>,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Details' })).toBeInTheDocument();
    expect(screen.getByText('Body')).toBeInTheDocument();
  });

  it('renders without a title', () => {
    render(<Panel>Body</Panel>);
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });
});
