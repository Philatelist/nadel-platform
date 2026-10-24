import type { ReportCode, ReportTemplate } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { chargesByMonthInfo } from '../test/reportFixtures';
import { renderWithApp } from '../test/render';
import { ReportsPage } from './ReportsPage';

// A code the generated type does not know, so the catalog certainly has no title for it.
const unknownReportCode = 'not-a-report' as ReportCode;

let reports: unknown[] = [];
let canManageTemplates = true;
let templates: ReportTemplate[] = [];

vi.mock('../me', () => ({
  useCan: (permission: string) => permission !== 'REPORT_TEMPLATES_MANAGE' || canManageTemplates,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListReports: () => queryResult(reports),
  useListReportTemplates: () => queryResult(templates),
}));

describe('ReportsPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    reports = [chargesByMonthInfo];
    canManageTemplates = true;
    templates = [];
  });

  it('lists a card per report the API returns, with its title, description and link', () => {
    renderWithApp(<ReportsPage />);
    const items = within(screen.getByRole('list', { name: 'Reports' })).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(within(items[0]!).getByRole('link', { name: 'Charges by month' })).toHaveAttribute(
      'href',
      '/reports/charges-by-month',
    );
    expect(within(items[0]!).getByText(/What was charged in each billing month/)).toBeInTheDocument();
  });

  it('leaves out a report the catalog has no title for and keeps the others', () => {
    reports = [{ ...chargesByMonthInfo, code: unknownReportCode }, chargesByMonthInfo];
    renderWithApp(<ReportsPage />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('shows nothing for a report code the catalog has no title for', () => {
    reports = [{ ...chargesByMonthInfo, code: unknownReportCode }];
    canManageTemplates = false;
    renderWithApp(<ReportsPage />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('No reports are available.')).toBeInTheDocument();
  });

  it('shows no Debtors card when the API leaves it out, and one when it lists it', () => {
    renderWithApp(<ReportsPage />);
    expect(screen.queryByRole('link', { name: 'Debtors' })).not.toBeInTheDocument();
    reports = [chargesByMonthInfo, { ...chargesByMonthInfo, code: 'debtors', personalData: true }];
    renderWithApp(<ReportsPage />);
    expect(screen.getByRole('link', { name: 'Debtors' })).toHaveAttribute('href', '/reports/debtors');
  });

  it('links to the report templates only with REPORT_TEMPLATES_MANAGE', () => {
    renderWithApp(<ReportsPage />);
    expect(screen.getByRole('link', { name: 'Report templates' })).toHaveAttribute(
      'href',
      '/reports/templates',
    );
    canManageTemplates = false;
    renderWithApp(<ReportsPage />);
    expect(screen.getAllByRole('link', { name: 'Report templates' })).toHaveLength(1);
  });

  describe('generating from a template', () => {
    const active: ReportTemplate = { id: 't-1', name: 'Water form', status: 'ACTIVE', versionCount: 1 };
    const archived: ReportTemplate = { id: 't-2', name: 'Old form', status: 'ARCHIVED', versionCount: 1 };

    it('offers Generate on active templates, and no upload, versions or archive, without REPORT_TEMPLATES_MANAGE', () => {
      templates = [active, archived];
      canManageTemplates = false;
      renderWithApp(<ReportsPage />);
      const items = within(screen.getByRole('region', { name: 'Report templates' })).getAllByRole('listitem');
      expect(items).toHaveLength(1);
      expect(within(items[0]!).getByText('Water form')).toBeInTheDocument();
      expect(within(items[0]!).getByRole('button', { name: 'Generate' })).toBeInTheDocument();
      expect(screen.queryByText('Old form')).not.toBeInTheDocument();
      for (const name of ['Upload new version', 'Versions', 'Archive']) {
        expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      }
    });

    it('leaves the templates to their own page for a user who manages them', () => {
      templates = [active];
      renderWithApp(<ReportsPage />);
      expect(screen.queryByRole('button', { name: 'Generate' })).not.toBeInTheDocument();
    });
  });
});
