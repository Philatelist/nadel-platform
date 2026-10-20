import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { ImportPage } from './ImportPage';

const downloadImportTemplate = vi.fn();
const downloadBlob = vi.fn();
const uploadImport = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  downloadImportTemplate: (...args: unknown[]) => downloadImportTemplate(...args),
  uploadImport: (...args: unknown[]) => uploadImport(...args),
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('../me', () => ({ useOptionalMe: () => null }));

function renderPage() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <ImportPage />
    </QueryClientProvider>,
  );
}

describe('ImportPage', () => {
  beforeEach(() => {
    downloadImportTemplate.mockReset();
    downloadBlob.mockReset();
    uploadImport.mockReset();
  });

  it('downloads the template and saves the blob', async () => {
    const blob = new Blob(['x']);
    downloadImportTemplate.mockResolvedValue(blob);
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Import' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download template' }));
    await vi.waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'nadel-import-template.xlsx'));
    expect(downloadImportTemplate).toHaveBeenCalledOnce();
  });

  it('disables the button while downloading', async () => {
    downloadImportTemplate.mockReturnValue(new Promise(() => {}));
    renderPage();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download template' }));
    expect(await screen.findByRole('button', { name: 'Downloading…' })).toBeDisabled();
  });

  it('shows a notice when the download fails and saves nothing', async () => {
    downloadImportTemplate.mockRejectedValue(new ApiError(500, undefined));
    renderPage();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download template' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  describe('upload', () => {
    const preview = {
      id: 'i1',
      fileName: 'plots.xlsx',
      expiresAt: '2026-10-20T12:30:00Z',
      counts: {
        plots: { created: 2, updated: 1, unchanged: 0 },
        members: { created: 0, updated: 0, unchanged: 0 },
        ownerships: { created: 0, updated: 0, unchanged: 0 },
      },
      problems: [],
      problemCount: 0,
      committable: true,
    };
    const xlsx = (name = 'plots.xlsx') => new File(['x'], name);

    it('disables the check button until a file is chosen', () => {
      renderPage();
      expect(screen.getByText('No file chosen')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Check file' })).toBeDisabled();
    });

    it('rejects a non-xlsx file on the client without a request', async () => {
      renderPage();
      await userEvent
        .setup({ applyAccept: false })
        .upload(screen.getByLabelText('Choose file'), xlsx('a.csv'));
      expect(await screen.findByRole('alert')).toHaveTextContent('not an Excel file');
      expect(screen.getByRole('button', { name: 'Check file' })).toBeDisabled();
      expect(uploadImport).not.toHaveBeenCalled();
    });

    it('uploads the file and shows the preview', async () => {
      uploadImport.mockResolvedValue(preview);
      renderPage();
      const user = userEvent.setup();
      await user.upload(screen.getByLabelText('Choose file'), xlsx());
      expect(screen.getByText('plots.xlsx')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Check file' }));
      expect(await screen.findByRole('heading', { name: 'Check result' })).toBeInTheDocument();
      expect(uploadImport).toHaveBeenCalledWith({ file: expect.objectContaining({ name: 'plots.xlsx' }) });
    });

    it('shows the size message on HTTP 413', async () => {
      uploadImport.mockRejectedValue(new ApiError(413, undefined));
      renderPage();
      const user = userEvent.setup();
      await user.upload(screen.getByLabelText('Choose file'), xlsx());
      await user.click(screen.getByRole('button', { name: 'Check file' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('larger than 10 MB');
    });
  });
});
