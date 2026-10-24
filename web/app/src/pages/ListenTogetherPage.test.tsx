// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { renderToString } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ListenTogetherPage from './ListenTogetherPage';

describe('party configuration page', () => {
  it('renders the actual signed-out page without an assumed public backend', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const html = renderToString(<MemoryRouter><QueryClientProvider client={client}>
      <ListenTogetherPage />
    </QueryClientProvider></MemoryRouter>);
    expect(html).toContain('Party server');
    expect(html).toContain('no hosted server configured');
    expect(html).toContain('https://your-party-server.example');
    client.clear();
  });
});
