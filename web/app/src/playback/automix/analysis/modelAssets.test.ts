import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadVerifiedAnalysisAsset, verifyAnalysisAsset, type AnalysisAsset } from './modelAssets';

const asset: AnalysisAsset = {
  url: 'https://example.invalid/pinned-model', bytes: 3,
  sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
};
const bytes = () => new TextEncoder().encode('abc').buffer;
afterEach(() => vi.unstubAllGlobals());

describe('optional audio analysis data', () => {
  it('checks the exact bytes rather than trusting the file name', async () => {
    expect(await verifyAnalysisAsset(asset, bytes())).toBe(true);
    expect(await verifyAnalysisAsset(asset, new TextEncoder().encode('abd').buffer)).toBe(false);
    expect(await verifyAnalysisAsset(asset, new ArrayBuffer(2))).toBe(false);
  });

  it('reuses a verified cached model without another network request', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    vi.stubGlobal('caches', { open: async () => ({ match: async () => new Response(bytes()) }) });
    expect(await loadVerifiedAnalysisAsset(asset)).toEqual(bytes());
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('replaces a corrupt cached copy only with integrity-checked data', async () => {
    const remove = vi.fn(async () => true);
    const put = vi.fn(async () => {});
    const fetcher = vi.fn(async () => new Response(bytes()));
    vi.stubGlobal('fetch', fetcher);
    vi.stubGlobal('caches', { open: async () => ({ match: async () => new Response('bad'), delete: remove, put }) });
    expect(await loadVerifiedAnalysisAsset(asset)).toEqual(bytes());
    expect(remove).toHaveBeenCalledWith(asset.url);
    expect(put).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledWith(asset.url, expect.objectContaining({ credentials: 'omit' }));
  });

  it('does not install changed upstream bytes in the cache', async () => {
    const put = vi.fn();
    vi.stubGlobal('fetch', async () => new Response('bad'));
    vi.stubGlobal('caches', { open: async () => ({ match: async () => undefined, put }) });
    await expect(loadVerifiedAnalysisAsset(asset)).rejects.toThrow('integrity');
    expect(put).not.toHaveBeenCalled();
  });
});
