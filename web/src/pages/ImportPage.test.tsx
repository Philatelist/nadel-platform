import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { ImportPage } from './ImportPage';

const downloadImportTemplate = vi.fn();
const downloadBlob = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  downloadImportTemplate: (...args: unknown[]) => downloadImportTemplate(...args),
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
});
