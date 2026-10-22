import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  ApiError,
  type MeterRangeConsumption,
  type MeterTree,
  type MeterTreeNode,
  type Permission,
} from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { MeterTreePage } from './MeterTreePage';

let permissions: Permission[];
let tree: MeterTree;
let treeParams: { from: string; to: string } | undefined;
let treeEnabled: boolean | undefined;
const setParent = vi.fn();
let setParentOptions: { onSuccess: () => Promise<void>; onError: (e: unknown) => void };

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('../kyivToday', () => ({ kyivToday: () => '2026-10-21' }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMeterTree: (params: { from: string; to: string }, options: { query: { enabled: boolean } }) => {
    treeParams = params;
    treeEnabled = options.query.enabled;
    return queryResult(tree);
  },
  useListMeters: () =>
    queryResult([
      { ...plotMeter, id: 'sub', kind: 'SUBSTATION', name: 'Substation', plot: undefined },
      { ...plotMeter, id: 'qa', kind: 'QUARTER', name: 'Quarter A', plot: undefined },
      { ...plotMeter, id: 'qold', kind: 'QUARTER', name: 'Old quarter', plot: undefined, active: false },
      { ...plotMeter, id: 'm1', kind: 'PLOT' },
      { ...plotMeter, id: 'cc', kind: 'COMMON_CONSUMER', name: 'Well', plot: undefined },
    ]),
  useSetMeterParent: (o: { mutation: typeof setParentOptions }) => {
    setParentOptions = o.mutation;
    return { mutate: setParent, isPending: false };
  },
}));

const known = (value: string, calculated = false): MeterRangeConsumption => ({
  known: true,
  calculated,
  consumption: value,
});

const meter = (id: string, over: Partial<MeterTreeNode['meter']> = {}): MeterTreeNode['meter'] => ({
  id,
  kind: 'QUARTER',
  serial: `S-${id}`,
  installedOn: '2025-01-01',
  active: true,
  ...over,
});

function node(over: Partial<MeterTreeNode> & Pick<MeterTreeNode, 'meter'>): MeterTreeNode {
  return {
    meterIds: [over.meter.id],
    slices: [],
    total: known('0.000'),
    children: [],
    ...over,
  };
}

function renderPage() {
  return renderWithApp(withQueries(<MeterTreePage />));
}

