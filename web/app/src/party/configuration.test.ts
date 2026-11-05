// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createParty, previewParty, savePartySettings } from './listenTogether';
import { sanitizeServerUrl } from './invite';

afterEach(() => { localStorage.clear(); vi.unstubAllGlobals(); });
describe('explicit party server', () => {
  it('never sends a party request to the static site when no backend is configured', async () => {
    localStorage.clear();
    const network = vi.fn(); vi.stubGlobal('fetch', network);
    await expect(createParty({ autoplayEnabled: false })).rejects.toMatchObject({ code: 'server_required' });
    await expect(previewParty('AB12CD')).rejects.toMatchObject({ code: 'server_required' });
    expect(network).not.toHaveBeenCalled();
  });
  it('rejects credentials and URL query/fragment data in a backend address', () => {
    for (const url of ['https://user:secret@example.com', 'https://example.com?token=x', 'https://example.com#x']) {
      expect(sanitizeServerUrl(url)).toBeNull();
    }
    expect(sanitizeServerUrl('https://example.com/party/')).toBe('https://example.com/party');
    savePartySettings({ customServer: 'https://example.com/party' });
  });
});
