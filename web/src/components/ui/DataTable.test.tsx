import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataTable, type Column } from './DataTable';

interface Row {
  id: string;
  name: string;
  debt: string;
}
const columns: Column<Row>[] = [
  { key: 'name', header: 'Name', cell: (r) => r.name },
  { key: 'debt', header: 'Debt', cell: (r) => r.debt, numeric: true },
];

describe('DataTable', () => {
  it('renders a captioned table with headers and rows', () => {
    render(
      <DataTable
        columns={columns}
        rows={[{ id: '1', name: 'Olena', debt: '10.00' }]}
        rowKey={(r) => r.id}
        caption="Members"
        emptyMessage="Nothing"
      />,
    );
    expect(screen.getByRole('table', { name: 'Members' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Debt' })).toHaveClass('text-right');
    expect(screen.getByRole('cell', { name: '10.00' })).toHaveClass('text-right', 'font-mono');
    expect(screen.getByRole('cell', { name: 'Olena' })).not.toHaveClass('text-right');
  });

  it('shows the empty message in a single spanning row', () => {
    render(
      <DataTable
        columns={columns}
        rows={[]}
        rowKey={(r: Row) => r.id}
        caption="Members"
        emptyMessage="Nothing"
      />,
    );
    const cell = screen.getByRole('cell', { name: 'Nothing' });
    expect(cell).toHaveAttribute('colspan', '2');
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2);
  });
});