describe('MeterTreePage', () => {
  beforeEach(() => {
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    setParent.mockReset();
    tree = {
      from: '2026-10-01',
      to: '2026-10-21',
      roots: [
        node({
          meter: meter('sub', { kind: 'SUBSTATION', name: 'Substation' }),
          total: known('1300.500'),
          children: [
            node({
              meter: meter('qa', { name: 'Quarter A' }),
              total: known('600.250', true),
              children: [
                node({
                  meter: meter('m1', { kind: 'PLOT', plot: { id: 'p7', number: '7' } }),
                  total: { known: false, calculated: false, lastReadingOn: '2026-09-20' },
                }),
                node({
                  meter: meter('m2', { kind: 'PLOT', plot: { id: 'p8', number: '8' } }),
                  total: { known: false, calculated: false },
                }),
              ],
            }),
          ],
        }),
      ],
    };
  });

  it('asks for the current month up to today', () => {
    renderPage();
    expect(treeParams).toEqual({ from: '2026-10-01', to: '2026-10-21' });
    expect(screen.getByLabelText('From')).toHaveValue('2026-10-01');
    expect(screen.getByLabelText('To')).toHaveValue('2026-10-21');
  });

  it('shows the nested tree with consumption, the calculated badge and the unknown texts', () => {
    renderPage();
    const lists = screen.getAllByRole('list', { name: 'Meter tree' });
    expect(lists).toHaveLength(3);
    const quarter = screen.getByText('Quarter A').closest('li') as HTMLElement;
    expect(within(quarter).getAllByText('Consumption: 600 kWh')[0]).toBeInTheDocument();
    expect(within(quarter).getByText('calculated')).toBeInTheDocument();
    expect(screen.getByText('Consumption: 1,300.5 kWh')).toBeInTheDocument();
    expect(screen.getByText('no reading after 20 Sep 2026')).toBeInTheDocument();
    expect(screen.getByText('no reading')).toBeInTheDocument();
    expect(screen.getByText('7')).toHaveAttribute('data-user-content');
  });

  it('marks meter labels as user content', () => {
    renderPage();
    expect(screen.getByText('Quarter A')).toHaveAttribute('data-user-content');
  });

  it('shows the slice dates of a meter moved within the range', () => {
    tree = {
      from: '2026-10-01',
      to: '2026-10-31',
      roots: [
        node({
          meter: meter('qa', { name: 'Quarter A' }),
          children: [
            node({
              meter: meter('m1', { kind: 'PLOT', plot: { id: 'p7', number: '7' } }),
              total: known('300.000'),
              slices: [
                {
                  from: '2026-10-01',
                  to: '2026-10-15',
                  start: { kind: 'REAL', meterId: 'm1', value: '1000.000' },
                  end: { kind: 'REAL', meterId: 'm1', value: '1100.000' },
                  consumption: known('100.000'),
                },
                {
                  from: '2026-10-15',
                  to: '2026-10-31',
                  start: { kind: 'REAL', meterId: 'm1', value: '1100.000' },
                  end: { kind: 'CALCULATED', meterId: 'm1', value: '1300.000' },
                  consumption: known('200.000', true),
                },
              ],
            }),
          ],
        }),
      ],
    };
    renderPage();
    expect(screen.getByText('1 Oct 2026 – 15 Oct 2026:')).toBeInTheDocument();
    expect(screen.getByText('15 Oct 2026 – 31 Oct 2026:')).toBeInTheDocument();
    expect(screen.getByText('Consumption: 100 kWh')).toBeInTheDocument();
    expect(screen.getByText('Consumption: 200 kWh')).toBeInTheDocument();
  });

  it('does not ask the server and explains when the end is before the start', async () => {
    renderPage();
    const to = screen.getByLabelText('To');
    await userEvent.setup().clear(to);
    await userEvent.setup().type(to, '2026-09-01');
    expect(treeEnabled).toBe(false);
    expect(screen.getByText('The end of the period cannot be before its start.')).toBeInTheDocument();
  });

  it('shows an empty state', () => {
    tree = { from: '2026-10-01', to: '2026-10-21', roots: [] };
    renderPage();
    expect(screen.getByText(/There is no tree yet/)).toBeInTheDocument();
  });

  it('offers Move only with METERS_MANAGE', () => {
    permissions = ['METERS_VIEW'];
    renderPage();
    expect(screen.queryByRole('button', { name: 'Move' })).toBeNull();
  });

  it('moves a meter under an active substation or quarter, not under itself or a removed one', async () => {
    const user = userEvent.setup();
    renderPage();
    const plot = screen.getByText('7').closest('li') as HTMLElement;
    await user.click(within(plot).getByRole('button', { name: 'Move' }));
    const dialog = screen.getByRole('dialog');
    const options = within(dialog)
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(options).toEqual(['Outside the tree', 'Substation', 'Quarter A']);
    await user.selectOptions(within(dialog).getByLabelText('Parent meter'), 'sub');
    await user.click(within(dialog).getByRole('button', { name: 'Move' }));
    expect(setParent).toHaveBeenCalledWith({
      id: 'm1',
      data: { parentMeterId: 'sub', effectiveFrom: '2026-10-21' },
    });
  });

  it('preselects the current parent and keeps Move disabled until it changes', async () => {
    const user = userEvent.setup();
    renderPage();
    const plot = screen.getByText('7').closest('li') as HTMLElement;
    await user.click(within(plot).getByRole('button', { name: 'Move' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText('Parent meter')).toHaveValue('qa');
    expect(within(dialog).getByRole('button', { name: 'Move' })).toBeDisabled();
    await user.selectOptions(within(dialog).getByLabelText('Parent meter'), 'sub');
    expect(within(dialog).getByRole('button', { name: 'Move' })).toBeEnabled();
  });

  it('takes a meter out of the tree without a parent', async () => {
    const user = userEvent.setup();
    renderPage();
    const plot = screen.getByText('7').closest('li') as HTMLElement;
    await user.click(within(plot).getByRole('button', { name: 'Move' }));
    await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText('Parent meter'), '');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move' }));
    expect(setParent).toHaveBeenCalledWith({ id: 'm1', data: { effectiveFrom: '2026-10-21' } });
  });

  it('shows the field problems of a refused move', async () => {
    const user = userEvent.setup();
    renderPage();
    const plot = screen.getByText('7').closest('li') as HTMLElement;
    await user.click(within(plot).getByRole('button', { name: 'Move' }));
    setParentOptions.onError(
      new ApiError(409, {
        code: 'METER_TREE_INVALID',
        errors: [
          { field: 'parentMeterId', code: 'TREE_QUARTER_UNDER_QUARTER' },
          { field: 'effectiveFrom', code: 'TREE_DATE_BEFORE_LATEST' },
        ],
      }),
    );
    expect(
      await screen.findByText('A quarter meter cannot be under another quarter meter'),
    ).toBeInTheDocument();
    expect(screen.getByText('The date is before the meter’s latest change in the tree')).toBeInTheDocument();
  });
});
