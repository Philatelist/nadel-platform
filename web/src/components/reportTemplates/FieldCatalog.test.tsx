import type { ReportTemplateField } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { FieldCatalog } from './FieldCatalog';

const catalog: ReportTemplateField[] = [
  { key: 'cooperative.iban', field: 'cooperative.iban', type: 'TEXT', permission: 'REPORTS_VIEW' },
  { key: 'totals.debt', field: 'totals.debt', type: 'MONEY', example: '1250.50', permission: 'REPORTS_VIEW' },
  {
    key: 'totals.charged.WATER',
    field: 'totals.charged.byChargeType',
    pattern: 'totals.charged.<type>',
    chargeTypeId: 'c-1',
    type: 'MONEY',
    permission: 'REPORTS_VIEW',
  },
  {
    key: 'rows.plots.number',
    field: 'rows.plots.number',
    type: 'TEXT',
    rowSet: 'PLOTS',
    permission: 'REPORTS_VIEW',
  },
  {
    key: 'rows.months.month',
    field: 'rows.months.month',
    type: 'MONTH',
    rowSet: 'MONTHS',
    permission: 'REPORTS_VIEW',
  },
  {
    key: 'rows.payments.amount',
    field: 'rows.payments.amount',
    type: 'MONEY',
    rowSet: 'PAYMENTS',
    permission: 'PAYMENTS_VIEW',
  },
];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetReportTemplateCatalog: () => queryResult(catalog),
}));

const user = () => userEvent.setup({ applyAccept: false });

describe('FieldCatalog', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));
  afterEach(() => vi.unstubAllGlobals());

  it('groups single values and row sets, with monospace key, type, description and example', () => {
    renderWithApp(<FieldCatalog />);
    const single = screen.getByRole('list', { name: 'Single values' });
    expect(within(single).getAllByRole('listitem')).toHaveLength(3);
    expect(screen.getByRole('list', { name: 'Repeated row: plots' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Repeated row: billing months' })).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Repeated row: water' })).not.toBeInTheDocument();
    const debt = within(single).getAllByRole('listitem')[1]!;
    expect(within(debt).getByText('${totals.debt}').tagName).toBe('CODE');
    expect(within(debt).getByText('Amount')).toBeInTheDocument();
    expect(within(debt).getByText('Debt of all active plots at the end of the period')).toBeInTheDocument();
    expect(within(debt).getByText('1250.50').tagName).toBe('CODE');
    expect(debt).toHaveTextContent('Example: 1250.50');
  });

  it('names the charge type in a family description', () => {
    renderWithApp(<FieldCatalog />);
    expect(screen.getByText(/^Charged for Water with service days/)).toBeInTheDocument();
  });

  it('filters by key and by description', async () => {
    renderWithApp(<FieldCatalog />);
    const u = user();
    await u.type(screen.getByLabelText('Search placeholders'), 'iban');
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await u.clear(screen.getByLabelText('Search placeholders'));
    await u.type(screen.getByLabelText('Search placeholders'), 'billing month');
    expect(screen.getByText('${rows.months.month}')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await u.clear(screen.getByLabelText('Search placeholders'));
    await u.type(screen.getByLabelText('Search placeholders'), 'zzz');
    expect(screen.getByText('No placeholders match the search.')).toBeInTheDocument();
  });

  it('copies the placeholder text and says Copied', async () => {
    // userEvent installs its own clipboard on navigator, so it is set up before ours is stubbed.
    const u = user();
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    renderWithApp(<FieldCatalog />);
    await u.click(
      screen.getByRole('button', {
        name: 'Copy the placeholder: Debt of all active plots at the end of the period',
      }),
    );
    expect(writeText).toHaveBeenCalledWith('${totals.debt}');
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('says it could not copy when the clipboard and the fallback both fail', async () => {
    // userEvent installs its own clipboard on navigator, so it is set up before ours is stubbed.
    const u = user();
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    renderWithApp(<FieldCatalog />);
    await u.click(
      screen.getByRole('button', {
        name: 'Copy the placeholder: Debt of all active plots at the end of the period',
      }),
    );
    expect(await screen.findByText(/Could not copy/)).toBeInTheDocument();
  });

  it('falls back to the legacy copy when the clipboard API refuses', async () => {
    // userEvent installs its own clipboard on navigator, so it is set up before ours is stubbed.
    const u = user();
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    const exec = vi.fn(() => true);
    Object.assign(document, { execCommand: exec });
    renderWithApp(<FieldCatalog />);
    await u.click(
      screen.getByRole('button', {
        name: 'Copy the placeholder: Debt of all active plots at the end of the period',
      }),
    );
    expect(await screen.findByText('Copied')).toBeInTheDocument();
    expect(exec).toHaveBeenCalledWith('copy');
    Reflect.deleteProperty(document, 'execCommand');
  });

  it('lists the payments rows in their own group', () => {
    renderWithApp(<FieldCatalog />);
    const group = screen.getByRole('list', { name: 'Repeated row: payments' });
    expect(within(group).getAllByRole('listitem')).toHaveLength(1);
    expect(within(group).getByText('${rows.payments.amount}')).toBeInTheDocument();
  });

  it('in Ukrainian, the copy button label carries the description and no Latin key', () => {
    window.localStorage.setItem('nadel.locale', 'uk');
    renderWithApp(<FieldCatalog />);
    const buttons = screen
      .getAllByRole('button')
      .filter((b) => b.getAttribute('aria-label')?.startsWith('Копіювати'));
    expect(buttons).toHaveLength(catalog.length);
    for (const button of buttons)
      expect(button.getAttribute('aria-label')).not.toMatch(/cooperative|totals|rows|\w\.\w/);
  });

  it('marks the sample placeholder of the intro and the example value as code', () => {
    renderWithApp(<FieldCatalog />);
    expect(document.querySelectorAll('code[data-code]').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('${totals.debt}', { selector: 'code[data-code]' })).toBeInTheDocument();
  });
});
