import { afterEach, describe, expect, it, vi } from 'vitest';
import { metadataBytes, metadataFetch, metadataText } from './http';

afterEach(() => vi.unstubAllGlobals());
describe('bounded metadata transport', () => {
  it('rejects a decompressed stream crossing its budget and closes the reader', async () => {
    let cancelled = false;
    const response = new Response(new ReadableStream({
      pull(controller) { controller.enqueue(new Uint8Array(5)); },
      cancel() { cancelled = true; },
    }));
    await expect(metadataBytes(response, 8)).rejects.toThrow('size limit');
    expect(cancelled).toBe(true);
    expect(response.body?.locked).toBe(false);
  });
  it('rejects a declared oversize before consuming and preserves UTF-8 across chunks', async () => {
    let cancelled = false;
    await expect(metadataBytes(new Response(new ReadableStream({ cancel() { cancelled = true; } }),
      { headers: { 'Content-Length': '999' } }), 8)).rejects.toThrow('size limit');
    expect(cancelled).toBe(true);
    const encoded = new TextEncoder().encode('தமிழ் Telugu हिन्दी');
    const response = new Response(new ReadableStream({ start(controller) {
      controller.enqueue(encoded.slice(0, 2)); controller.enqueue(encoded.slice(2)); controller.close();
    } }));
    expect(await metadataText(response)).toBe('தமிழ் Telugu हिन्दी');
  });
  it('never follows a credential-bearing metadata redirect and always has a timeout', async () => {
    const request = vi.fn(async (_url: string | URL, _init: RequestInit) => new Response('{}'));
    vi.stubGlobal('fetch', request);
    await metadataFetch('https://example.invalid', { credentials: 'include', redirect: 'follow' });
    expect(request.mock.calls[0]?.[1]).toMatchObject({ credentials: 'include', redirect: 'error' });
    expect(request.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal);
  });
});
