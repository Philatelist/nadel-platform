import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { act, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryBoundary } from '../components/QueryBoundary';
import { renderWithApp } from '../test/render';
import { OfflineBanner } from './OfflineBanner';

const OFFLINE = 'No internet connection. Data will appear when the connection is back.';

afterEach(() => onlineManager.setOnline(true));

describe('offline', () => {
  it('shows the banner only while offline', () => {
    renderWithApp(<OfflineBanner />);
    expect(screen.queryByText(OFFLINE)).toBeNull();
    act(() => onlineManager.setOnline(false));
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    act(() => onlineManager.setOnline(true));
    expect(screen.queryByText(OFFLINE)).toBeNull();
  });

  it('QueryBoundary says «offline» instead of loading forever or a generic error', () => {
    onlineManager.setOnline(false);
    const query = { data: undefined, error: null, isPending: true, isError: false, refetch: vi.fn() };
    renderWithApp(
      <QueryClientProvider client={new QueryClient()}>
        <QueryBoundary query={query}>{() => <p>member data</p>}</QueryBoundary>
      </QueryClientProvider>,
    );
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    expect(screen.queryByText('member data')).toBeNull();
  });
});
