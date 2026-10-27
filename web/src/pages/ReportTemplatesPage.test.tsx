import { ApiError, type ReportTemplate, type ReportTemplateVersion } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RequirePermission } from '../components/RequirePermission';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ReportTemplatesPage } from './ReportTemplatesPage';

const uploadReportTemplate = vi.fn();
const uploadReportTemplateVersion = vi.fn();
const archiveReportTemplate = vi.fn();
const downloadReportTemplateVersion = vi.fn();
const downloadBlob = vi.fn();
let templates: ReportTemplate[] = [];
let versions: ReportTemplateVersion[] = [];
let permissions: string[] = ['REPORT_TEMPLATES_MANAGE'];

vi.mock('../me', () => ({
  useCan: (permission: string) => permissions.includes(permission),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListReportTemplates: () => queryResult(templates),
  useListReportTemplateVersions: () => queryResult(versions),
  useGetReportTemplateCatalog: () => queryResult([]),
  uploadReportTemplate: (...args: unknown[]) => uploadReportTemplate(...args),
  uploadReportTemplateVersion: (...args: unknown[]) => uploadReportTemplateVersion(...args),
  archiveReportTemplate: (...args: unknown[]) => archiveReportTemplate(...args),
  downloadReportTemplateVersion: (...args: unknown[]) => downloadReportTemplateVersion(...args),
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));

const version = (overrides: Partial<ReportTemplateVersion> = {}): ReportTemplateVersion => ({
  id: 'v-2',
  versionNo: 2,
  fileName: 'water-v2.xlsx',
  sizeBytes: 1000,
  sha256: 'a'.repeat(64),
  uploadedAt: '2026-10-01T09:30:00Z',
  uploadedByName: 'Olena Koval',
  ...overrides,
});
const taxEmpty: ReportTemplate = { slot: 'TAX', status: 'NOT_ADDED', versionCount: 0 };
const water: ReportTemplate = {
  id: 't-1',
  name: 'Water form',
  description: 'For the water office',
  status: 'ACTIVE',
  latestVersion: version(),
  versionCount: 2,
};
const old: ReportTemplate = { ...water, id: 't-2', name: 'Old form', status: 'ARCHIVED' };

function xlsx(name = 'form.xlsx', size = 100): File {
  const file = new File(['x'], name);
  Object.defineProperty(file, 'size', { value: size });
  return file;
}
function problem(status: number, code: string, errors?: { field: string; code: string }[]) {
  return new ApiError(status, { code, errors });
}

function renderPage(route = '/reports/templates') {
  renderWithApp(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <Routes>
        <Route element={<RequirePermission permission="REPORT_TEMPLATES_MANAGE" />}>
          <Route path="/reports/templates" element={<ReportTemplatesPage />} />
        </Route>
      </Routes>
    </QueryClientProvider>,
    { route },
  );
}
const user = () => userEvent.setup({ applyAccept: false });

describe('ReportTemplatesPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    [uploadReportTemplate, uploadReportTemplateVersion, archiveReportTemplate].forEach((m) => m.mockReset());
    downloadReportTemplateVersion.mockReset();
    downloadBlob.mockReset();
    templates = [taxEmpty, water, old];
    versions = [version(), version({ id: 'v-1', versionNo: 1, fileName: 'water-v1.xlsx' })];
    permissions = ['REPORT_TEMPLATES_MANAGE'];
  });

  it('shows the empty tax slot and uploads into it with the slot name and TAX', async () => {
    uploadReportTemplate.mockResolvedValue({
      template: { ...water, slot: 'TAX', name: 'Tax report' },
      warnings: [],
    });
    renderPage();
    const tax = screen.getByRole('region', { name: 'Tax report' });
    expect(within(tax).getByText('The template is not added yet')).toBeInTheDocument();
    const u = user();
    await u.click(within(tax).getByRole('button', { name: 'Upload the template' }));
    const file = xlsx('tax.xlsx');
    await u.upload(within(tax).getByLabelText(/Excel file/), file);
    await u.click(within(tax).getByRole('button', { name: 'Upload' }));
    await vi.waitFor(() => expect(uploadReportTemplate).toHaveBeenCalledOnce());
    expect(uploadReportTemplate.mock.calls[0]?.[0]).toEqual({ file, name: 'Tax report', slot: 'TAX' });
    expect(await screen.findByText(/The template was uploaded\./)).toBeInTheDocument();
  });

  it('shows the active tax template with its version instead of the empty slot', () => {
    templates = [{ ...water, id: 't-tax', name: 'Tax form', slot: 'TAX' }];
    renderPage();
    const tax = screen.getByRole('list', { name: 'Tax report' });
    expect(within(tax).getByText('Tax form')).toBeInTheDocument();
    expect(within(tax).getByRole('button', { name: 'Upload new version' })).toBeInTheDocument();
    expect(screen.queryByText('The template is not added yet')).not.toBeInTheDocument();
  });

  it('lists the other templates with status, latest version, file, uploader and version count', () => {
    renderPage();
    const items = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByText('Water form')).toBeInTheDocument();
    expect(within(items[0]!).getByText('Active')).toBeInTheDocument();
    expect(within(items[0]!).getByText(/Version 2,/)).toBeInTheDocument();
    expect(within(items[0]!).getByText('water-v2.xlsx')).toBeInTheDocument();
    expect(within(items[0]!).getByText(/Olena Koval/)).toBeInTheDocument();
    expect(within(items[0]!).getByText(/Versions: 2/)).toBeInTheDocument();
    expect(within(items[1]!).getByText('Archived')).toBeInTheDocument();
  });

  it('uploads a new template with name, description and file, and shows the links warning', async () => {
    uploadReportTemplate.mockResolvedValue({ template: water, warnings: ['EXTERNAL_LINKS_REMOVED'] });
    renderPage();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Add a template' }));
    await u.type(screen.getByLabelText('Name'), '  Heating form ');
    await u.type(screen.getByLabelText('Description'), 'Winter');
    const file = xlsx('heat.xlsx');
    await u.upload(screen.getByLabelText(/Excel file/), file);
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    await vi.waitFor(() => expect(uploadReportTemplate).toHaveBeenCalledOnce());
    expect(uploadReportTemplate.mock.calls[0]?.[0]).toEqual({
      file,
      name: 'Heating form',
      description: 'Winter',
    });
    expect(
      await screen.findByText('Links to other files were removed from the template.'),
    ).toBeInTheDocument();
  });

  it('refuses a file that is not .xlsx or over 2 MiB without calling the API', async () => {
    renderPage();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Add a template' }));
    await u.type(screen.getByLabelText('Name'), 'Form');
    await u.upload(screen.getByLabelText(/Excel file/), xlsx('macro.xlsm'));
    expect(screen.getByText(/This is not an Excel file/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    await u.upload(screen.getByLabelText(/Excel file/), xlsx('big.xlsx', 2 * 1024 * 1024 + 1));
    expect(screen.getByText(/The file is too large: the maximum is 2 MB/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    expect(uploadReportTemplate).not.toHaveBeenCalled();
  });

  it('maps a macros refusal to its text', async () => {
    uploadReportTemplate.mockRejectedValue(
      problem(400, 'TEMPLATE_INVALID', [{ field: 'file', code: 'MACROS' }]),
    );
    renderPage();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Add a template' }));
    await u.type(screen.getByLabelText('Name'), 'Form');
    await u.upload(screen.getByLabelText(/Excel file/), xlsx());
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText(/The file contains macros/)).toBeInTheDocument();
  });

  it('shows a taken name as a field error on the name', async () => {
    uploadReportTemplate.mockRejectedValue(
      problem(409, 'TEMPLATE_NAME_TAKEN', [{ field: 'name', code: 'TEMPLATE_NAME_TAKEN' }]),
    );
    renderPage();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Add a template' }));
    await u.type(screen.getByLabelText('Name'), 'Water form');
    await u.upload(screen.getByLabelText(/Excel file/), xlsx());
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText('Another active template already has this name.')).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true');
  });

  it('maps a taken tax slot to its text', async () => {
    uploadReportTemplate.mockRejectedValue(
      problem(409, 'TEMPLATE_SLOT_TAKEN', [{ field: 'slot', code: 'TEMPLATE_SLOT_TAKEN' }]),
    );
    renderPage();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Upload the template' }));
    await u.upload(screen.getByLabelText(/Excel file/), xlsx());
    await u.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText(/already added\. Upload a new version/)).toBeInTheDocument();
  });

  it('uploads a new version with the file only', async () => {
    uploadReportTemplateVersion.mockResolvedValue({ template: water, warnings: [] });
    renderPage();
    const u = user();
    const item = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole('listitem')[0]!;
    await u.click(within(item).getByRole('button', { name: 'Upload new version' }));
    expect(within(item).queryByLabelText('Name')).not.toBeInTheDocument();
    const file = xlsx('v3.xlsx');
    await u.upload(within(item).getByLabelText(/Excel file/), file);
    await u.click(within(item).getByRole('button', { name: 'Upload' }));
    await vi.waitFor(() => expect(uploadReportTemplateVersion).toHaveBeenCalledWith('t-1', { file }));
  });

  it('expands the versions and downloads one under its own file name', async () => {
    const blob = new Blob(['x']);
    downloadReportTemplateVersion.mockResolvedValue(blob);
    renderPage();
    const u = user();
    const item = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole('listitem')[0]!;
    await u.click(within(item).getByRole('button', { name: 'Versions' }));
    const downloads = within(item).getAllByRole('button', { name: 'Download' });
    expect(downloads).toHaveLength(2);
    await u.click(downloads[1]!);
    await vi.waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'water-v1.xlsx'));
    expect(downloadReportTemplateVersion).toHaveBeenCalledWith('t-1', 'v-1');
  });

  it('archives only after the confirm step', async () => {
    archiveReportTemplate.mockResolvedValue({ ...water, status: 'ARCHIVED' });
    renderPage();
    const u = user();
    const item = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole('listitem')[0]!;
    await u.click(within(item).getByRole('button', { name: 'Archive' }));
    expect(archiveReportTemplate).not.toHaveBeenCalled();
    const dialog = await screen.findByRole('dialog');
    await u.click(within(dialog).getByRole('button', { name: 'Archive' }));
    await vi.waitFor(() => expect(archiveReportTemplate).toHaveBeenCalledWith('t-1'));
  });

  it('gives an archived template no upload or archive button', () => {
    renderPage();
    const archived = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole(
      'listitem',
    )[1]!;
    expect(within(archived).queryByRole('button', { name: 'Upload new version' })).not.toBeInTheDocument();
    expect(within(archived).queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument();
    expect(within(archived).getByRole('button', { name: 'Versions' })).toBeInTheDocument();
  });

  it('is not reachable without REPORT_TEMPLATES_MANAGE', () => {
    permissions = ['REPORTS_VIEW'];
    renderPage();
    expect(screen.queryByRole('heading', { level: 1, name: 'Report templates' })).not.toBeInTheDocument();
  });

  it('offers Generate on active templates only and shows the placeholder catalog', () => {
    renderPage();
    const items = within(screen.getByRole('list', { name: 'Other templates' })).getAllByRole('listitem');
    expect(within(items[0]!).getByRole('button', { name: 'Generate' })).toBeInTheDocument();
    expect(within(items[1]!).queryByRole('button', { name: 'Generate' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Placeholders' })).toBeInTheDocument();
  });
});
