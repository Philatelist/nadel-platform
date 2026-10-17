import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('renders the h1, badge, description and actions', () => {
    render(
      <PageHeader
        title="Members"
        badge={<span>Active</span>}
        description="All members"
        actions={<button>Add</button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Members' })).toHaveClass('font-serif');
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('All members')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });

  it('renders only the title when nothing else is given', () => {
    render(<PageHeader title="Members" />);
    expect(screen.getByRole('heading', { name: 'Members' })).toBeInTheDocument();
  });
});
