import {
  ApiError,
  type CommonConsumerBilling,
  type CooperativeSettings,
  type Meter,
  type Permission,
} from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, wellMeter, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { CommonConsumersSection } from './CommonConsumersSection';

type Callbacks<T> = { onSuccess?: (data: T) => void; onError?: (error: unknown) => void };

let permissions: Permission[];
let billings: Record<string, CommonConsumerBilling>;
const updateSettings = vi.fn();
const updateBilling = vi.fn();
let billingCallbacks: Callbacks<CommonConsumerBilling>;
let settingsCallbacks: Callbacks<CooperativeSettings>;

const well: Meter = { ...wellMeter, id: 'w1', name: 'Well pump', active: true };
const light: Meter = { ...wellMeter, id: 'l1', name: 'Street lighting', active: true };

const settings: CooperativeSettings = {
  memberPlotStatusVisibility: 'OWN_ONLY',
  lossesMode: 'FIXED_PERCENT',
  lossesPercent: '5.00',
  commonConsumerDefaultSplit: 'CONNECTED_PLOTS',
  redAmount: '1000.00',
  redDays: 90,
};

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMeters: () => queryResult([well, light]),
  useGetCommonConsumerBilling: (id: string) => queryResult(billings[id]),
  useUpdateCommonConsumerBilling: (o: { mutation: Callbacks<CommonConsumerBilling> }) => {
    billingCallbacks = o.mutation;
    return { mutate: updateBilling, isPending: false };
  },
  useUpdateSettings: (o: { mutation: Callbacks<CooperativeSettings> }) => {
    settingsCallbacks = o.mutation;
    return { mutate: updateSettings, isPending: false };
  },
  listPlots: () =>
    Promise.resolve({
      items: [
        { id: 'p1', number: '1', kind: 'PLOT', archived: false },
        { id: 'p2', number: '2', kind: 'PLOT', archived: false },
      ],
      page: 0,
      size: 100,
      totalItems: 2,
      totalPages: 1,
    }),
  useListPlots: () => queryResult({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
}));

function renderSection() {
  renderWithApp(withQueries(<CommonConsumersSection saved={settings} />));
}

function consumer(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('li') as HTMLElement;
}

describe('CommonConsumersSection', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['SETTINGS_MANAGE', 'METERS_VIEW'];
    updateSettings.mockReset();
    updateBilling.mockReset();
    billings = {
      w1: {
        meterId: 'w1',
        name: 'Well pump',
        active: true,
        split: 'SERVICE_USERS',
        effectiveSplit: 'SERVICE_USERS',
        servicePlotIds: ['p1', 'p2'],
      },
      l1: {
        meterId: 'l1',
        name: 'Street lighting',
        active: true,
        effectiveSplit: 'CONNECTED_PLOTS',
        servicePlotIds: [],
      },
    };
  });

  it('lists the consumers with their own split, the default and the service users by number', async () => {
    renderSection();
    expect(screen.getByRole('radio', { name: 'Among connected plots' })).toBeChecked();
    const wellItem = within(consumer('Well pump'));
    expect(wellItem.getByLabelText('Split')).toHaveValue('SERVICE_USERS');
    expect(await wellItem.findByText('1')).toBeInTheDocument();
    expect(wellItem.getByText('2')).toBeInTheDocument();
    const lightItem = within(consumer('Street lighting'));
    expect(lightItem.getByLabelText('Split')).toHaveValue('');
    expect(lightItem.getByRole('option', { name: 'By default (Among connected plots)' })).toBeInTheDocument();
    expect(lightItem.queryByText('Service users')).toBeNull();
  });

  it('saves a changed cooperative default', async () => {
    renderSection();
    const save = screen.getByRole('button', { name: 'Save the default' });
    expect(save).toBeDisabled();
    await userEvent.click(screen.getByRole('radio', { name: 'Equally per plot' }));
    await userEvent.click(save);
    expect(updateSettings).toHaveBeenCalledWith({ data: { commonConsumerDefaultSplit: 'EQUAL_PER_PLOT' } });
    act(() => settingsCallbacks.onSuccess?.({ ...settings, commonConsumerDefaultSplit: 'EQUAL_PER_PLOT' }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
  });

  it('changes the split of a consumer and keeps its service users', async () => {
    renderSection();
    const item = consumer('Well pump');
    await userEvent.selectOptions(within(item).getByLabelText('Split'), 'EQUAL_PER_PLOT');
    await userEvent.click(within(item).getByRole('button', { name: 'Save' }));
    expect(updateBilling).toHaveBeenCalledWith({
      meterId: 'w1',
      data: { split: 'EQUAL_PER_PLOT', servicePlotIds: ['p1', 'p2'] },
    });
  });

  it('goes back to the default by sending no split', async () => {
    renderSection();
    const item = consumer('Well pump');
    await userEvent.selectOptions(within(item).getByLabelText('Split'), '');
    await userEvent.click(within(item).getByRole('button', { name: 'Save' }));
    expect(updateBilling).toHaveBeenCalledWith({ meterId: 'w1', data: { servicePlotIds: ['p1', 'p2'] } });
  });

  it('saves the service users after one is removed', async () => {
    renderSection();
    const item = consumer('Well pump');
    expect(within(item).getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.click(await within(item).findByRole('button', { name: 'Remove plot 1' }));
    await userEvent.click(within(item).getByRole('button', { name: 'Save' }));
    expect(updateBilling).toHaveBeenCalledWith({
      meterId: 'w1',
      data: { split: 'SERVICE_USERS', servicePlotIds: ['p2'] },
    });
    act(() =>
      billingCallbacks.onSuccess?.({ ...billings.w1!, servicePlotIds: ['p2'] } as CommonConsumerBilling),
    );
    expect(await within(item).findByText('Saved')).toBeInTheDocument();
  });

  it('shows a plot error at the plot it names', async () => {
    renderSection();
    const item = consumer('Well pump');
    await userEvent.click(await within(item).findByRole('button', { name: 'Remove plot 1' }));
    await userEvent.click(within(item).getByRole('button', { name: 'Save' }));
    act(() =>
      billingCallbacks.onError?.(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'servicePlotIds[0]', code: 'PLOT_ARCHIVED' }],
        }),
      ),
    );
    const error = await within(item).findByText('The plot is archived');
    expect(within(error.closest('li') as HTMLElement).getByText('2')).toBeInTheDocument();
  });

  it('does not list the consumers without METERS_VIEW', () => {
    permissions = ['SETTINGS_MANAGE'];
    renderSection();
    expect(screen.getByRole('radio', { name: 'Equally per plot' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Well pump' })).toBeNull();
  });
});
