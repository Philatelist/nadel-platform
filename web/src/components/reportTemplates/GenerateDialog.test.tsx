import { ApiError, type ReportTemplate } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { GenerateDialog } from './GenerateDialog';
import { TemplateRow } from './TemplateList';

const generateReportTemplate = vi.fn();
const downloadBlob = vi.fn();
let headers: Headers | undefined;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  generateReportTemplate: (...args: unknown[]) => generateReportTemplate(...args),
  responseHeadersOf: () => headers,
  useListReportTemplates: () => queryResult([]),
}));
vi.mock('../../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('../../reportTemplateGenerate', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../reportTemplateGenerate')>()),
  previousMonthPeriod: () => ({ from: '2026-09-01', to: '2026-09-30' }),
}));

const blob = new Blob(['xlsx']);
const user = () => userEvent.setup({ applyAccept: false });
const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');

function renderDialog(onClose = vi.fn()) {
  renderWithApp(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <GenerateDialog template={{ id: 't-1', name: 'Water form' }} onClose={onClose} />
    </QueryClientProvider>,
  );
  return onClose;
}

describe('GenerateDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    generateReportTemplate.mockReset();
    downloadBlob.mockReset();
    generateReportTemplate.mockResolvedValue(blob);
    headers = new Headers({ 'X-Report-Warnings': '0' });
  });

  it('opens with the previous month and the template name', () => {
    renderDialog();
    expect(screen.getByLabelText('From')).toHaveValue('2026-09-01');
    expect(screen.getByLabelText('To')).toHaveValue('2026-09-30');
    expect(screen.getByText('Water form')).toBeInTheDocument();
  });

  it('generates with from, to and the locale, downloads under the template-and-period name, plain success', async () => {
    renderDialog();
    await user().click(screen.getByRole('button', { name: 'Generate and download' }));
    await vi.waitFor(() => expect(downloadBlob).toHaveBeenCalledOnce());
    expect(generateReportTemplate).toHaveBeenCalledWith('t-1', {
      from: '2026-09-01',
      to: '2026-09-30',
      lang: 'en',
    });
    expect(downloadBlob).toHaveBeenCalledWith(blob, 'Water-form_2026-09-01_2026-09-30.xlsx');
    expect(await screen.findByText('The file was downloaded.')).toBeInTheDocument();
  });

  it('refuses an end before the start without calling the server', async () => {
    renderDialog();
    const u = user();
    await u.clear(screen.getByLabelText('To'));
    await u.type(screen.getByLabelText('To'), '2026-08-01');
    await u.click(screen.getByRole('button', { name: 'Generate and download' }));
    expect(screen.getByText('The end date must not be before the start date.')).toBeInTheDocument();
    expect(generateReportTemplate).not.toHaveBeenCalled();
  });

  it('refuses a period of a year or more', async () => {
    renderDialog();
    const u = user();
    await u.clear(screen.getByLabelText('To'));
    await u.type(screen.getByLabelText('To'), '2027-09-01');
    await u.click(screen.getByRole('button', { name: 'Generate and download' }));
    expect(screen.getByText('The period must be shorter than one year.')).toBeInTheDocument();
    expect(generateReportTemplate).not.toHaveBeenCalled();
  });

  it('still downloads with warnings and shows the count, the first warnings and where the full list is', async () => {
    headers = new Headers({
      'X-Report-Warnings': '12',
      'X-Report-Warnings-Detail': encode([
        { code: 'UNKNOWN_PLACEHOLDER', sheet: 'Аркуш1', cell: 'B7', placeholder: 'totals.nope' },
        {
          code: 'VALUE_UNKNOWN',
          sheet: 'Аркуш1',
          cell: 'C2',
          placeholder: 'cooperative.iban',
          reason: 'NOT_SET',
        },
      ]),
    });
    renderDialog();
    await user().click(screen.getByRole('button', { name: 'Generate and download' }));
    const notice = await screen.findByRole('status');
    expect(downloadBlob).toHaveBeenCalledOnce();
    expect(within(notice).getByText(/Warnings in the file: 12/)).toBeInTheDocument();
    const items = within(notice).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Аркуш1 B7: ${totals.nope} — The app does not know this placeholder');
    expect(items[1]).toHaveTextContent('Not entered yet');
    expect(within(notice).getByText(/“Warnings” sheet of the file/)).toBeInTheDocument();
  });

  it('shows the count even when the detail header is unreadable', async () => {
    headers = new Headers({ 'X-Report-Warnings': '5', 'X-Report-Warnings-Detail': '%%%' });
    renderDialog();
    await user().click(screen.getByRole('button', { name: 'Generate and download' }));
    expect(await screen.findByText(/Warnings in the file: 5/)).toBeInTheDocument();
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it.each([
    ['TEMPLATE_ARCHIVED', 409, 'The template is archived: a file cannot be made from it.'],
    [
      'REPORT_TOO_LARGE',
      422,
      'The report has more than 20 000 rows on a sheet. Narrow the filters and try again.',
    ],
    ['TEMPLATE_INVALID', 400, 'An array formula crosses the repeating row. Move it above or below that row.'],
  ])('maps %s to its text and downloads nothing', async (code, status, text) => {
    generateReportTemplate.mockRejectedValue(
      new ApiError(status, {
        code,
        errors: code === 'TEMPLATE_INVALID' ? [{ field: 'file', code: 'ARRAY_ACROSS_ROW' }] : undefined,
      }),
    );
    renderDialog();
    await user().click(screen.getByRole('button', { name: 'Generate and download' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it('puts a server REPORT_FILTER_INVALID under the To field', async () => {
    generateReportTemplate.mockRejectedValue(
      new ApiError(400, { code: 'REPORT_FILTER_INVALID', errors: [{ field: 'to', code: 'INVALID' }] }),
    );
    renderDialog();
    await user().click(screen.getByRole('button', { name: 'Generate and download' }));
    const to = await screen.findByLabelText('To');
    await vi.waitFor(() => expect(to).toHaveAccessibleDescription(/do not fit this report/));
  });
});

describe('Generate button on a template row', () => {
  const row = (status: ReportTemplate['status']) =>
    renderWithApp(
      <TemplateRow
        template={{ id: 't-1', name: 'Form', status, versionCount: 1 }}
        versionsOpen={false}
        onToggleVersions={vi.fn()}
        onUploadVersion={vi.fn()}
        onArchive={vi.fn()}
        onGenerate={vi.fn()}
      />,
    );

  it('is there for an active template', () => {
    row('ACTIVE');
    expect(screen.getByRole('button', { name: 'Generate' })).toBeInTheDocument();
  });

  it('is not there for an archived template', () => {
    row('ARCHIVED');
    expect(screen.queryByRole('button', { name: 'Generate' })).not.toBeInTheDocument();
  });
});
