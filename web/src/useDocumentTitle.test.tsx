import type { Me } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from './test/render';
import { useDocumentTitle } from './useDocumentTitle';

let me: Me | null;

vi.mock('./me', () => ({ useOptionalMe: () => me }));

function Probe({ page }: { page?: string }) {
  useDocumentTitle(page);
  return null;
}

describe('useDocumentTitle', () => {
  beforeEach(() => {
    me = { tenant: { id: 't1', name: 'Coop' } } as Me;
    document.title = '';
  });

  it('joins the page and the cooperative name', () => {
    renderWithApp(<Probe page="Members" />);
    expect(document.title).toBe('Members — Coop');
  });

  it('uses just the cooperative name without a page', () => {
    renderWithApp(<Probe />);
    expect(document.title).toBe('Coop');
  });

  it('falls back to the product name without a tenant', () => {
    me = null;
    renderWithApp(<Probe page="Members" />);
    expect(document.title).toBe('Members — Nadel');
  });

  it('uses just the product name without a page and a tenant', () => {
    me = null;
    renderWithApp(<Probe />);
    expect(document.title).toBe('Nadel');
  });
});
