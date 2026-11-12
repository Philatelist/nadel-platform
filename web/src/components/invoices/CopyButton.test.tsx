import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { CopyButton } from './CopyButton';

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function setClipboard(writeText: ((v: string) => Promise<void>) | undefined) {
  Object.defineProperty(navigator, 'clipboard', { value: writeText && { writeText }, configurable: true });
}

describe('CopyButton', () => {
  it('copies the value as received and announces it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard(writeText);
    renderWithApp(<CopyButton value="UA21 3223 1300" label="Copy IBAN" />);
    await userEvent.click(screen.getByRole('button', { name: 'Copy IBAN' }));
    expect(writeText).toHaveBeenCalledWith('UA21 3223 1300');
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
  });

  it('clears the announcement after about two seconds', async () => {
    setClipboard(vi.fn().mockResolvedValue(undefined));
    renderWithApp(<CopyButton value="x" label="Copy" />);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
    await waitFor(() => expect(screen.getByRole('status')).toBeEmptyDOMElement(), { timeout: 3000 });
  });

  it('falls back to execCommand when the clipboard API rejects', async () => {
    setClipboard(vi.fn().mockRejectedValue(new Error('denied')));
    const exec = vi.fn().mockReturnValue(true);
    document.execCommand = exec;
    renderWithApp(<CopyButton value="abc" label="Copy" />);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(exec).toHaveBeenCalledWith('copy');
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
  });

  it('shows a selectable read-only field when both methods fail', async () => {
    setClipboard(undefined);
    document.execCommand = vi.fn().mockReturnValue(false);
    renderWithApp(<CopyButton value="abc" label="Copy" />);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    const field = await screen.findByRole('textbox', { name: 'Copy' });
    expect(field).toHaveValue('abc');
    expect(field).toHaveAttribute('readonly');
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });
});
