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
const commitImport = vi.fn();
const listImports = vi.fn();
let canViewMembers = true;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  downloadImportTemplate: (...args: unknown[]) => downloadImportTemplate(...args),
  uploadImport: (...args: unknown[]) => uploadImport(...args),
  commitImport: (...args: unknown[]) => commitImport(...args),
  listImports: (...args: unknown[]) => listImports(...args),
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('../me', () => ({ useOptionalMe: () => null, useCan: () => canViewMembers }));

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
    commitImport.mockReset();
    listImports.mockReset();
    listImports.mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    canViewMembers = true;
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

    async function toPreview(over: object = {}) {
      uploadImport.mockResolvedValue({ ...preview, ...over });
      renderPage();
      const user = userEvent.setup();
      await user.upload(screen.getByLabelText('Choose file'), xlsx());
      await user.click(screen.getByRole('button', { name: 'Check file' }));
      await screen.findByRole('heading', { name: 'Check result' });
      return user;
    }

    it('commits and shows the result with links', async () => {
      commitImport.mockResolvedValue({ id: 'i1', counts: preview.counts });
      const user = await toPreview();
      await user.click(screen.getByRole('button', { name: 'Import' }));
      expect(await screen.findByRole('heading', { name: 'Import complete' })).toBeInTheDocument();
      expect(commitImport).toHaveBeenCalledWith('i1');
      expect(screen.getByRole('link', { name: 'Go to plots' })).toHaveAttribute('href', '/plots');
      expect(screen.getByRole('link', { name: 'Go to members' })).toHaveAttribute('href', '/members');
      expect(screen.queryByRole('heading', { name: 'Check result' })).not.toBeInTheDocument();
    });

    it('refreshes the history after the upload and after the commit', async () => {
      commitImport.mockResolvedValue({ id: 'i1', counts: preview.counts });
      const user = await toPreview();
      await vi.waitFor(() => expect(listImports).toHaveBeenCalledTimes(2));
      await user.click(screen.getByRole('button', { name: 'Import' }));
      await vi.waitFor(() => expect(listImports).toHaveBeenCalledTimes(3));
    });

    it('refreshes the history after a refused commit', async () => {
      commitImport.mockRejectedValue(new ApiError(409, { code: 'IMPORT_STALE' }));
      const user = await toPreview();
      await vi.waitFor(() => expect(listImports).toHaveBeenCalledTimes(2));
      await user.click(screen.getByRole('button', { name: 'Import' }));
      await vi.waitFor(() => expect(listImports).toHaveBeenCalledTimes(3));
    });

    it('hides the members link without MEMBERS_VIEW', async () => {
      canViewMembers = false;
      commitImport.mockResolvedValue({ id: 'i1', counts: preview.counts });
      const user = await toPreview();
      await user.click(screen.getByRole('button', { name: 'Import' }));
      await screen.findByRole('heading', { name: 'Import complete' });
      expect(screen.getByRole('link', { name: 'Go to plots' })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'Go to members' })).not.toBeInTheDocument();
    });

    it('disables Import when the preview is not committable', async () => {
      await toPreview({ committable: false, problemCount: 1, problems: [] });
      expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();
    });

    it.each([
      ['IMPORT_STALE', 409, 'Upload the file again'],
      ['IMPORT_EXPIRED', 410, 'Upload the file again'],
    ])('on %s offers to upload again and resets the page', async (code, status, label) => {
      commitImport.mockRejectedValue(new ApiError(status, { code }));
      const user = await toPreview();
      await user.click(screen.getByRole('button', { name: 'Import' }));
      await user.click(await screen.findByRole('button', { name: label }));
      expect(screen.queryByRole('heading', { name: 'Check result' })).not.toBeInTheDocument();
      expect(screen.getByText('No file chosen')).toBeInTheDocument();
    });

    it.each([
      ['IMPORT_HAS_ERRORS', 'The file has problems'],
      ['IMPORT_NOTHING_TO_COMMIT', 'Nothing to import'],
      ['IMPORT_ALREADY_COMMITTED', 'already been imported'],
    ])('shows a notice for %s', async (code, text) => {
      commitImport.mockRejectedValue(new ApiError(409, { code }));
      const user = await toPreview();
      await user.click(screen.getByRole('button', { name: 'Import' }));
      expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Upload the file again' })).not.toBeInTheDocument();
    });

    it('shows the size message on HTTP 413', async () => {
      uploadImport.mockRejectedValue(new ApiError(413, undefined));
      renderPage();
      const user = userEvent.setup();
      await user.upload(screen.getByLabelText('Choose file'), xlsx());
      await user.click(screen.getByRole('button', { name: 'Check file' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('too large — the maximum is 10 MB');
    });
  });
});
